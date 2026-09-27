package com.geminireader.tts

import com.geminireader.analysis.GroqVoices
import com.geminireader.data.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class GroqTest {
    private fun streamed(): ByteArray {
        val wav = Wav.encode(Pcm(byteArrayOf(1, 2, 3, 4)))
        // Odd-sized metadata chunk, including its required padding.
        val metadata = byteArrayOf(76, 73, 83, 84, 1, 0, 0, 0, 65, 0)
        val result = wav.take(36).toByteArray() + metadata + wav.drop(36).toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(4, -1); putInt(50, -1) }
        return result
    }

    @Test fun decodesStreamedWavWithMetadataAndRejectsTruncation() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GroqTtsClient.decode(streamed()).bytes)
        try { GroqTtsClient.decode(Wav.encode(Pcm(ByteArray(8))).dropLast(2).toByteArray()); fail() }
        catch (_: IllegalArgumentException) {}
        try { GroqTtsClient.decode(Wav.encode(Pcm(ByteArray(0)))); fail() }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun splitsUnicodeWithoutLosingText() {
        val text = "A".repeat(199) + "😀 مرحباً " + "word ".repeat(100)
        val chunks = GroqTtsClient.chunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 200 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun sendsSeparateKeyAndModelConcatenatesAndRetriesOnlyTransientErrors() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = mutableListOf<JsonObject>()
        val headers = mutableListOf<String>()
        var calls = 0
        var denied = false
        server.createContext("/") { e ->
            calls++
            headers += e.requestHeaders.getFirst("Authorization")
            bodies += Json.parseToJsonElement(e.requestBody.bufferedReader().readText()).jsonObject
            val code = if (denied) 401 else if (calls == 1) 429 else 200
            e.responseHeaders.add("Retry-After", "0")
            val bytes = if (code == 200) streamed() else "{}".toByteArray()
            e.sendResponseHeaders(code, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val settings = Settings(engine = "groq", groqApiKey = "groq-test", apiKey = "google-secret",
                groqModel = GroqVoices.ENGLISH, groqVoice = "troy")
            val client = GroqTtsClient(HttpApi(), "http://127.0.0.1:${server.address.port}/")
            val result = client.synthesize(Speech("مرحبا ".repeat(80), "do not send", "troy"), settings)
            assertEquals((calls - 1) * 4, result.bytes.size)
            assertEquals(24000, result.rate)
            assertTrue(headers.all { it == "Bearer groq-test" })
            assertTrue(bodies.all { it["model"]!!.jsonPrimitive.content == GroqVoices.ENGLISH &&
                it["input"]!!.jsonPrimitive.content.length <= 200 && !it.toString().contains("do not send") })
            denied = true
            val before = calls
            try { client.synthesize(Speech("مرحبا", "", "troy"), settings); fail() }
            catch (e: ApiFailure) { assertEquals(401, e.code) }
            assertEquals(before + 1, calls)
        } finally { server.stop(0) }
    }

    @Test fun costsArePerModelAndBookAndCacheIgnoresCredentials() {
        val root = Files.createTempDirectory("groq-spending").toFile()
        try {
            val tracker = SpendingTracker(root.resolve("spending.json"))
            for ((model, book) in listOf(GroqVoices.ENGLISH to "book-one", GroqVoices.ENGLISH to "book-two"))
                tracker.record(GroqTtsClient.ENDPOINT, obj("model" to str(model), "input" to str("😀abc")), obj(), SpendingBook(book, book))
            assertEquals(4 * 22.0 / 1_000_000, tracker.state.value.rows.first { it.bookId == "book-one" }.usd, 1e-10)
            assertEquals(4 * 22.0 / 1_000_000, tracker.state.value.rows.first { it.bookId == "book-two" }.usd, 1e-10)
            val s = Settings(engine = "groq")
            val speech = Speech("hello", "", "troy")
            assertEquals(AudioCache.key(speech, s), AudioCache.key(speech, s.copy(groqApiKey = "rotated")))
            assertNotEquals(AudioCache.key(speech, s), AudioCache.key(speech.copy(voice = "hannah"), s.copy(groqVoice = "hannah")))
        } finally { root.deleteRecursively() }
    }
}
