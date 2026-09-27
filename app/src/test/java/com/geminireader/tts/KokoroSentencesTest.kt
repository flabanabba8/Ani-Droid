package com.geminireader.tts

import com.geminireader.analysis.VoiceDirector
import com.geminireader.data.Settings
import com.geminireader.text.Segment
import com.geminireader.text.Segmenter
import org.junit.Assert.*
import org.junit.Test

class KokoroSentencesTest {
    @Test fun startsWithOneSentenceEvenWhenTheWholeParagraphFitsTheOldLimit() {
        val text = "The gate opened. A bird flew away! Where did it go?"
        val chunks = Segmenter.sentenceChunks(Segment(2, 10, 10 + text.length, text, "2.0"))
        assertEquals(listOf("The gate opened. ", "A bird flew away! ", "Where did it go?"), chunks.map { it.text })
        assertEquals(text, chunks.joinToString("") { it.text })
        assertEquals(10, chunks.first().start)
        assertEquals(10 + text.length, chunks.last().end)
        assertTrue(chunks.all { it.paragraph == 2 && it.q == "2.0" })
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.end == b.start })
        val s = Settings(engine = "kokoro", characterMode = "narrator", withinPauseMs = 80, paragraphPauseMs = 350)
        val speech = chunks.mapIndexed { i, segment -> VoiceDirector.direct(segment, s, null, "", i == chunks.lastIndex) }
        assertEquals(listOf(80, 80, 350), speech.map { it.pauseMs })
    }

    @Test fun splitsLongSentencesAtWordsAndRetainsUnicodeOffsets() {
        val text = "The " + "bright 😀 lantern ".repeat(40) + "glowed. Another sentence."
        val chunks = Segmenter.sentenceChunks(Segment(3, 25, text.length + 25, text, "3.2"))
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.text.length <= 350 && !it.text.last().isHighSurrogate() })
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.end == b.start })
        assertEquals(text.length + 25, chunks.last().end)
        assertTrue(chunks.dropLast(1).all { it.text.last().isWhitespace() })
        val unbroken = "😀".repeat(400)
        val hard = Segmenter.sentenceChunks(Segment(0, 0, unbroken.length, unbroken), 349)
        assertEquals(unbroken, hard.joinToString("") { it.text })
        assertTrue(hard.all { it.text.length <= 349 && !it.text.last().isHighSurrogate() })
    }

    @Test fun skipsSeparatorsAndPreservesQuotedSentences() {
        assertTrue(Segmenter.sentenceChunks(Segment(0, 0, 5, "* * *")).isEmpty())
        val text = "“Come inside. It is warm here!”"
        val chunks = Segmenter.sentenceChunks(Segment(7, 12, 12 + text.length, text, "7.1"))
        assertEquals(2, chunks.size)
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.q == "7.1" })
    }
}
