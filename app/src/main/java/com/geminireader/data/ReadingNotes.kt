package com.geminireader.data

import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable data class ReadingNote(val id: String = UUID.randomUUID().toString(), val chapter: Int, val paragraph: Int, val start: Int, val end: Int, val quote: String, val note: String = "", val createdAt: Long = System.currentTimeMillis())
class ReadingNotes(private val books: BookRepository) {
    private fun file(id: String) = File(books.directory(id), "notes.json")
    @Synchronized fun list(id: String): List<ReadingNote> = file(id).takeIf { it.isFile }?.let { json.decodeFromString(it.readText()) } ?: emptyList()
    @Synchronized fun save(id: String, note: ReadingNote) {
        require(note.quote.isNotBlank() && note.note.length <= 10_000)
        atomicWrite(file(id), json.encodeToString(list(id).filterNot { it.id == note.id } + note))
    }
    @Synchronized fun delete(id: String, note: String) = atomicWrite(file(id), json.encodeToString(list(id).filterNot { it.id == note }))
    fun unchanged(book: Book, note: ReadingNote): Boolean = book.chapters.getOrNull(note.chapter)?.paragraphs?.getOrNull(note.paragraph)?.let { text -> note.start >= 0 && note.end <= text.length && note.start < note.end && text.substring(note.start, note.end) == note.quote } ?: false
    fun export(book: Book, notes: List<ReadingNote>): String = buildString {
        appendLine(book.title); if (book.author.isNotBlank()) appendLine(book.author)
        notes.forEach { entry ->
            appendLine(); appendLine("Chapter ${entry.chapter + 1}: ${book.chapters.getOrNull(entry.chapter)?.title.orEmpty()} · paragraph ${entry.paragraph + 1}")
            appendLine(entry.quote); if (entry.note.isNotBlank()) appendLine("Note: ${entry.note}")
        }
    }
}
