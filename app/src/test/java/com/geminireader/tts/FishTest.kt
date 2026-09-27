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

class FishTest {
    @Test fun tokenAuthVoiceQueryStreamedAudioAndCacheReuse() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val dir = Files.createTempDirectory("fish-cache").toFile()
        var path = ""; var key = ""; var body = ""; var calls = 0; var model = ""
        server.createContext("/") { e ->
            model = e.requestHeaders.getFirst("model"); calls++; path = e.requestURI.toString(); key = e.requestHeaders.getFirst("Authorization")
            body = e.requestBody.bufferedReader().readText()
            val wav = Wav.encode(Pcm(ByteArray(4800) { (it % 127).toByte() }, 44100))
            java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { putInt(4, -220); putInt(40, -256) }
            e.sendResponseHeaders(200, wav.size.toLong()); e.responseBody.use { it.write(wav) }
        }
        server.start()
        try {
            val s = Settings(engine = "fish", fishApiKey = "test-fish", groqApiKey = "not-this")
            val speech = Speech("Hello.", "do not speak instructions", "0efe38923c0b445ab09382e851ae1e30")
            val engine = FishTtsClient(HttpApi(), "http://127.0.0.1:${server.address.port}")
            val cache = AudioCache(dir)
            val file = cache.get(speech, s, engine)
            assertEquals(44100, Wav.decode(file.readBytes()).rate)
            assertEquals("/v1/tts", path)
            assertEquals("Bearer test-fish", key)
            assertEquals("s2.1-pro-free", model)
            val request = json.parseToJsonElement(body).jsonObject
            assertEquals("Hello.", request["text"]!!.jsonPrimitive.content)
            assertEquals("0efe38923c0b445ab09382e851ae1e30", request["reference_id"]!!.jsonPrimitive.content)
            assertEquals("normal", request["latency"]!!.jsonPrimitive.content)
            assertEquals("wav", request["format"]!!.jsonPrimitive.content)
            assertFalse(body.contains("instructions"))
            server.stop(0)
            assertEquals(file, cache.get(speech, s, engine)); assertEquals(1, calls)
            assertNotEquals(AudioCache.key(speech, s), AudioCache.key(speech.copy(voice = FishVoices.DEFAULT), s))
            assertEquals(AudioCache.key(speech, s), AudioCache.key(speech, s.copy(fishApiKey = "rotated")))
        } finally { server.stop(0); dir.deleteRecursively() }
    }
    @Test fun ordinaryTruncationIsStillRejected() {
        val wav = Wav.encode(Pcm(ByteArray(4800), 24000))
        try { FishTtsClient.decode(wav.copyOf(100)); fail("Truncated audio accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun customCharacterAndTextProviderAreIndependent() {
        assertFalse(FishVoices.valid("0efe38923c0b445ab09382e851ae1e30&callback=https://example.com"))
        assertFalse(FishVoices.valid("https://example.com/voice"))
        assertFalse(FishVoices.valid("flux-haley-ar"))
        val c = Character("a", "Alice", voice = "0efe38923c0b445ab09382e851ae1e30")
        assertEquals(c.voice, FishVoices.distinct(c, Settings()))
        assertEquals("groq", Settings(engine = "fish").textEngine)
        assertEquals(FishVoices.DEFAULT, Settings(engine = "fish").speechVoice)
    }
    @Test fun estimatesUnicodeCharactersByBook() {
        val dir = Files.createTempDirectory("fish-spending").toFile()
        try {
            val tracker = SpendingTracker(dir.resolve("history.json"))
            tracker.record(FishTtsClient.BASE + "/v1/tts", obj("model" to str("s2.1-pro-free"), "text" to str("Hi😀")), obj(), SpendingBook("a", "A"))
            val row = tracker.state.value.rows.single()
            assertEquals("Fish Audio", row.provider); assertEquals("s2.1-pro-free", row.model)
            assertEquals("a", row.bookId)
            assertEquals(if (java.time.LocalDate.now().isAfter(java.time.LocalDate.of(2026, 11, 30))) 1L else 0L, row.unpriced)
            assertEquals(0.0, row.usd, 0.00000001)
        } finally { dir.deleteRecursively() }
    }
}
