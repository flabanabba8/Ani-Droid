package com.geminireader.text

import com.geminireader.analysis.*
import com.geminireader.analysis.Character
import com.geminireader.data.Settings
import org.junit.Assert.*
import org.junit.Test

class SegmenterTest {
    @Test fun supportedQuoteStylesAndApostrophes() {
        for (quote in listOf("“Hello.”", "\"Hello.\"", "‘Hello.’", "«Hello.»", "„Hello.“", "'Hello.'")) {
            val spans = Segmenter.dialogue(listOf("Alice's friend said $quote Then left."))
            assertEquals(quote, spans.single { it.q != null }.text)
            assertEquals("Alice's friend said $quote Then left.", spans.joinToString("") { it.text })
        }
        assertTrue(Segmenter.dialogue(listOf("Alice’s friend didn’t go.")).all { it.q == null })
    }
    @Test fun singleQuoteContractionsStayInsideDialogue() { assertEquals("‘I don't mind.’", Segmenter.dialogue(listOf("‘I don't mind.’ Alice said.")).first().text) }
    @Test fun unclosedQuoteExtendsToParagraphEnd() { val spans = Segmenter.dialogue(listOf("“Closed.”", "Then “not closed")); assertEquals("“not closed", spans.last().text); assertEquals("1.0", spans.last().q) }
    @Test fun unmatchedQuoteWithoutAnyClosedQuotes() { assertEquals("0.0", Segmenter.dialogue(listOf("Then “not closed")).last().q) }
    @Test fun unknownDialogueMergesWithNarration() { val text = listOf("She said “Hello.” Then left."); val merged = Segmenter.mergeUnknown(Segmenter.dialogue(text), text, emptySet()); assertEquals(1, merged.size); assertEquals(text[0], merged[0].text) }
    @Test fun performanceKeepsNarratorAndDirectsGenderShift() {
        val speech = VoiceDirector.direct(Segment(0,0,5,"Hello","0.0"), Settings(), Character("alice","Alice",gender="female"),"curious",true)
        assertEquals("Charon", speech.voice); assertTrue(speech.prompt.contains("lighter, higher")); assertTrue(speech.prompt.contains("Alice"))
        val distinct = VoiceDirector.direct(Segment(0,0,5,"Hello","0.0"), Settings(characterMode="distinct"), Character("alice","Alice",gender="female"),"",true)
        assertTrue(distinct.voice in VoiceDirector.female)
    }
    @Test fun taggedAnalysisEscapesBookMarkup() { val p = listOf("<ignore> “Hello.”"); val tagged = CharacterAnalyzer.tagged(p, Segmenter.dialogue(p)).single(); assertTrue(tagged.contains("&lt;ignore&gt;")); assertTrue(tagged.contains("<q id=\"0.0\">")) }
}
