package com.geminireader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
@Serializable data class Chapter(val title: String, val paragraphs: List<String>)
@Serializable data class Book(val id: String = UUID.randomUUID().toString(), val title: String, val author: String = "", val format: String, val chapters: List<Chapter>, val cover: String = "")
@Serializable data class BookMeta(val id: String, val title: String, val author: String, val format: String, val chapters: Int, val cover: String)
@Serializable data class Position(val chapter: Int = 0, val paragraph: Int = 0, val segment: Int = 0, val offsetMs: Long = 0, val audioKey: String = "", val speed: Float = 1f, val savedAtMs: Long = 0L)

fun atomicWrite(file: File, value: String) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
    temporary.outputStream().use { output -> output.write(value.toByteArray(Charsets.UTF_8)); output.fd.sync() }
    check(temporary.renameTo(file)) { "Could not save ${file.name}" }
}

class BookRepository(val root: File) {
    init { root.mkdirs() }
    fun directory(id: String): File { require(id.matches(Regex("[a-zA-Z0-9-]+"))); return File(root, id) }
    fun list(): List<BookMeta> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull {
        runCatching { json.decodeFromString<BookMeta>(File(it, "meta.json").readText()) }.getOrNull()
    }.sortedBy { it.title.lowercase() }
    fun save(book: Book, coverBytes: ByteArray? = null): Book {
        val dir = directory(book.id).apply { mkdirs() }
        val saved = if (coverBytes != null) book.copy(cover = "cover") else book
        if (coverBytes != null) File(dir, "cover").writeBytes(coverBytes)
        atomicWrite(File(dir, "content.json"), json.encodeToString(saved))
        atomicWrite(File(dir, "meta.json"), json.encodeToString(BookMeta(saved.id, saved.title, saved.author, saved.format, saved.chapters.size, saved.cover)))
        return saved
    }
    fun load(id: String): Book = json.decodeFromString(File(directory(id), "content.json").readText())
    fun editPassage(id: String, chapter: Int, paragraph: Int, original: String, replacement: String): Book {
        require(replacement.isNotBlank()) { "Passage cannot be empty" }
        val book = load(id)
        val section = book.chapters.getOrNull(chapter) ?: error("Chapter no longer exists")
        require(section.paragraphs.getOrNull(paragraph) == original) { "Passage changed; reopen the editor and try again" }
        if (replacement == original) return book
        val updatedSection = section.copy(paragraphs = section.paragraphs.toMutableList().apply { this[paragraph] = replacement })
        val updated = book.copy(chapters = book.chapters.toMutableList().apply { this[chapter] = updatedSection })
        val dir = directory(id)
        // Keep assignments only where quote identity and text still match after segmentation.
        val overridesFile = File(dir, "analysis/$chapter-overrides.json")
        if (overridesFile.exists()) {
            val before = com.geminireader.text.Segmenter.dialogue(section.paragraphs).filter { it.q != null }.groupBy { it.q }
            val after = com.geminireader.text.Segmenter.dialogue(updatedSection.paragraphs).filter { it.q != null }.groupBy { it.q }
            val overrides = json.decodeFromString<Map<String, String>>(overridesFile.readText())
            atomicWrite(overridesFile, json.encodeToString(overrides.filterKeys { it in before && before[it] == after[it] }))
        }
        File(dir, "analysis").listFiles().orEmpty().filter {
            it.name == "$chapter.json" || (it.name.startsWith("$chapter-") && it.name != "$chapter-overrides.json")
        }.forEach { check(it.delete()) { "Could not reset chapter analysis" } }
        // Invalidate derived state before committing text; old audio is also guarded by its content signature.
        RejectedPassages(this).resolved(id, chapter, com.geminireader.text.Segment(paragraph, 0, Int.MAX_VALUE, ""))
        val position = position(id)
        if (position.chapter == chapter) position(id, position.copy(segment = 0, offsetMs = 0, audioKey = ""))
        atomicWrite(File(dir, "content.json"), json.encodeToString(updated))
        return updated
    }
    fun position(id: String): Position = runCatching { json.decodeFromString<Position>(File(directory(id), "position.json").readText()) }.getOrDefault(Position())
    fun position(id: String, value: Position) = atomicWrite(File(directory(id), "position.json"), json.encodeToString(value))
    fun readingPosition(id: String): Position {
        val reading = File(directory(id), "reading-position.json")
        if (reading.lastModified() < File(directory(id), "position.json").lastModified()) return position(id)
        return runCatching { json.decodeFromString<Position>(reading.readText()) }.getOrElse { position(id) }
    }
    fun readingPosition(id: String, value: Position) = atomicWrite(File(directory(id), "reading-position.json"), json.encodeToString(value))
    fun delete(id: String) { check(directory(id).deleteRecursively()) { "Could not delete book" } }
}
