package com.geminireader.importer

import com.geminireader.data.Book

data class CleanupOptions(val pageNumbers: Boolean = false, val footnoteMarkers: Boolean = false, val wrappedLines: Boolean = false, val header: String = "")
object ImportCleanup {
    fun paragraph(text: String, options: CleanupOptions): String {
        if (options.pageNumbers && Regex("(?i)^(?:page\\s+)?\\d{1,6}(?:\\s+of\\s+\\d{1,6})?$").matches(text.trim())) return ""
        if (options.header.isNotBlank() && text.trim() == options.header.trim()) return ""
        var result = text
        if (options.footnoteMarkers) result = result.replace(Regex("\\[\\d{1,3}\\]|[¹²³⁴⁵⁶⁷⁸⁹⁰]+"), "")
        if (options.wrappedLines) result = result.replace(Regex("(?<=\\p{L})-\\r?\\n(?=\\p{Ll})"), "").replace(Regex("[ \\t]*\\r?\\n[ \\t]*"), " ")
        return if (result == text) text else result.trim()
    }
    fun apply(book: Book, options: CleanupOptions): Book = book.copy(chapters = book.chapters.map { ch ->
        ch.copy(paragraphs = ch.paragraphs.map { paragraph(it, options) }.filter { it.isNotBlank() })
    }.filter { it.paragraphs.isNotEmpty() }).also { require(it.chapters.isNotEmpty()) { "Cleanup would remove the entire book" } }
    fun headers(book: Book): List<String> = book.chapters.asSequence().flatMap { it.paragraphs.asSequence() }.filter { it.length in 3..100 }
        .groupingBy { it.trim() }.eachCount().filterValues { it >= 3 }.keys.take(30)
}
