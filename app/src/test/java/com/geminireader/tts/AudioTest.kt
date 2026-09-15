package com.geminireader.tts

import com.geminireader.data.Settings
import com.geminireader.text.Segmenter
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AudioTest {
    @Test fun wavRoundtripAndPadding() {
        val pcm = Pcm(ByteArray(480) { if (it % 2 == 0) 100 else 1 })
        val decoded = Wav.decode(Wav.encode(pcm))
        assertArrayEquals(pcm.bytes, decoded.bytes)
        assertEquals(24000, decoded.rate)
        assertEquals(480 + 3840, Wav.trimAndPad(pcm, 80).bytes.size)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedWav() { Wav.decode(Wav.encode(Pcm(ByteArray(400))).copyOf(60)) }
    @Test fun parsesBothGeminiShapes() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val inline = obj("candidates" to arr(obj("content" to obj("parts" to arr(obj("inlineData" to obj("data" to str(encoded), "mimeType" to str("audio/L16;rate=22050"))))))))
        assertEquals(22050, ApiAudio.gemini(inline).rate)
        assertArrayEquals(byteArrayOf(1,2,3,4), ApiAudio.gemini(obj("output_audio" to obj("data" to str(encoded)))).bytes)
    }
    @Test fun cloudBodySeparatesInstructionsFromSpeech() {
        val body = CloudTtsClient.body(Speech("Hello", "Warmly", "Charon"), Settings())
        assertTrue(body.toString().contains("\"prompt\":\"Warmly\""))
        assertTrue(body.toString().contains("\"text\":\"Hello\""))
    }
    @Test fun cacheSeparatesMockVoicePromptAndPauses() {
        val speech = Speech("Hello", "Warmly", "Charon")
        val key = AudioCache.key(speech, Settings())
        assertNotEquals(key, AudioCache.key(speech.copy(prompt = "Sadly"), Settings()))
        assertNotEquals(key, AudioCache.key(speech.copy(pauseMs = 80), Settings()))
        assertNotEquals(key, AudioCache.key(speech, Settings(vertexUrl = "http://10.0.2.2:8765")))
    }
    @Test fun vertexRoutesToCreditsProjectAndRegion() {
        val s = Settings(vertexProject = "my-credits", vertexLocation = "us-central1")
        assertEquals("https://us-central1-aiplatform.googleapis.com/v1beta1/projects/my-credits/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent", VertexEndpoint.generate(s, "gemini-2.5-flash"))
        assertEquals("https://aiplatform.googleapis.com", VertexEndpoint.base(s.copy(vertexLocation = "global")))
    }
    @Test fun longUnicodeTextFitsCloudLimitsWithoutLostText() {
        val text = "文".repeat(3000) + "😄".repeat(1000)
        val chunks = Segmenter.chunks(0, text)
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.text.toByteArray().size <= 4000 })
    }
}
