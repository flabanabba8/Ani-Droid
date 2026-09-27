package com.geminireader.tts

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamedWavTest {
    private val samples = byteArrayOf(10, 2, 30, 4)
    private fun fixture(): ByteArray {
        val wav = Wav.encode(Pcm(samples, 44100))
        // Cartesia's published WAV has LIST/INFO metadata before an unknown-size data chunk.
        val list = ByteBuffer.allocate(34).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("LIST".toByteArray()); putInt(26); put(ByteArray(26))
        }.array()
        return (wav.copyOfRange(0, 36) + list + wav.copyOfRange(36, wav.size)).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(4, -1); putInt(74, -1) }
        }
    }
    @Test fun reproducesStrictFailureAndPreservesAudioWithStreamedHeaders() {
        val wav = fixture()
        try { Wav.decode(wav); fail("Old decoder must reproduce failure") }
        catch (e: IllegalArgumentException) { assertEquals("Truncated WAV chunk", e.message) }
        val decoded = Wav.decodeStreamed(wav)
        assertEquals(44100, decoded.rate)
        assertArrayEquals(samples, decoded.bytes)
        assertEquals(-1, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(74))
        assertArrayEquals(samples, Wav.decode(Wav.encode(decoded)).bytes)
    }
    @Test fun rejectsRealTruncationOddSamplesAndMissingAudio() {
        val invalid = listOf(
            Wav.encode(Pcm(samples)).dropLast(2).toByteArray(),
            fixture().dropLast(1).toByteArray(),
            fixture().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1) },
            Wav.encode(Pcm(ByteArray(0)))
        )
        invalid.forEach {
            try { Wav.decodeStreamed(it); fail("Invalid audio must fail") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun acceptsOrdinaryWavAndOddPaddedMetadata() {
        assertArrayEquals(samples, Wav.decodeStreamed(Wav.encode(Pcm(samples))).bytes)
        val wav = Wav.encode(Pcm(samples))
        val oddChunk = byteArrayOf(74, 85, 78, 75, 1, 0, 0, 0, 65, 0)
        val streamed = wav.copyOfRange(0, 36) + oddChunk + wav.copyOfRange(36, wav.size)
        ByteBuffer.wrap(streamed).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(4, -1); putInt(50, -1) }
        assertArrayEquals(samples, Wav.decodeStreamed(streamed).bytes)
    }
}
