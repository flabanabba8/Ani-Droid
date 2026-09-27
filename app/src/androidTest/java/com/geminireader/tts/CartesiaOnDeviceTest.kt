package com.geminireader.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geminireader.analysis.*
import com.geminireader.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class CartesiaOnDeviceTest {
    @Test fun publishedCartesiaResponseWhenSupplied() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "cartesia-reference.wav")
        org.junit.Assume.assumeTrue("Optional published provider fixture", file.exists())
        val bytes = file.readBytes()
        try { Wav.decode(bytes); fail("Expected reproduction of reported error") }
        catch (e: IllegalArgumentException) { assertEquals("Truncated WAV chunk", e.message) }
        val pcm = Wav.decodeStreamed(bytes)
        assertEquals(44100, pcm.rate)
        assertEquals(bytes.size - 78, pcm.bytes.size)
        assertTrue(pcm.bytes.any { it != 0.toByte() })
        assertArrayEquals(pcm.bytes, Wav.decode(Wav.encode(pcm)).bytes)
    }
    @Test fun mockSpeechThroughAndroidTransportAndCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val wav = Wav.encode(Pcm(ByteArray(88200) { if (it % 2 == 0) 100 else 10 }, 44100))
        java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4, -1); putInt(40, -1)
        }
        var requestLine = ""
        val worker = thread {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                requestLine = reader.readLine()
                var length = 0
                while (true) {
                    val line = reader.readLine()
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { reader.read() }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${wav.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(wav); flush()
                }
            }
        }
        val folder = java.io.File(context.cacheDir, "cartesia-mock-test")
        try {
            val settings = Settings(engine = "cartesia", cartesiaApiKey = "mock", characterMode = "distinct")
            val custom = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            val c = Character("a", "Alice", voice = custom)
            val text = "Hello."
            val speech = VoiceDirector.direct(com.geminireader.text.Segment(0, 0, text.length, text), settings, c, "", true)
            assertEquals(custom, speech.voice)
            val client = CartesiaTtsClient(HttpApi(), "http://127.0.0.1:${server.localPort}")
            val cache = AudioCache(folder)
            val file = cache.get(speech, settings, client)
            assertEquals(44100, Wav.decode(file.readBytes()).rate)
            worker.join(5000)
            assertTrue(requestLine.startsWith("POST /tts/bytes "))
            server.close()
            assertEquals(file, cache.get(speech, settings, client))
        } finally { server.close(); folder.deleteRecursively() }
    }
}
