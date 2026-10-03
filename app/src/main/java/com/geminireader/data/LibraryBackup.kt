package com.geminireader.data

import com.geminireader.analysis.Character
import com.geminireader.ReaderApp
import kotlinx.serialization.Serializable
import java.io.*
import java.util.UUID
import java.util.zip.*

@Serializable data class PortableBook(val book: Book, val cast: List<Character>, val position: Position, val reading: Position, val notes: String = "[]", val cover: String = "")
@Serializable data class LibraryArchive(val version: Int = 1, val books: List<PortableBook>, val pronunciations: List<Pronunciation>, val series: List<Series>, val links: Map<String, SeriesLink>)
/** Only these typed fields can enter a backup: settings, credentials, broker data and audio are excluded. */
object LibraryBackup {
    private const val MAX = 256 * 1024 * 1024
    fun write(app: ReaderApp, output: OutputStream) {
        val archive = LibraryArchive(books = app.books.list().map { meta ->
            val dir = app.books.directory(meta.id)
            PortableBook(app.books.load(meta.id), app.analyzer.cast(meta.id), app.books.position(meta.id), app.books.readingPosition(meta.id),
                File(dir, "notes.json").takeIf { it.isFile }?.readText() ?: "[]",
                File(dir, "cover").takeIf { it.isFile }?.let { java.util.Base64.getEncoder().encodeToString(it.readBytes()) }.orEmpty())
        }, pronunciations = app.performances.pronunciations(), series = app.performances.series(), links = app.performances.links())
        val bytes = json.encodeToString(archive).toByteArray()
        require(bytes.size <= MAX) { "Library exceeds the 256 MB backup limit" }
        ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("pagecast-library.json")); zip.write(bytes); zip.closeEntry() }
    }
    fun restore(app: ReaderApp, input: InputStream): Int {
        val archive = ZipInputStream(input).use { zip ->
            require(zip.nextEntry?.name == "pagecast-library.json") { "Not a PageCast library backup" }
            val collected = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                require(collected.size().toLong() + count <= MAX) { "Backup exceeds the 256 MB limit" }
                collected.write(buffer, 0, count)
            }
            val bytes = collected.toByteArray()
            require(bytes.size <= MAX) { "Backup exceeds the 256 MB limit" }
            require(zip.nextEntry == null) { "Unexpected backup entries" }
            json.decodeFromString<LibraryArchive>(bytes.toString(Charsets.UTF_8))
        }
        require(archive.version == 1 && archive.books.size <= 1000) { "Unsupported backup" }
        require(archive.books.map { it.book.id }.distinct().size == archive.books.size) { "Duplicate book IDs" }
        require(archive.series.map { it.id }.distinct().size == archive.series.size) { "Duplicate series IDs" }
        archive.pronunciations.forEach(PronunciationRules::validate)
        val originalPerformanceFiles = listOf("series.json", "series-links.json", "pronunciations.json").associateWith { name -> File(app.filesDir, name).takeIf { it.exists() }?.readText() }
        val ids = archive.books.associate { it.book.id to UUID.randomUUID().toString() }
        val seriesIds = archive.series.associate { it.id to UUID.randomUUID().toString() }
        val stage = File(app.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val staged = BookRepository(stage)
            archive.books.forEach { entry ->
                require(entry.book.chapters.isNotEmpty() && entry.book.chapters.all { it.paragraphs.isNotEmpty() }) { "Backup contains an empty book" }
                // Validate notes before writing; no paths from the archive are used for extraction.
                json.decodeFromString<List<ReadingNote>>(entry.notes)
                val id = ids.getValue(entry.book.id)
                val cover = entry.cover.takeIf { it.isNotBlank() }?.let { java.util.Base64.getDecoder().decode(it) }
                require(cover == null || cover.size <= 10 * 1024 * 1024) { "Cover too large" }
                staged.save(entry.book.copy(id = id, cover = ""), cover)
                staged.position(id, entry.position.copy(audioKey = "", offsetMs = 0))
                staged.readingPosition(id, entry.reading.copy(audioKey = "", offsetMs = 0))
                atomicWrite(File(staged.directory(id), "cast.json"), json.encodeToString(entry.cast))
                atomicWrite(File(staged.directory(id), "notes.json"), entry.notes)
            }
            val moved = mutableListOf<File>()
            try {
                ids.values.forEach { id -> val dest = app.books.directory(id); check(!dest.exists() && staged.directory(id).renameTo(dest)) { "Could not restore book" }; moved += dest }
                app.performances.saveSeries(app.performances.series() + archive.series.map { it.copy(id = seriesIds.getValue(it.id)) })
                app.performances.save(app.performances.pronunciations() + archive.pronunciations.mapNotNull { p ->
                    val owner = when (p.scope) { "global" -> ""; "book" -> ids[p.owner]; "series" -> seriesIds[p.owner]; else -> null } ?: return@mapNotNull null
                    PronunciationRules.validate(p); p.copy(id = UUID.randomUUID().toString(), owner = owner)
                })
                archive.links.forEach { (old, link) -> ids[old]?.let { id -> seriesIds[link.series]?.let { series -> app.performances.saveLink(id, link.copy(series = series)) } } }
            } catch (e: Exception) {
                originalPerformanceFiles.forEach { (name, original) -> runCatching {
                    val file = File(app.filesDir, name)
                    if (original == null) file.delete() else atomicWrite(file, original)
                }.onFailure { e.addSuppressed(it) } }
                moved.forEach { it.deleteRecursively() }; throw e
            }
            return ids.size
        } finally { stage.deleteRecursively() }
    }
}
