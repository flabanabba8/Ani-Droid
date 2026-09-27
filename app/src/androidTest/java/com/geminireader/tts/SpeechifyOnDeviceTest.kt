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
class SpeechifyOnDeviceTest {
    @Test fun realSpeechAndCustomCharacterVoice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("speechifyLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val s = SettingsStore(context).flow.first().copy(engine = "speechify", characterMode = "distinct")
        assertTrue(s.speechifyApiKey.isNotBlank())
        // Valid accessible voice deliberately outside the six-voice dropdown.
        val custom = "alicia"
        val character = Character("a", "Alice", voice = custom)
        val text = "The next chapter is ready."
        val speech = VoiceDirector.direct(com.geminireader.text.Segment(0, 0, text.length, text), s, character, "", true)
        assertEquals(custom, speech.voice)
        val api = HttpApi()
        val pcm = SpeechifyTtsClient(api).synthesize(speech, s)
        assertTrue(pcm.rate in 16000..48000)
        assertTrue(pcm.bytes.size > 24000)
        assertTrue(pcm.bytes.any { it != 0.toByte() })
        java.io.File(context.cacheDir, "speechify-custom-test.wav").writeBytes(Wav.encode(pcm))
    }
}
