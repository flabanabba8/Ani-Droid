package com.geminireader.tts

import com.geminireader.analysis.CartesiaVoices
import com.geminireader.data.Settings
import kotlinx.serialization.json.JsonPrimitive

class CartesiaTtsClient(private val api: HttpApi, private val base: String = BASE) : TtsEngine {
    companion object {
        const val BASE = "https://api.cartesia.ai"
        const val VERSION = "2026-08-14"
        fun body(speech: Speech, settings: Settings) = obj(
            "model_id" to str(settings.cartesiaModel), "transcript" to str(speech.text),
            "voice" to str(speech.voice.ifBlank { settings.cartesiaVoice }), "language" to str("en"),
            "output_format" to obj("container" to str("wav"), "encoding" to str("pcm_s16le"), "sample_rate" to JsonPrimitive(44100)))
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
        require(CartesiaVoices.valid(speech.voice.ifBlank { settings.cartesiaVoice })) { "Enter a valid Cartesia voice ID" }
        require(settings.cartesiaModel in CartesiaVoices.models) { "Select a Cartesia model" }
        require(speech.text.isNotBlank() && speech.text.length <= 4000) { "Cartesia passages must contain 1–4000 characters" }
        val pcm = Wav.decodeStreamed(api.cartesiaAudio("$base/tts/bytes", settings.cartesiaApiKey, body(speech, settings)))
        require(pcm.bytes.isNotEmpty()) { "Cartesia returned empty audio" }
        return pcm
    }
}
