package com.geminireader.tts

import com.geminireader.analysis.FishVoices
import com.geminireader.data.Settings
import kotlinx.serialization.json.JsonPrimitive

class FishTtsClient(private val api: HttpApi, private val base: String = BASE) : TtsEngine {
    companion object {
        const val BASE = "https://api.fish.audio"
        const val MODEL = "s2.1-pro-free"
        // Fish Audio's complete HTTP WAV uses paired 0xffffff00 size placeholders.
        // Normalize only this exact 44-byte PCM header; other malformed files stay rejected.
        fun decode(bytes: ByteArray): Pcm {
            if (bytes.size >= 44) {
                val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                if (String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 8) == "WAVEfmt " &&
                    header.getInt(16) == 16 && String(bytes, 36, 4) == "data" &&
                    header.getInt(4) == -220 && header.getInt(40) == -256) {
                    val normalized = bytes.copyOf()
                    java.nio.ByteBuffer.wrap(normalized).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                        putInt(4, bytes.size - 8); putInt(40, bytes.size - 44)
                    }
                    return Wav.decodeStreamed(normalized)
                }
            }
            return Wav.decodeStreamed(bytes)
        }
        fun body(speech: Speech, settings: Settings) = obj(
            "text" to str(speech.text), "reference_id" to str(speech.voice.ifBlank { settings.fishVoice }),
            "format" to str("wav"), "sample_rate" to JsonPrimitive(44100), "latency" to str("normal"))
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
        require(FishVoices.valid(speech.voice.ifBlank { settings.fishVoice })) { "Enter a valid Fish Audio voice ID" }
        require(speech.text.isNotBlank() && speech.text.length <= 2000) { "Fish Audio passages must contain 1–2000 characters" }
        return decode(api.fishAudio("$base/v1/tts", settings.fishApiKey, body(speech, settings)))
    }
}
