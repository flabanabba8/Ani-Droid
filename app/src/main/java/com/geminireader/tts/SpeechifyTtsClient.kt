package com.geminireader.tts

import com.geminireader.analysis.SpeechifyVoices
import com.geminireader.data.Settings
import kotlinx.serialization.json.*
import java.util.Base64

class SpeechifyTtsClient(private val api: HttpApi, private val base: String = BASE) : TtsEngine {
    companion object {
        const val BASE = "https://api.speechify.ai"
        const val MODEL = "simba-3.2"
        fun body(speech: Speech, settings: Settings) = obj(
            "input" to str(speech.text), "voice_id" to str(speech.voice.ifBlank { settings.speechifyVoice }),
            "model" to str(MODEL), "audio_format" to str("wav"))
        fun decode(response: JsonObject): Pcm {
            val audio = response["audio_data"]?.jsonPrimitive?.content.orEmpty()
            require(audio.isNotBlank()) { "Speechify returned empty audio" }
            val bytes = try { Base64.getDecoder().decode(audio) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Speechify returned invalid audio encoding")
            }
            return Wav.decodeStreamed(bytes)
        }
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
        require(SpeechifyVoices.valid(speech.voice.ifBlank { settings.speechifyVoice })) { "Enter a valid Speechify voice ID" }
        require(speech.text.isNotBlank() && speech.text.length <= 2000) { "Speechify passages must contain 1–2000 characters" }
        return decode(api.speechifyAudio("$base/v1/audio/speech", settings.speechifyApiKey, body(speech, settings)))
    }
}
