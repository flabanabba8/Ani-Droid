package com.geminireader.tts

import com.geminireader.analysis.InworldVoices
import com.geminireader.data.Settings
import kotlinx.serialization.json.*
import java.util.Base64

class InworldTtsClient(private val api: HttpApi, private val base: String = BASE) : TtsEngine {
    companion object {
        const val BASE = "https://api.inworld.ai"
        const val MODEL = "inworld-tts-2"
        fun body(speech: Speech, settings: Settings) = obj(
            "text" to str(speech.text), "voice_id" to str(speech.voice.ifBlank { settings.inworldVoice }),
            "model_id" to str(MODEL), "delivery_mode" to str("BALANCED"),
            "audio_config" to obj("audio_encoding" to str("LINEAR16"), "sample_rate_hertz" to JsonPrimitive(48000)))
        fun decode(response: JsonObject): Pcm {
            val audio = (response["audioContent"] ?: response["audio_content"])?.jsonPrimitive?.content.orEmpty()
            require(audio.isNotBlank()) { "Inworld returned empty audio" }
            val bytes = try { Base64.getDecoder().decode(audio) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Inworld returned invalid audio encoding")
            }
            return Wav.decodeStreamed(bytes)
        }
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
        require(InworldVoices.valid(speech.voice.ifBlank { settings.inworldVoice })) { "Enter a valid Inworld voice ID" }
        require(speech.text.isNotBlank() && speech.text.length <= 2000) { "Inworld passages must contain 1–2000 characters" }
        return decode(api.inworldAudio("$base/tts/v1/voice", settings.inworldApiKey, body(speech, settings)))
    }
}
