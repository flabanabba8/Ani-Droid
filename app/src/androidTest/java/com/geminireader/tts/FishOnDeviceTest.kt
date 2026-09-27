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
class FishOnDeviceTest {
    @Test fun realSpeechAndCustomCharacterVoice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fishLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val s = SettingsStore(context).flow.first().copy(engine = "fish", characterMode = "distinct")
        assertTrue(s.fishApiKey.isNotBlank())
        // Valid accessible voice deliberately outside the six-voice dropdown.
        val custom = "0efe38923c0b445ab09382e851ae1e30"
        val character = Character("a", "Alice", voice = custom)
        val text = "The next chapter is ready."
        val speech = VoiceDirector.direct(com.geminireader.text.Segment(0, 0, text.length, text), s, character, "", true)
        assertEquals(custom, speech.voice)
        val api = HttpApi()
        val pcm = FishTtsClient(api).synthesize(speech, s)
        assertEquals(44100, pcm.rate)
        assertTrue(pcm.bytes.size > 24000)
        assertTrue(pcm.bytes.any { it != 0.toByte() })
        java.io.File(context.cacheDir, "fish-custom-test.wav").writeBytes(Wav.encode(pcm))
    }
}
