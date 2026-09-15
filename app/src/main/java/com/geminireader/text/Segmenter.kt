package com.geminireader.text

import java.text.BreakIterator
import java.util.Locale

data class Segment(val paragraph: Int, val start: Int, val end: Int, val text: String, val q: String? = null)
object Segmenter {
    fun sentences(text: String): List<IntRange> {
        val iterator = BreakIterator.getSentenceInstance(Locale.US).apply { setText(text) }
        val result = mutableListOf<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) { result += start until end; start = end; end = iterator.next() }
        return result
    }
    fun chunks(paragraph: Int, text: String, offset: Int = 0, q: String? = null): List<Segment> {
        val result = mutableListOf<Segment>()
        var start = 0
        val ends = sentences(text).map { it.last + 1 }
        while (start < text.length) {
            var end = ends.lastOrNull { it > start && it <= start + 1200 } ?: minOf(start + 1200, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            if (text.substring(start, end).isNotBlank()) result += Segment(paragraph, offset + start, offset + end, text.substring(start, end), q)
            start = end
        }
        return result
    }
    fun narration(paragraphs: List<String>): List<Segment> = paragraphs.flatMapIndexed { i, p -> chunks(i, p) }
}
