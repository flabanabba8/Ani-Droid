package com.geminireader.text

import java.text.BreakIterator
import java.util.Locale

@kotlinx.serialization.Serializable data class Segment(val paragraph: Int, val start: Int, val end: Int, val text: String, val q: String? = null)
object Segmenter {
    const val PLAN_VERSION = "punctuation-attached-v2"
    private val punctuationTypes = setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
    fun hasSpeech(text: String): Boolean = text.codePoints().anyMatch {
        !Character.isWhitespace(it) && !Character.isSpaceChar(it) && Character.getType(it) !in punctuationTypes
    }

    // Preserve source offsets and the speaker while attaching orphan punctuation.
    private fun attachPunctuation(segments: List<Segment>): List<Segment> {
        val result = mutableListOf<Segment>()
        for (segment in segments) {
            val previous = result.lastOrNull()
            if (previous != null && previous.paragraph == segment.paragraph && previous.end == segment.start &&
                (!hasSpeech(segment.text) || !hasSpeech(previous.text))) {
                result[result.lastIndex] = previous.copy(end = segment.end, text = previous.text + segment.text,
                    q = if (hasSpeech(previous.text)) previous.q else segment.q)
            } else result += segment
        }
        return result.filter { hasSpeech(it.text) }
    }
    fun isSceneBreak(text: String): Boolean {
        val compact = text.filterNot { it.isWhitespace() }
        return compact.length >= 3 && compact.all { it == '*' }
    }
    private val styles = listOf('„' to '“', '“' to '”', '"' to '"', '«' to '»', '‘' to '’', '\'' to '\'')
    private fun opening(text: String, index: Int, quote: Char): Boolean = text[index] == quote &&
        (quote !in listOf('‘', '\'') || ((index == 0 || !text[index - 1].isLetterOrDigit()) && index + 1 < text.length && !text[index + 1].isWhitespace()))
    private fun closing(text: String, index: Int, quote: Char): Boolean = text[index] == quote &&
        !(quote in listOf('’', '\'') && index > 0 && index + 1 < text.length && text[index - 1].isLetterOrDigit() && text[index + 1].isLetterOrDigit())
    fun dialogue(paragraphs: List<String>): List<Segment> {
        val style = styles.maxBy { (open, close) -> paragraphs.sumOf { p -> p.indices.sumOf { i -> if (opening(p, i, open)) 1 + (if (p.indexOf(close, i + 1) >= 0) 2 else 0) else 0 } } }
        return paragraphs.flatMapIndexed { index, text ->
            val result = mutableListOf<Segment>()
            var cursor = 0; var quote = 0
            while (cursor < text.length) {
                val start = (cursor until text.length).firstOrNull { opening(text, it, style.first) } ?: text.length
                result += rawChunks(index, text.substring(cursor, start), cursor)
                if (start == text.length) break
                val end = (start + 1 until text.length).firstOrNull { closing(text, it, style.second) }?.plus(1) ?: text.length
                result += rawChunks(index, text.substring(start, end), start, "$index.${quote++}")
                cursor = end
            }
            attachPunctuation(result)
        }
    }
    fun mergeUnknown(segments: List<Segment>, paragraphs: List<String>, known: Set<String>): List<Segment> {
        val result = mutableListOf<Segment>()
        var pending: Segment? = null
        fun flush() { pending?.let { result += chunks(it.paragraph, paragraphs[it.paragraph].substring(it.start, it.end), it.start, it.q) }; pending = null }
        segments.forEach { original ->
            val segment = if (original.q in known) original else original.copy(q = null)
            val previous = pending
            if (previous != null && previous.paragraph == segment.paragraph && previous.end == segment.start && previous.q == segment.q) pending = previous.copy(end = segment.end)
            else { flush(); pending = segment }
        }
        flush(); return result
    }
    fun sentences(text: String): List<IntRange> {
        val iterator = BreakIterator.getSentenceInstance(Locale.US).apply { setText(text) }
        val result = mutableListOf<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) { result += start until end; start = end; end = iterator.next() }
        return result
    }
    fun chunks(paragraph: Int, text: String, offset: Int = 0, q: String? = null, maxChars: Int = 1200): List<Segment> {
        return attachPunctuation(rawChunks(paragraph, text, offset, q, maxChars))
    }
    private fun rawChunks(paragraph: Int, text: String, offset: Int = 0, q: String? = null, maxChars: Int = 1200): List<Segment> {
        require(maxChars >= 2)
        if (isSceneBreak(text)) return emptyList()
        val result = mutableListOf<Segment>()
        var start = 0
        val ends = sentences(text).map { it.last + 1 }
        while (start < text.length) {
            var end = ends.lastOrNull { it > start && it <= start + maxChars } ?: minOf(start + maxChars, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            if (text.substring(start, end).isNotBlank()) result += Segment(paragraph, offset + start, offset + end, text.substring(start, end), q)
            start = end
        }
        return result
    }
    // Increment this when local queue boundaries change, so old prepared plans are rebuilt.
    const val LOCAL_SENTENCE_PLAN = "kokoro-sentences-v1"
    fun sentenceChunks(segment: Segment, maxChars: Int = 350): List<Segment> {
        require(maxChars >= 2)
        if (isSceneBreak(segment.text)) return emptyList()
        return attachPunctuation(sentences(segment.text).flatMap { sentence ->
            val result = mutableListOf<Segment>()
            var start = sentence.first
            val limit = sentence.last + 1
            while (start < limit) {
                var end = minOf(start + maxChars, limit)
                if (end < limit) {
                    if (segment.text[end - 1].isHighSurrogate()) end--
                    val space = (end - 1 downTo start + 1).firstOrNull { segment.text[it].isWhitespace() }
                    if (space != null) end = space + 1
                }
                val text = segment.text.substring(start, end)
                if (text.isNotBlank()) result += Segment(segment.paragraph, segment.start + start, segment.start + end, text, segment.q)
                start = end
            }
            result
        })
    }
    fun narration(paragraphs: List<String>): List<Segment> = paragraphs.flatMapIndexed { i, p -> chunks(i, p) }
}
