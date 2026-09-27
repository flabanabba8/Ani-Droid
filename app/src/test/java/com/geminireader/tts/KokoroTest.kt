package com.geminireader.tts

import com.geminireader.analysis.Character
import com.geminireader.analysis.KokoroVoices
import com.geminireader.analysis.VoiceDirector
import com.geminireader.data.*
import com.geminireader.text.Segment
import com.geminireader.text.Segmenter
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class KokoroTest {
    @Test fun nativeCallbackRetainsTypedBoxedSignatureAndHonorsCancellation() {
        val method = KokoroGenerationCallback::class.java.getDeclaredMethod("invoke", FloatArray::class.java)
        assertEquals(Integer::class.java, method.returnType)
        val job = Job()
        val callback = KokoroGenerationCallback(job)
        assertEquals(1, callback(floatArrayOf()))
        job.cancel()
        assertEquals(0, callback(floatArrayOf()))
    }

    @Test fun mapsBundledSpeakerIdsAndConvertsPcm() {
        assertEquals(28, KokoroVoices.names.size)
        assertEquals(3, KokoroVoices.speakerId("af_heart"))
        assertEquals(16, KokoroVoices.speakerId("am_michael"))
        assertEquals(26, KokoroVoices.speakerId("bm_george"))
        assertThrows(IllegalArgumentException::class.java) { KokoroVoices.speakerId("Charon") }
        val pcm = KokoroPcm.encode(floatArrayOf(-2f, 0f, 0.5f, 2f), 24000)
        assertEquals(24000, pcm.rate)
        val buffer = java.nio.ByteBuffer.wrap(pcm.bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals((-32767).toShort(), buffer.short)
        assertEquals(0.toShort(), buffer.short)
        assertEquals(16383.toShort(), buffer.short)
        assertEquals(32767.toShort(), buffer.short)
        assertThrows(IllegalArgumentException::class.java) { KokoroPcm.encode(floatArrayOf(Float.NaN), 24000) }
        assertThrows(IllegalArgumentException::class.java) { KokoroPcm.encode(floatArrayOf(), 24000) }
    }

    @Test fun factoryUsesInjectedLocalEngineAndNeverConstructsAnHttpBackend() {
        val local = object : TtsEngine {
            override suspend fun synthesize(speech: Speech, settings: Settings) = Pcm(byteArrayOf(1, 0))
        }
        assertSame(local, TtsEngines.create(Settings(engine = "kokoro"), HttpApi(), local))
        assertThrows(IllegalArgumentException::class.java) { TtsEngines.create(Settings(engine = "kokoro"), HttpApi()) }
    }

    @Test fun smallerLocalChunksPreserveTextAndOffsets() {
        val text = "A sentence. ".repeat(80) + "😀"
        val chunks = Segmenter.chunks(4, text, 7, "4.0", maxChars = 350)
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.text.length <= 350 && it.paragraph == 4 && it.q == "4.0" })
        assertEquals(7, chunks.first().start)
        assertEquals(text.length + 7, chunks.last().end)
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.end == b.start })
    }


    @Test fun keepsEngineVoicesAndCacheSeparate() {
        val settings = Settings(engine = "kokoro", characterMode = "distinct", kokoroVoice = "af_heart")
        val character = Character("one", "One", gender = "male", voice = "Charon")
        val speech = VoiceDirector.direct(Segment(0, 0, 5, "Hello", "0.0"), settings, character, "shout", true)
        assertTrue(speech.voice.startsWith("am_"))
        assertTrue(speech.voice in KokoroVoices.names)
        assertEquals("", speech.prompt)
        assertEquals("bf_emma", VoiceDirector.distinctVoice(character.copy(voice = "bf_emma"), settings))
        assertEquals("af_heart", VoiceDirector.direct(Segment(0, 0, 5, "Hello"), settings.copy(characterMode = "narrator"), character, "", true).voice)
        val key = AudioCache.key(speech, settings)
        assertEquals(key, AudioCache.key(speech, settings.copy(narratorPrompt = "Unused", model = "unused-google-model")))
        assertNotEquals(key, AudioCache.key(speech, settings.copy(engine = "vertex")))
    }

    @Test fun offlineSignatureIncludesKokoroModelAndVoice() {
        val root = Files.createTempDirectory("kokoro-offline").toFile()
        try {
            val books = BookRepository(root)
            val offline = OfflineChapters(books, PerformanceRepository(root))
            val book = books.save(Book(title = "Test", format = "txt", chapters = listOf(Chapter("One", listOf("Hello")))))
            val s = Settings(engine = "kokoro")
            assertNotEquals(offline.signature(book, 0, s), offline.signature(book, 0, s.copy(kokoroVoice = "am_michael")))
            assertNotEquals(offline.signature(book, 0, s), offline.signature(book, 0, s.copy(engine = "vertex")))
        } finally { root.deleteRecursively() }
    }

    @Test fun oldPreparedSceneBreakAudioIsNotReused() {
        val root = Files.createTempDirectory("old-scene-break").toFile()
        try {
            val books = BookRepository(root)
            val offline = OfflineChapters(books, PerformanceRepository(root))
            val book = books.save(Book(title = "Test", format = "txt", chapters = listOf(Chapter("One", listOf("***")))))
            val s = Settings()
            val speech = Speech("***", "", "Charon")
            val file = "${AudioCache.key(speech, s)}.wav"
            offline.audio(book.id, 0, file).writeBytes(Wav.encode(Pcm(byteArrayOf(1, 0))))
            offline.save(book.id, 0, PreparedChapter(offline.signature(book, 0, s), listOf(PreparedLine(Segment(0, 0, 3, "***"), speech, "Narrator", file)), complete = true))
            assertNull(offline.ready(book, 0, s))
        } finally { root.deleteRecursively() }
    }

    @Test fun sceneBreaksAreVisibleSourceButNeverSpeechSegments() {
        val paragraphs = listOf("Before.", "***", "* * *", "After.")
        assertEquals(listOf(0, 3), Segmenter.narration(paragraphs).map { it.paragraph })
        assertEquals(listOf(0, 3), Segmenter.dialogue(paragraphs).map { it.paragraph })
        assertFalse(Segmenter.isSceneBreak("A***B"))
        assertFalse(Segmenter.isSceneBreak("Thus my first order of business was summoning this Guardian Beast."))
    }
}
