package com.geminireader.tts

import com.geminireader.analysis.DeepgramVoices
import com.geminireader.data.Settings
import okhttp3.HttpUrl.Companion.toHttpUrl

class DeepgramTtsClient(private val api: HttpApi, private val base: String = BASE) : TtsEngine {
    companion object {
        const val BASE = "https://api.deepgram.com"
        // Deepgram's complete HTTP WAV uses paired 0x7fff0000 size placeholders.
        // Normalize only this exact 44-byte PCM header; other malformed files stay rejected.
        fun decode(bytes: ByteArray): Pcm {
            if (bytes.size >= 44) {
                val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                if (String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 8) == "WAVEfmt " &&
                    header.getInt(16) == 16 && String(bytes, 36, 4) == "data" &&
                    header.getInt(4) == 0x7fff0024 && header.getInt(40) == 0x7fff0000) {
                    val normalized = bytes.copyOf()
                    java.nio.ByteBuffer.wrap(normalized).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                        putInt(4, bytes.size - 8); putInt(40, bytes.size - 44)
                    }
                    return Wav.decodeStreamed(normalized)
                }
            }
            return Wav.decodeStreamed(bytes)
        }
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
        val voice = speech.voice.ifBlank { settings.deepgramVoice }
        require(DeepgramVoices.valid(voice)) { "Enter an English Deepgram Flux voice model" }
        require(speech.text.isNotBlank() && speech.text.length <= 2000) { "Deepgram passages must contain 1–2000 characters" }
        val url = "$base/v2/speak".toHttpUrl().newBuilder()
            .addQueryParameter("model", voice).addQueryParameter("encoding", "linear16")
            .addQueryParameter("container", "wav").addQueryParameter("sample_rate", "24000").build()
        val pcm = decode(api.deepgramAudio(url.toString(), settings.deepgramApiKey, obj("text" to str(speech.text))))
        require(pcm.bytes.isNotEmpty()) { "Deepgram returned empty audio" }
        return pcm
    }
}
