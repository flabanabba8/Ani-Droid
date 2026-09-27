package com.geminireader.tts

import com.geminireader.analysis.*
import com.geminireader.data.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files

class DeepgramTest {
    @Test fun tokenAuthVoiceQueryStreamedAudioAndCacheReuse() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val dir = Files.createTempDirectory("deepgram-cache").toFile()
        var path = ""; var key = ""; var body = ""; var calls = 0
        server.createContext("/") { e ->
            calls++; path = e.requestURI.toString(); key = e.requestHeaders.getFirst("Authorization")
            body = e.requestBody.bufferedReader().readText()
            val wav = Wav.encode(Pcm(ByteArray(4800) { (it % 127).toByte() }, 24000))
            java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { putInt(4, 0x7fff0024); putInt(40, 0x7fff0000) }
            e.sendResponseHeaders(200, wav.size.toLong()); e.responseBody.use { it.write(wav) }
        }
        server.start()
        try {
            val s = Settings(engine = "deepgram", deepgramApiKey = "test-deepgram", groqApiKey = "not-this")
            val speech = Speech("Hello.", "do not speak instructions", "flux-haley-en")
            val engine = DeepgramTtsClient(HttpApi(), "http://127.0.0.1:${server.address.port}")
            val cache = AudioCache(dir)
            val file = cache.get(speech, s, engine)
            assertEquals(24000, Wav.decode(file.readBytes()).rate)
            assertEquals("/v2/speak?model=flux-haley-en&encoding=linear16&container=wav&sample_rate=24000", path)
            assertEquals("Token test-deepgram", key)
            assertEquals(obj("text" to str("Hello.")), json.parseToJsonElement(body))
            server.stop(0)
            assertEquals(file, cache.get(speech, s, engine)); assertEquals(1, calls)
            assertNotEquals(AudioCache.key(speech, s), AudioCache.key(speech.copy(voice = DeepgramVoices.DEFAULT), s))
            assertEquals(AudioCache.key(speech, s), AudioCache.key(speech, s.copy(deepgramApiKey = "rotated")))
        } finally { server.stop(0); dir.deleteRecursively() }
    }
    @Test fun ordinaryTruncationIsStillRejected() {
        val wav = Wav.encode(Pcm(ByteArray(4800), 24000))
        try { DeepgramTtsClient.decode(wav.copyOf(100)); fail("Truncated audio accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun customCharacterAndTextProviderAreIndependent() {
        assertFalse(DeepgramVoices.valid("flux-haley-en&callback=https://example.com"))
        assertFalse(DeepgramVoices.valid("https://example.com/voice"))
        assertFalse(DeepgramVoices.valid("flux-haley-ar"))
        val c = Character("a", "Alice", voice = "flux-haley-en")
        assertEquals(c.voice, DeepgramVoices.distinct(c, Settings()))
        assertEquals("groq", Settings(engine = "deepgram").textEngine)
        assertEquals(DeepgramVoices.DEFAULT, Settings(engine = "deepgram").speechVoice)
    }
    @Test fun estimatesUnicodeCharactersByBook() {
        val dir = Files.createTempDirectory("deepgram-spending").toFile()
        try {
            val tracker = SpendingTracker(dir.resolve("history.json"))
            tracker.record(DeepgramTtsClient.BASE + "/v2/speak?model=flux-hannah-en", obj("text" to str("Hi😀")), obj(), SpendingBook("a", "A"))
            val row = tracker.state.value.rows.single()
            assertEquals("Deepgram", row.provider); assertEquals("flux-hannah-en", row.model)
            assertEquals("a", row.bookId); assertEquals(0L, row.unpriced)
            assertEquals(3 * 45.0 / 1_000_000, row.usd, 0.00000001)
        } finally { dir.deleteRecursively() }
    }
}
