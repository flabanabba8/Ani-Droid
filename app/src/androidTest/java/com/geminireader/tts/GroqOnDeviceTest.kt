package com.geminireader.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geminireader.analysis.GroqVoices
import com.geminireader.data.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroqOnDeviceTest {
    @Test fun liveCharacterAnalysisAndCachedReuse() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("groqLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as com.geminireader.ReaderApp
        val saved = SettingsStore(context).flow.first()
        val settings = com.geminireader.data.Settings(engine = "groq", groqApiKey = saved.groqApiKey,
            groqAnalysisEngine = "groq", characterMode = "distinct")
        val book = app.books.save(com.geminireader.data.Book(title = "Groq analysis fixture", format = "txt",
            chapters = listOf(com.geminireader.data.Chapter("One", listOf(
                "Alice waved. \"Hello, Bob!\" she said.",
                "Bob smiled. \"Good morning, Alice,\" he replied.")))))
        try {
            val result = app.analyzer.analyze(book, 0, settings, force = true)
            assertEquals(2, result.lines.size)
            assertEquals(setOf("alice", "bob"), result.characters.map { it.name.lowercase() }.toSet())
            assertTrue(result.lines.all { line -> result.characters.any { it.id == line.speaker } })
            assertTrue(result.characters.all { it.suggestedVoice in GroqVoices.names })
            val plan = app.playback.prepareChapter(book, 0, settings)
            val dialogue = plan.filter { it.q != null }
            assertEquals(2, dialogue.size)
            assertTrue(dialogue.all { app.playback.speechFor(it, settings, true).voice in GroqVoices.names })
            // A fresh analyzer with no key must use the durable saved result, not the network.
            val cached = com.geminireader.analysis.CharacterAnalyzer(app.books, HttpApi())
                .analyze(book, 0, settings.copy(groqApiKey = ""))
            assertEquals(result.fingerprint, cached.fingerprint)
            assertEquals(result.lines, cached.lines)
        } finally { app.books.delete(book.id) }
    }

    @Test fun liveEnglishSpeech() = runBlocking {
        assumeTrue("Explicit opt-in required for paid API requests",
            InstrumentationRegistry.getArguments().getString("groqLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = SettingsStore(context).flow.first()
        assertTrue("Provision Groq key first", saved.groqApiKey.isNotBlank())
        val client = GroqTtsClient(HttpApi())
        for ((model, voice, text) in listOf(
            Triple(GroqVoices.ENGLISH, "troy", "The reader is ready for the next chapter.")
        )) {
            val pcm = client.synthesize(Speech(text, "", voice), saved.copy(engine = "groq", groqModel = model, groqVoice = voice))
            assertEquals(24000, pcm.rate)
            assertTrue(pcm.bytes.size > 24000)
            assertTrue(pcm.bytes.any { it != 0.toByte() })
            java.io.File(context.cacheDir, "groq-${voice}-test.wav").writeBytes(Wav.encode(pcm))
        }
    }
}
