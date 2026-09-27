package com.geminireader.tts

import com.geminireader.analysis.VoiceDirector
import com.geminireader.data.Settings
import com.geminireader.text.Segment
import org.junit.Assert.*
import org.junit.Test

class AndroidTtsTest {
    @Test fun narratorUsesInstalledVoiceAndCacheSeparatesEngineVoiceAndOfflinePolicy() {
        val s = Settings(engine = "android", characterMode = "narrator", androidTtsEngine = "one.engine", androidTtsVoice = "en-local")
        val speech = VoiceDirector.direct(Segment(0, 0, 6, "Hello."), s, null, "", true)
        assertEquals("en-local", speech.voice); assertEquals("", speech.prompt)
        val key = AudioCache.key(speech, s)
        assertNotEquals(key, AudioCache.key(speech, s.copy(androidTtsEngine = "other.engine")))
        assertNotEquals(key, AudioCache.key(speech, s.copy(androidTtsVoice = "other-voice")))
        assertNotEquals(key, AudioCache.key(speech, s.copy(androidTtsOfflineOnly = false)))
        assertEquals(key, AudioCache.key(speech, s.copy(groqApiKey = "unused", vertexToken = "unused")))
    }
}
