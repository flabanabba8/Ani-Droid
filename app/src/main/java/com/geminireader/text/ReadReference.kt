package com.geminireader.text

import com.geminireader.data.Book

data class ReadExcerpt(val chapter: Int, val paragraph: Int, val text: String)
object ReadReference {
    fun before(book: Book, chapter: Int, paragraph: Int): Sequence<ReadExcerpt> = sequence {
        for (ch in 0..chapter.coerceAtMost(book.chapters.lastIndex)) {
            val texts = book.chapters[ch].paragraphs
            val end = if (ch == chapter) paragraph.coerceIn(0, texts.size) else texts.size
            for (p in 0 until end) yield(ReadExcerpt(ch, p, texts[p]))
        }
    }
    fun names(book: Book, chapter: Int, paragraph: Int): List<String> = before(book, chapter, paragraph)
        .flatMap { Regex("\\b\\p{Lu}[\\p{L}'’-]{2,}(?: \\p{Lu}[\\p{L}'’-]{2,})?").findAll(it.text).map { it.value } }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(100).map { it.key }
    fun find(book: Book, chapter: Int, paragraph: Int, query: String): List<ReadExcerpt> {
        if (query.isBlank()) return emptyList()
        val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(query.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return before(book, chapter, paragraph).filter { pattern.containsMatchIn(it.text) }.take(30).toList()
    }
}
