package com.geminireader.analysis

import com.geminireader.data.Settings
import com.geminireader.data.json
import com.geminireader.text.Segment
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VoiceCatalogTest {
    private val line = Segment(0, 0, 5, "Hello", "0.0")
    @Test fun fixedVoiceEnginesPreserveNarratorCharacterVoiceAndPauses() {
        val voices = mapOf("fish" to FishVoices.names.last(), "speechify" to SpeechifyVoices.names.last(),
            "inworld" to InworldVoices.names.last(), "deepgram" to DeepgramVoices.names.last(),
            "cartesia" to CartesiaVoices.names.last(), "elevenlabs" to ElevenVoices.names.last(),
            "groq" to "hannah", "kokoro" to "bm_george", "android" to "en-installed")
        for ((engine, voice) in voices) for (mode in listOf("narrator", "distinct", "performance")) {
            val settings = Settings(engine = engine, characterMode = mode, androidTtsVoice = "en-installed")
            val character = Character("alice", "Alice", voice = voice)
            for (person in listOf(null, character)) for (end in listOf(false, true)) {
                val speech = VoiceDirector.direct(line, settings, person, "curious", end)
                val expected = if (engine != "android" && mode == "distinct" && person != null) voice else settings.speechVoice
                assertEquals("$engine/$mode", expected, speech.voice)
                assertEquals("", speech.prompt)
                assertEquals(if (end) settings.paragraphPauseMs else settings.withinPauseMs, speech.pauseMs)
                assertEquals(line.text, speech.text)
            }
        }
    }
    @Test fun completeUniqueCatalog() {
        assertEquals(30, VoiceCatalog.all.size)
        assertEquals(30, VoiceCatalog.names.toSet().size)
        assertEquals(14, VoiceDirector.female.size)
        assertEquals(16, VoiceDirector.male.size)
        assertEquals("informative", VoiceCatalog.find("Charon")!!.trait)
        assertEquals("female", VoiceCatalog.find("Kore")!!.gender)
        assertEquals("gravelly", VoiceCatalog.find("Algenib")!!.trait)
    }
    @Test fun everyVoiceUsesActualProfileInsteadOfStaleSettingsGender() {
        for (voice in VoiceCatalog.all) for (gender in listOf("female", "male", "unknown")) {
            val s = Settings(narratorVoice = voice.name, narratorGender = "incorrect")
            val result = VoiceDirector.direct(line, s, Character("c", "Character", gender = gender), "calm", true)
            assertEquals(voice.name, result.voice)
            assertTrue(result.prompt.contains(voice.context))
            val direction = VoiceCatalog.performance(voice.name, gender)
            assertTrue(result.prompt.contains(direction))
            when {
                voice.gender == "male" && gender == "female" -> assertTrue(direction.startsWith("Use a lighter, higher"))
                voice.gender == "female" && gender == "male" -> assertTrue(direction.startsWith("Use a slightly lower"))
                else -> assertTrue(direction.startsWith("Stay near"))
            }
        }
    }
    @Test fun recommendationAndManualOverrideUseActualDistinctVoice() {
        val s = Settings(characterMode = "distinct", narratorVoice = "Charon")
        val c = Character("alice", "Alice", gender = "female", suggestedVoice = "Leda")
        val automatic = VoiceDirector.direct(line, s, c, "", true)
        assertEquals("Leda", automatic.voice)
        assertTrue(automatic.prompt.contains("Stay near"))
        val override = VoiceDirector.direct(line, s, c.copy(voice = "Algenib"), "", true)
        assertEquals("Algenib", override.voice)
        assertTrue(override.prompt.contains("gravelly"))
        assertTrue(override.prompt.contains("lighter, higher"))
        assertEquals("Leda", VoiceDirector.distinctVoice(c.copy(voice = "invalid"), s))
    }
    @Test fun performanceDoesNotSwitchToRecommendedVoiceAndNarrationDoesNotAct() {
        val c = Character("alice", "Alice", gender = "female", suggestedVoice = "Leda")
        assertEquals("Charon", VoiceDirector.direct(line, Settings(), c, "", true).voice)
        val result = VoiceDirector.direct(line, Settings(characterMode = "narrator"), c, "", true)
        assertFalse(result.prompt.contains("Perform Alice"))
        assertFalse(result.prompt.contains("lighter, higher"))
    }
    @Test fun analysisHasCatalogAndConstrainedVoiceSuggestion() {
        val context = VoiceCatalog.analysisContext(Settings(narratorVoice = "Kore"))
        VoiceCatalog.all.forEach { assertTrue(context.contains(it.context)) }
        assertTrue(context.contains("Selected narrator: Kore"))
        val properties = CharacterAnalyzer.voiceSchema()["properties"]!!.jsonObject["characters"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(VoiceCatalog.names.toSet(), properties["suggestedVoice"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
    @Test fun narratorAndModeInvalidateAnalysisButLegacyGenderDoesNot() {
        val s = Settings()
        assertNotEquals(VoiceCatalog.cacheIdentity(s), VoiceCatalog.cacheIdentity(s.copy(narratorVoice = "Kore")))
        assertNotEquals(VoiceCatalog.cacheIdentity(s), VoiceCatalog.cacheIdentity(s.copy(characterMode = "distinct")))
        assertEquals(VoiceCatalog.cacheIdentity(s), VoiceCatalog.cacheIdentity(s.copy(narratorGender = "female")))
    }
    @Test fun oldCharacterRecordsRemainReadable() {
        val c = json.decodeFromString<Character>("""{"id":"alice","name":"Alice","voice":"Kore","edited":true}""")
        assertEquals("", c.suggestedVoice)
        assertEquals("Kore", VoiceDirector.distinctVoice(c, Settings()))
        assertTrue(c.edited)
    }
}
