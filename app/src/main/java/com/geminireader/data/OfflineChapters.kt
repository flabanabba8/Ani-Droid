package com.geminireader.data

import com.geminireader.text.Segment
import com.geminireader.tts.*
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable data class PreparedLine(val segment: Segment, val speech: Speech, val speaker: String, val file: String)
@Serializable data class PreparedChapter(val signature: String, val lines: List<PreparedLine>, val complete: Boolean = false)
class OfflineChapters(private val books: BookRepository, private val performances: PerformanceRepository) {
    fun folder(id: String, chapter: Int): File { require(chapter >= 0); return File(books.directory(id), "offline/$chapter").apply { mkdirs() } }
    fun audio(id: String, chapter: Int, key: String): File { require(key.matches(Regex("[a-f0-9]{64}\\.wav"))); return File(folder(id, chapter), key) }
    fun signature(book: Book, chapter: Int, s: Settings): String {
        val playbackSettings = listOf(s.engine, s.model, s.analysisModel, s.vertexProject, s.vertexLocation, s.vertexUrl, s.cloudUrl, s.geminiUrl, s.language, s.narratorVoice, s.narratorPrompt, s.characterMode, s.withinPauseMs, s.paragraphPauseMs)
        val cast = File(books.directory(book.id), "cast.json").takeIf { it.exists() }?.readText().orEmpty()
        val overrides = File(books.directory(book.id), "analysis/$chapter-overrides.json").takeIf { it.exists() }?.readText().orEmpty()
        val link = performances.link(book.id)
        val profiles = performances.series().firstOrNull { it.id == link.series }?.profiles.orEmpty()
        val source = listOf("offline-v1", json.encodeToString(book.chapters[chapter]), playbackSettings.joinToString("\u0000"), json.encodeToString(performances.applicable(book.id)), json.encodeToString(link), json.encodeToString(profiles), cast, overrides).joinToString("\u0001")
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun read(id: String, chapter: Int): PreparedChapter? = runCatching { json.decodeFromString<PreparedChapter>(File(folder(id, chapter), "manifest.json").readText()) }.getOrNull()
    fun save(id: String, chapter: Int, value: PreparedChapter) = atomicWrite(File(folder(id, chapter), "manifest.json"), json.encodeToString(value))
    fun ready(book: Book, chapter: Int, settings: Settings): PreparedChapter? = read(book.id, chapter)?.takeIf { plan ->
        plan.complete && plan.signature == signature(book, chapter, settings) && plan.lines.isNotEmpty() && plan.lines.all { runCatching { WavExport.inspect(audio(book.id, chapter, it.file)); true }.getOrDefault(false) }
    }
}
