package com.geminireader.tts

import com.geminireader.analysis.GroqVoices
import com.geminireader.data.Settings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

class GroqTtsClient(private val api: HttpApi, private val endpoint: String = ENDPOINT) : TtsEngine {
    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/audio/speech"
        const val MODEL = "canopylabs/orpheus-v1-english"
        /** Preserve every character; prefer word boundaries and never split UTF-16 surrogate pairs. */
        fun chunks(text: String): List<String> {
            require(text.isNotBlank()) { "Groq speech needs text" }
            val result = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(start + 200, text.length)
                if (end < text.length) {
                    if (text[end - 1].isHighSurrogate()) end--
                    val space = (end - 1 downTo start + 1).firstOrNull { text[it].isWhitespace() }
                    if (space != null) end = space + 1
                }
                result += text.substring(start, end)
                start = end
            }
            return result
        }
        /** Normalize streaming length markers before the strict PCM decoder. */
        fun decode(bytes: ByteArray): Pcm = Wav.decodeStreamed(bytes)
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
        require(settings.groqModel in GroqVoices.models) { "Select a Groq model" }
        val voices = GroqVoices.namesFor(settings.groqModel)
        val voice = speech.voice.takeIf { it in voices } ?: settings.groqVoice
        require(voice in voices) { "Select a Groq voice" }
        var rate: Int? = null
        val output = ByteArrayOutputStream()
        for (text in chunks(speech.text).filter { it.isNotBlank() }) {
            currentCoroutineContext().ensureActive()
            val body = obj("model" to str(settings.groqModel), "voice" to str(voice), "input" to str(text), "response_format" to str("wav"))
            val pcm = decode(api.speechAudio(endpoint, settings.groqApiKey, body))
            require(rate == null || rate == pcm.rate) { "Groq returned inconsistent sample rates" }
            rate = pcm.rate
            output.write(pcm.bytes)
        }
        return Pcm(output.toByteArray(), requireNotNull(rate))
    }
}
