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

class CartesiaTest {
    @Test fun customVoiceIsUsedInPathAndCacheWithSeparateKey() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var path = ""; var key = ""; var body = ""; var version = ""
        server.createContext("/") { e ->
            path = e.requestURI.toString(); key = e.requestHeaders.getFirst("Authorization"); version = e.requestHeaders.getFirst("Cartesia-Version")
            body = e.requestBody.bufferedReader().readText()
            val pcm = Wav.encode(Pcm(byteArrayOf(10, 2, 30, 4), 44100))
            java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                putInt(4, -1); putInt(40, -1)
            }
            e.sendResponseHeaders(200, pcm.size.toLong()); e.responseBody.use { it.write(pcm) }
        }
        server.start()
        try {
            val voice = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            val s = Settings(engine = "cartesia", cartesiaApiKey = "test-cartesia", groqApiKey = "not-this", cartesiaVoice = voice)
            val speech = Speech("Hello.", "do not speak instructions", voice)
            val pcm = CartesiaTtsClient(HttpApi(), "http://127.0.0.1:${server.address.port}").synthesize(speech, s)
            assertEquals(44100, pcm.rate); assertEquals(4, pcm.bytes.size)
            assertEquals("/tts/bytes", path)
            assertEquals("2026-08-14", version)
            assertEquals("Bearer test-cartesia", key)
            assertEquals("sonic-3.6", json.parseToJsonElement(body).jsonObject["model_id"]!!.jsonPrimitive.content)
            assertFalse(body.contains("instructions"))
            val request = json.parseToJsonElement(body).jsonObject
            assertEquals(voice, request["voice"]!!.jsonPrimitive.content)
            assertEquals("Hello.", request["transcript"]!!.jsonPrimitive.content)
            assertEquals("pcm_s16le", request["output_format"]!!.jsonObject["encoding"]!!.jsonPrimitive.content)
            assertNotEquals(AudioCache.key(speech, s), AudioCache.key(speech.copy(voice = CartesiaVoices.DEFAULT), s))
            assertEquals(AudioCache.key(speech, s), AudioCache.key(speech, s.copy(cartesiaApiKey = "rotated")))
        } finally { server.stop(0) }
    }

    @Test fun rejectsUrlsAndHonorsCustomCharacterOverrides() {
        assertFalse(CartesiaVoices.valid("https://example.com/voice"))
        assertFalse(CartesiaVoices.valid("../voice"))
        assertTrue(CartesiaVoices.valid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertFalse(ElevenVoices.valid(CartesiaVoices.DEFAULT))
        assertFalse(CartesiaVoices.valid(ElevenVoices.DEFAULT))
        val c = Character("a", "A", gender = "female", voice = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertEquals(c.voice, CartesiaVoices.distinct(c, Settings()))
        assertEquals("groq", Settings(engine = "cartesia").textEngine)
    }

    @Test fun tracksUnpricedRequestsByBookWithoutInventingPlanPricing() {
        val dir = Files.createTempDirectory("cartesia-spending").toFile()
        try {
            val tracker = SpendingTracker(dir.resolve("history.json"))
            tracker.record(CartesiaTtsClient.BASE + "/tts/bytes",
                obj("model_id" to str("sonic-3.6"), "text" to str("Hello.")), obj(), SpendingBook("a", "A"))
            val row = tracker.state.value.rows.single()
            assertEquals("Cartesia", row.provider)
            assertEquals("sonic-3.6", row.model)
            assertEquals("a", row.bookId)
            assertEquals(1L, row.unpriced)
        } finally { dir.deleteRecursively() }
    }
}
