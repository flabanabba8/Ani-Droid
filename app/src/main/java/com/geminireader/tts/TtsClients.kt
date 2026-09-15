package com.geminireader.tts

import com.geminireader.data.Settings
import com.geminireader.data.json
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
fun str(value: String) = JsonPrimitive(value)
fun arr(vararg values: JsonElement) = JsonArray(values.toList())
class ApiFailure(val code: Int, message: String): IOException(message)
class HttpApi {
    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
    suspend fun request(url: String, key: String, body: JsonObject? = null, token: String = "", project: String = ""): JsonObject = withContext(Dispatchers.IO) {
        var failure: Exception? = null
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            try {
                val request = Request.Builder().url(url)
                if (token.isNotBlank()) request.header("Authorization", "Bearer $token") else if (key.isNotBlank()) request.header("x-goog-api-key", key)
                if (project.isNotBlank()) request.header("x-goog-user-project", project)
                if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
                client.newCall(request.build()).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        val message = when (response.code) {
                            400 -> "Request rejected: check model, language and voice."
                            401 -> "Authentication failed: check the API key or renew the OAuth token."
                            403 -> "Access denied: enable the API and check project permissions. Cloud TTS may require OAuth."
                            404 -> "Model or endpoint unavailable. Check the model name."
                            429 -> "Quota or rate limit reached. Try again later."
                            else -> "Speech service returned HTTP ${response.code}."
                        }
                        throw ApiFailure(response.code, message)
                    }
                    return@withContext json.parseToJsonElement(text).jsonObject
                }
            } catch (e: IOException) {
                failure = e
                if (e is ApiFailure && e.code != 429 && e.code < 500) throw e
                if (attempt < 3) delay(500L shl attempt)
            }
        }
        throw failure ?: IOException("Request failed")
    }
}
data class Speech(val text: String, val prompt: String, val voice: String, val pauseMs: Int = 350)
interface TtsEngine { suspend fun synthesize(speech: Speech, settings: Settings): Pcm }
object ApiAudio {
    fun cloud(response: JsonObject): Pcm = Wav.decode(Base64.getDecoder().decode(response["audioContent"]?.jsonPrimitive?.content ?: error("Cloud returned no audio")))
    fun gemini(response: JsonObject): Pcm {
        val parts = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
        val audio = parts?.firstNotNullOfOrNull { it.jsonObject["inlineData"]?.jsonObject }
            ?: response["output_audio"]?.jsonObject
            ?: response["outputs"]?.jsonArray?.firstNotNullOfOrNull { element -> element.jsonObject.takeIf { it["type"]?.jsonPrimitive?.content == "audio" } }
            ?: error("Gemini returned text instead of audio")
        val data = Base64.getDecoder().decode(audio["data"]?.jsonPrimitive?.content ?: error("Missing audio data"))
        if (data.size >= 4 && String(data, 0, 4) == "RIFF") return Wav.decode(data)
        val mime = (audio["mimeType"] ?: audio["mime_type"])?.jsonPrimitive?.content.orEmpty()
        val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toInt() ?: audio["sample_rate"]?.jsonPrimitive?.intOrNull ?: 24000
        require(data.isNotEmpty() && data.size % 2 == 0 && rate in 8000..192000) { "Invalid PCM audio" }
        return Pcm(data, rate)
    }
}
class CloudTtsClient(private val api: HttpApi): TtsEngine {
    companion object {
        fun body(speech: Speech, s: Settings): JsonObject {
            require(speech.text.toByteArray().size <= 4000 && speech.prompt.toByteArray().size <= 4000) { "TTS text and prompt must each fit within 4000 UTF-8 bytes" }
            return obj("input" to obj("text" to str(speech.text), "prompt" to str(speech.prompt)), "voice" to obj("languageCode" to str(s.language), "name" to str(speech.voice), "modelName" to str(s.model)), "audioConfig" to obj("audioEncoding" to str("LINEAR16"), "sampleRateHertz" to JsonPrimitive(24000)))
        }
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm = ApiAudio.cloud(api.request("${settings.cloudUrl.trimEnd('/')}/v1/text:synthesize", settings.apiKey, body(speech, settings), settings.oauthToken, settings.project))
}
class GeminiApiTtsClient(private val api: HttpApi): TtsEngine {
    companion object {
        fun transcript(speech: Speech) = "# AUDIO PROFILE\nNarrator: ${speech.voice}\n## THE SCENE\nAn audiobook.\n### DIRECTOR'S NOTES\n${speech.prompt}\nSpeak only the transcript.\n#### TRANSCRIPT\n${speech.text}"
        fun body(speech: Speech) = obj("contents" to arr(obj("parts" to arr(obj("text" to str(transcript(speech)))))), "generationConfig" to obj("responseModalities" to arr(str("AUDIO")), "speechConfig" to obj("voiceConfig" to obj("prebuiltVoiceConfig" to obj("voiceName" to str(speech.voice))))))
        fun interactions(speech: Speech, model: String) = obj("model" to str(model), "input" to str(transcript(speech)), "response_format" to obj("type" to str("audio")), "generation_config" to obj("speech_config" to arr(obj("voice" to str(speech.voice)))))
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
        val base = settings.geminiUrl.trimEnd('/')
        val key = settings.geminiKey.ifBlank { settings.apiKey }
        try {
            repeat(2) { attempt ->
                val response = api.request("$base/v1beta/models/${settings.model.removePrefix("models/")}:generateContent", key, body(speech))
                try { return ApiAudio.gemini(response) } catch (e: IllegalStateException) { if (attempt == 1) throw e; delay(500) }
            }
        } catch (e: CancellationException) { throw e } catch (e: ApiFailure) {
            if (e.code !in listOf(400, 404, 405, 501) && e.code < 500) throw e
        } catch (_: IllegalStateException) { /* Text-only response: try Interactions. */ }
        return ApiAudio.gemini(api.request("$base/v1beta/interactions", key, interactions(speech, settings.model.removePrefix("models/"))))
    }
}
