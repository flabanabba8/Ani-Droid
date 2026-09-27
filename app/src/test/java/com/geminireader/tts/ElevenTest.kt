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

class ElevenTest {
    @Test fun customVoiceIsUsedInPathAndCacheWithSeparateKey() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var path = ""; var key = ""; var body = ""
        server.createContext("/") { e ->
            path = e.requestURI.toString(); key = e.requestHeaders.getFirst("xi-api-key")
            body = e.requestBody.bufferedReader().readText()
            val pcm = byteArrayOf(10, 2, 30, 4)
            e.sendResponseHeaders(200, pcm.size.toLong()); e.responseBody.use { it.write(pcm) }
        }
        server.start()
        try {
            val voice = "CustomVoice1234567890"
            val s = Settings(engine = "elevenlabs", elevenApiKey = "test-eleven", groqApiKey = "not-this", elevenVoice = voice)
            val speech = Speech("Hello.", "do not speak instructions", voice)
            val pcm = ElevenTtsClient(HttpApi(), "http://127.0.0.1:${server.address.port}").synthesize(speech, s)
            assertEquals(24000, pcm.rate); assertEquals(4, pcm.bytes.size)
            assertEquals("/v1/text-to-speech/$voice?output_format=pcm_24000", path)
            assertEquals("test-eleven", key)
            assertEquals("eleven_flash_v2_5", json.parseToJsonElement(body).jsonObject["model_id"]!!.jsonPrimitive.content)
            assertFalse(body.contains("instructions"))
            assertNotEquals(AudioCache.key(speech, s), AudioCache.key(speech.copy(voice = ElevenVoices.DEFAULT), s))
            assertEquals(AudioCache.key(speech, s), AudioCache.key(speech, s.copy(elevenApiKey = "rotated")))
        } finally { server.stop(0) }
    }

    @Test fun rejectsUrlsAndHonorsCustomCharacterOverrides() {
        assertFalse(ElevenVoices.valid("https://example.com/voice"))
        assertFalse(ElevenVoices.valid("../voice"))
        assertTrue(ElevenVoices.valid("CustomVoice1234567890"))
        val c = Character("a", "A", gender = "female", voice = "CustomVoice1234567890")
        assertEquals(c.voice, ElevenVoices.distinct(c, Settings()))
        assertEquals("groq", Settings(engine = "elevenlabs").textEngine)
    }

    @Test fun tracksUnpricedRequestsByBookWithoutInventingPlanPricing() {
        val dir = Files.createTempDirectory("eleven-spending").toFile()
        try {
            val tracker = SpendingTracker(dir.resolve("history.json"))
            tracker.record(ElevenTtsClient.BASE + "/v1/text-to-speech/" + ElevenVoices.DEFAULT,
                obj("model_id" to str("eleven_v3"), "text" to str("Hello.")), obj(), SpendingBook("a", "A"))
            val row = tracker.state.value.rows.single()
            assertEquals("ElevenLabs", row.provider)
            assertEquals("eleven_v3", row.model)
            assertEquals("a", row.bookId)
            assertEquals(1L, row.unpriced)
        } finally { dir.deleteRecursively() }
    }
}
