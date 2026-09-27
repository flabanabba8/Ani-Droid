package com.geminireader.data

import com.geminireader.analysis.Analysis
import com.geminireader.text.Segment
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PassageEditingTest {
    @Test fun editsPersistAndInvalidateOnlyAffectedState() {
        val root = Files.createTempDirectory("passage-edit").toFile()
        try {
            val books = BookRepository(root)
            val book = books.save(Book(id = "book", title = "Title", format = "txt", chapters = listOf(
                Chapter("One", listOf("\"Hello\"", "\"Goodbye\"")), Chapter("Two", listOf("Unchanged")))))
            val dir = books.directory(book.id)
            val overrides = File(dir, "analysis/0-overrides.json")
            atomicWrite(overrides, json.encodeToString(mapOf("0.0" to "alice", "1.0" to "bob")))
            atomicWrite(File(dir, "analysis/0.json"), json.encodeToString(Analysis()))
            atomicWrite(File(dir, "analysis/0-checkpoint.json"), "{}")
            atomicWrite(File(dir, "analysis/1.json"), "{}")
            books.position(book.id, Position(0, 0, 4, 1000, "old.wav", 1.5f))
            val rejections = RejectedPassages(books)
            rejections.record(book.id, 0, Segment(0, 0, 7, "\"Hello\""), "blocked")
            rejections.record(book.id, 0, Segment(1, 0, 9, "\"Goodbye\""), "blocked")
            val updated = books.editPassage(book.id, 0, 0, "\"Hello\"", "\"Welcome\"")
            assertEquals(updated, BookRepository(root).load(book.id))
            assertEquals(listOf("\"Welcome\"", "\"Goodbye\""), updated.chapters[0].paragraphs)
            assertEquals(book.chapters[1], updated.chapters[1])
            assertEquals(mapOf("1.0" to "bob"), json.decodeFromString<Map<String, String>>(overrides.readText()))
            assertFalse(File(dir, "analysis/0.json").exists())
            assertFalse(File(dir, "analysis/0-checkpoint.json").exists())
            assertTrue(File(dir, "analysis/1.json").exists())
            assertEquals(1, rejections.list(book.id).single().segment.paragraph)
            assertEquals(Position(0, 0, speed = 1.5f), books.position(book.id))
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsBlankAndStaleEditsWithoutChangingBook() {
        val root = Files.createTempDirectory("passage-validation").toFile()
        try {
            val books = BookRepository(root)
            val book = books.save(Book(title = "Test", format = "txt", chapters = listOf(Chapter("One", listOf("Original")))))
            assertThrows(IllegalArgumentException::class.java) { books.editPassage(book.id, 0, 0, "Original", " \n ") }
            assertThrows(IllegalArgumentException::class.java) { books.editPassage(book.id, 0, 0, "Outdated", "New") }
            assertEquals(book, books.load(book.id))
            assertEquals(book, books.editPassage(book.id, 0, 0, "Original", "Original"))
        } finally { root.deleteRecursively() }
    }
}
