package com.geminireader.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geminireader.data.Settings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidTtsOnDeviceTest {
    @Test fun installedOfflineVoiceCacheCancellationAndRecovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AndroidTtsClient(context)
        val folder = File(context.cacheDir, "android-tts-test-cache")
        try {
            val engines = client.engines()
            assertTrue("No installed TTS engines", engines.isNotEmpty())
            val selected = engines.firstOrNull { it.id == "com.google.android.tts" } ?: engines.first()
            val catalog = client.catalog(selected.id)
            val voice = catalog.voices.firstOrNull { !it.isNetworkConnectionRequired }
            assertNotNull("No installed offline English voice", voice)
            val settings = Settings(engine = "android", characterMode = "narrator", androidTtsEngine = catalog.engine, androidTtsVoice = voice!!.name)
            val speech = Speech("The next chapter is ready.", "", voice.name)
            val pcm = client.synthesize(speech, settings)
            assertTrue(pcm.bytes.size > 1000); assertTrue(pcm.bytes.any { it != 0.toByte() })
            val cache = AudioCache(folder)
            val file = cache.get(speech, settings, client)
            val noGeneration = object : TtsEngine {
                override suspend fun synthesize(speech: Speech, settings: Settings): Pcm = error("Cache replay generated audio")
            }
            assertEquals(file, cache.get(speech, settings, noGeneration))
            val cancelled = launch { client.synthesize(speech.copy(text = "This is a cancellation test. ".repeat(100)), settings) }
            delay(30); cancelled.cancelAndJoin()
            assertTrue(client.synthesize(speech.copy(text = "Ready again."), settings).bytes.isNotEmpty())
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("android-tts-") && it.extension == "wav" })
            File(context.cacheDir, "android-system-reference.wav").writeBytes(Wav.encode(pcm))
            println("Android TTS: ${catalog.engine}; voice=${voice.name}; rate=${pcm.rate}; bytes=${pcm.bytes.size}")
        } finally { client.release(); folder.deleteRecursively() }
    }
}
