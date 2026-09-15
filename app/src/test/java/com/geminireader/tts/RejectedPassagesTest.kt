package com.geminireader.tts

import com.geminireader.data.*
import com.geminireader.text.Segment
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RejectedPassagesTest {
    @Test fun persistsExactPassageAndSeparatesBooksAndChapters() {
        val root = Files.createTempDirectory("rejected-passages").toFile()
        try {
            val books = BookRepository(root)
            val records = RejectedPassages(books)
            val segment = Segment(3, 5, 9, "name")
            records.record("book-a", 0, segment, "PROHIBITED_CONTENT")
            records.record("book-a", 0, segment, "SAFETY")
            records.record("book-a", 1, segment, "SAFETY")
            records.record("book-b", 0, segment, "SAFETY")
            val restored = RejectedPassages(books)
            assertEquals(2, restored.list("book-a").size)
            assertEquals(segment, restored.list("book-a").first().segment)
            assertEquals("SAFETY", restored.list("book-a").first().reason)
            restored.resolved("book-a", 0, segment)
            assertEquals(1, restored.list("book-a").size)
            assertEquals(1, restored.list("book-b").size)
        } finally { root.deleteRecursively() }
    }
    @Test fun partialSuccessDoesNotClearLargerRejectedSpan() {
        val root = Files.createTempDirectory("rejected-spans").toFile()
        try {
            val records = RejectedPassages(BookRepository(root))
            records.record("book", 0, Segment(0, 0, 10, "0123456789"), "SAFETY")
            records.resolved("book", 0, Segment(0, 0, 5, "01234"))
            assertEquals(1, records.list("book").size)
            records.resolved("book", 0, Segment(0, 0, 10, "0123456789"))
            assertTrue(records.list("book").isEmpty())
        } finally { root.deleteRecursively() }
    }
}
