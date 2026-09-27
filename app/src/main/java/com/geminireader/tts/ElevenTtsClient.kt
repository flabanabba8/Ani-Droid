package com.geminireader.tts

import com.geminireader.analysis.ElevenVoices
import com.geminireader.data.Settings

class ElevenTtsClient(private val api: HttpApi, private val base: String = BASE) : TtsEngine {
    companion object { const val BASE = "https://api.elevenlabs.io" }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
        val voice = speech.voice.ifBlank { settings.elevenVoice }
        require(ElevenVoices.valid(voice)) { "Enter a valid ElevenLabs voice ID" }
        require(settings.elevenModel in ElevenVoices.models) { "Select an ElevenLabs speech model" }
        require(speech.text.isNotBlank() && speech.text.length <= 3000) { "ElevenLabs passages must contain 1–3000 characters" }
        val bytes = api.elevenAudio("$base/v1/text-to-speech/$voice?output_format=pcm_24000", settings.elevenApiKey,
            obj("text" to str(speech.text), "model_id" to str(settings.elevenModel)))
        require(bytes.isNotEmpty() && bytes.size % 2 == 0) { "ElevenLabs returned invalid PCM audio" }
        return Pcm(bytes, 24000)
    }
}
