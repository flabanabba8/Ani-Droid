package com.geminireader.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geminireader.analysis.*
import com.geminireader.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElevenOnDeviceTest {
    @Test fun realSpeechAndCustomCharacterVoice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("elevenLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val s = SettingsStore(context).flow.first().copy(engine = "elevenlabs", characterMode = "distinct")
        assertTrue(s.elevenApiKey.isNotBlank())
        // Valid accessible voice deliberately outside the eight-voice dropdown.
        val custom = "FGY2WhTYpPnrIDTdsKH5"
        val character = Character("a", "Alice", voice = custom)
        val text = "The next chapter is ready."
        val speech = VoiceDirector.direct(com.geminireader.text.Segment(0, 0, text.length, text), s, character, "", true)
        assertEquals(custom, speech.voice)
        val pcm = ElevenTtsClient(HttpApi()).synthesize(speech, s)
        assertEquals(24000, pcm.rate)
        assertTrue(pcm.bytes.size > 24000)
        assertTrue(pcm.bytes.any { it != 0.toByte() })
        java.io.File(context.cacheDir, "eleven-custom-test.wav").writeBytes(Wav.encode(pcm))
    }
}
