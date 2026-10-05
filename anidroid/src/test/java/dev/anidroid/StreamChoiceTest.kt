package dev.anidroid

import org.junit.Assert.*
import org.junit.Test

class StreamChoiceTest {
    private fun s(h: Int)=Stream("s$h","https://example.invalid/$h",emptyMap(),height=h)
    @Test fun exactHeightWins() = assertEquals(480,chooseStream(listOf(s(360),s(480),s(1080)),480)!!.height)
    @Test fun fallsBackToHighestBelowCap() = assertEquals(480,chooseStream(listOf(s(1080),s(360),s(480)),720)!!.height)
    @Test fun fallsBackToLowestWhenAllExceedCap() = assertEquals(720,chooseStream(listOf(s(1080),s(720)),480)!!.height)
    @Test fun adaptiveStreamHandlesCap() = assertEquals(0,chooseStream(listOf(s(0),s(1080)),720)!!.height)
    @Test fun autoPrefersAdaptiveThenBest() { assertEquals(0,chooseStream(listOf(s(360),s(0)),0)!!.height);assertEquals(1080,chooseStream(listOf(s(360),s(1080)),0)!!.height);assertNull(chooseStream(emptyList(),720)) }
}
