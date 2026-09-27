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

class InworldTest {
    @Test fun basicAuthJsonAudioAndCacheReuse() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val dir = Files.createTempDirectory("inworld-cache").toFile()
        var path = ""; var key = ""; var body = ""; var calls = 0
        server.createContext("/") { e ->
            calls++; path = e.requestURI.toString(); key = e.requestHeaders.getFirst("Authorization")
            body = e.requestBody.bufferedReader().readText()
            val wav = Wav.encode(Pcm(ByteArray(4800) { (it % 127).toByte() }, 48000))
            val response = obj("audioContent" to str(java.util.Base64.getEncoder().encodeToString(wav))).toString().toByteArray()
            e.sendResponseHeaders(200, response.size.toLong()); e.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val s = Settings(engine = "inworld", inworldApiKey = "test-inworld", groqApiKey = "not-this")
            val speech = Speech("Hello.", "do not speak instructions", "Ashley")
            val engine = InworldTtsClient(HttpApi(), "http://127.0.0.1:${server.address.port}")
            val cache = AudioCache(dir)
            val file = cache.get(speech, s, engine)
            assertEquals(48000, Wav.decode(file.readBytes()).rate)
            assertEquals("/tts/v1/voice", path)
            assertEquals("Basic test-inworld", key)
            assertEquals(InworldTtsClient.body(speech, s), json.parseToJsonElement(body))
            server.stop(0)
            assertEquals(file, cache.get(speech, s, engine)); assertEquals(1, calls)
            assertNotEquals(AudioCache.key(speech, s), AudioCache.key(speech.copy(voice = InworldVoices.DEFAULT), s))
            assertEquals(AudioCache.key(speech, s), AudioCache.key(speech, s.copy(inworldApiKey = "rotated")))
        } finally { server.stop(0); dir.deleteRecursively() }
    }
    @Test fun ordinaryTruncationIsStillRejected() {
        val wav = Wav.encode(Pcm(ByteArray(4800), 24000))
        try { InworldTtsClient.decode(obj("audioContent" to str(java.util.Base64.getEncoder().encodeToString(wav.copyOf(100))))); fail("Truncated audio accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun customCharacterAndTextProviderAreIndependent() {
        assertFalse(InworldVoices.valid("Ashley&callback=https://example.com"))
        assertFalse(InworldVoices.valid("https://example.com/voice"))
        assertFalse(InworldVoices.valid("bad voice"))
        val c = Character("a", "Alice", voice = "Ashley")
        assertEquals(c.voice, InworldVoices.distinct(c, Settings()))
        assertEquals("groq", Settings(engine = "inworld").textEngine)
        assertEquals(InworldVoices.DEFAULT, Settings(engine = "inworld").speechVoice)
    }
    @Test fun estimatesUnicodeCharactersByBook() {
        val dir = Files.createTempDirectory("inworld-spending").toFile()
        try {
            val tracker = SpendingTracker(dir.resolve("history.json"))
            tracker.record(InworldTtsClient.BASE + "/tts/v1/voice", obj("model_id" to str("inworld-tts-2"), "text" to str("Hi😀")), obj("usage" to obj("processedCharactersCount" to JsonPrimitive(7))), SpendingBook("a", "A"))
            val row = tracker.state.value.rows.single()
            assertEquals("Inworld", row.provider); assertEquals("inworld-tts-2", row.model)
            assertEquals("a", row.bookId); assertEquals(0L, row.unpriced)
            assertEquals(7 * 25.0 / 1_000_000, row.usd, 0.00000001)
        } finally { dir.deleteRecursively() }
    }
}
