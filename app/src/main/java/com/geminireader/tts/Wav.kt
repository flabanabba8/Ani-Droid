package com.geminireader.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class Pcm(val bytes: ByteArray, val rate: Int = 24000)
object Wav {
    fun encode(pcm: Pcm): ByteArray {
        require(pcm.rate in 8000..192000 && pcm.bytes.size % 2 == 0)
        return ByteBuffer.allocate(44 + pcm.bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.bytes.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(pcm.rate); putInt(pcm.rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.bytes.size); put(pcm.bytes)
        }.array()
    }
    fun decode(wav: ByteArray): Pcm {
        require(wav.size >= 44 && String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE") { "Invalid WAV audio" }
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 12; var rate = 0; var audio: ByteArray? = null
        while (cursor + 8 <= wav.size) {
            val tag = String(wav, cursor, 4)
            val size = buffer.getInt(cursor + 4)
            require(size >= 0 && size.toLong() + cursor + 8 <= wav.size) { "Truncated WAV chunk" }
            if (tag == "fmt ") {
                require(size >= 16 && buffer.getShort(cursor + 8).toInt() == 1 && buffer.getShort(cursor + 10).toInt() == 1 && buffer.getShort(cursor + 22).toInt() == 16) { "Audio must be 16-bit mono PCM" }
                rate = buffer.getInt(cursor + 12)
            }
            if (tag == "data") audio = wav.copyOfRange(cursor + 8, cursor + 8 + size)
            cursor += 8 + size + (size % 2)
        }
        require(rate in 8000..192000 && audio != null && audio.size % 2 == 0) { "WAV has no valid PCM data" }
        return Pcm(audio, rate)
    }
    fun trimAndPad(pcm: Pcm, pauseMs: Int): Pcm {
        require(pauseMs in 0..5000)
        val buffer = ByteBuffer.wrap(pcm.bytes).order(ByteOrder.LITTLE_ENDIAN)
        var first = 0; var last = pcm.bytes.size / 2
        while (first < last && abs(buffer.getShort(first * 2).toInt()) < 120) first++
        while (last > first && abs(buffer.getShort((last - 1) * 2).toInt()) < 120) last--
        // Keep 10 ms on either edge to avoid clipping breaths and consonants.
        first = (first - pcm.rate / 100).coerceAtLeast(0)
        last = (last + pcm.rate / 100).coerceAtMost(pcm.bytes.size / 2)
        return Pcm(pcm.bytes.copyOfRange(first * 2, last * 2) + ByteArray(pcm.rate * pauseMs / 1000 * 2), pcm.rate)
    }
}
