package com.geminireader.data

import com.geminireader.text.Segment
import java.io.File
import kotlinx.serialization.Serializable

@Serializable data class RejectedPassage(val chapter: Int, val segment: Segment, val reason: String)

/** Book-local records use original text offsets, never pronunciation-adjusted speech offsets. */
class RejectedPassages(private val books: BookRepository, private val filename: String = "rejected-passages.json") {
    private fun file(book: String) = File(books.directory(book), filename)
    @Synchronized fun list(book: String): List<RejectedPassage> =
        file(book).takeIf { it.exists() }?.let { json.decodeFromString(it.readText()) } ?: emptyList()

    @Synchronized fun record(book: String, chapter: Int, segment: Segment, reason: String) {
        val records = list(book).filterNot { it.chapter == chapter && it.segment == segment }
        atomicWrite(file(book), json.encodeToString(records + RejectedPassage(chapter, segment, reason)))
    }

    @Synchronized fun resolved(book: String, chapter: Int, segment: Segment) {
        val records = list(book)
        val remaining = records.filterNot {
            it.chapter == chapter && it.segment.paragraph == segment.paragraph &&
                it.segment.start >= segment.start && it.segment.end <= segment.end
        }
        if (remaining != records) atomicWrite(file(book), json.encodeToString(remaining))
    }

    @Synchronized fun dismiss(book: String, dismissed: List<RejectedPassage>) {
        val records = list(book)
        val remaining = records.filterNot { it in dismissed }
        if (remaining != records) atomicWrite(file(book), json.encodeToString(remaining))
    }
}
