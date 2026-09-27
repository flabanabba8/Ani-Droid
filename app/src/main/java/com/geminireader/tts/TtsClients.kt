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
import kotlin.coroutines.resumeWithException

fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
fun str(value: String) = JsonPrimitive(value)
fun arr(vararg values: JsonElement) = JsonArray(values.toList())
class ApiFailure(val code: Int, message: String): IOException(message)
class MissingAudio(val blocked: Boolean, val reason: String): IllegalStateException(
    if (blocked) "Gemini declined this passage ($reason). No audio was generated."
    else "Gemini returned no audio ($reason). Use Retry failed speech; the book text has not been changed."
)
// Retry generation failures separately from HTTP failures; never replay explicit blocks.
internal suspend fun retryGeneration(attempts: Int = 3, generate: suspend () -> Pcm): Pcm {
    require(attempts > 0)
    repeat(attempts) { attempt ->
        currentCoroutineContext().ensureActive()
        try { return generate() } catch (e: MissingAudio) {
            if (e.blocked || attempt == attempts - 1) throw e
            delay(500L shl attempt)
        }
    }
    error("Generation attempts exhausted")
}
class HttpApi(val readTimeoutMs: Long = 120_000, val callTimeoutMs: Long = 150_000, private val spending: com.geminireader.data.SpendingTracker? = null) {
    // A call deadline alone leaves OkHttp's much shorter default socket read timeout active.
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
        })
    }
    /** Binary WAV endpoint. Same cancellable transport; bearer credentials never follow redirects. */
    suspend fun speechAudio(url: String, token: String, body: JsonObject): ByteArray = groqData(url, token, body, false)
    suspend fun groqChat(url: String, token: String, body: JsonObject): JsonObject =
        json.parseToJsonElement(groqData(url, token, body, true).decodeToString()).jsonObject
    private suspend fun groqData(url: String, token: String, body: JsonObject, chat: Boolean): ByteArray = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Enter your Groq API key in Settings" }
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val retryAfter = execute(request).use { response ->
                if (response.isSuccessful) {
                    // Count every accepted response, including malformed audio, before validation.
                    val bytes = response.body.bytes()
                    val usage = if (chat) runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrDefault(obj()) else obj()
                    spending?.record(url, body, usage, currentCoroutineContext()[com.geminireader.data.SpendingBook])
                    return@withContext bytes
                }
                val errorCode = runCatching {
                    json.parseToJsonElement(response.body.string()).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                }.getOrNull()
                val message = if (errorCode == "model_terms_required")
                    "Accept this model's terms in the Groq console playground before generating speech."
                else when (response.code) {
                    400, 422 -> "Groq rejected the request. Check input and model access."
                    401 -> "Groq authentication failed. Check the Groq API key; Vertex credentials are separate."
                    403 -> "Groq model access denied. Check model terms and permissions in the Groq console."
                    429 -> "Groq rate limit reached. Wait before retrying generation."
                    else -> "Groq returned HTTP ${response.code}."
                }
                if (attempt == 3 || (response.code != 429 && response.code !in 500..599)) throw ApiFailure(response.code, message)
                response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 30)?.times(1000) ?: (500L shl attempt)
            }
            delay(retryAfter)
        }
        error("Groq request attempts exhausted")
    }
    suspend fun elevenAudio(url: String, key: String, body: JsonObject): ByteArray = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Enter your ElevenLabs API key in Settings" }
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url).header("xi-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val wait = execute(request).use { response ->
                if (response.isSuccessful) {
                    spending?.record(url, body, obj(), currentCoroutineContext()[com.geminireader.data.SpendingBook])
                    return@withContext response.body.bytes()
                }
                val message = when (response.code) {
                    401, 403 -> "ElevenLabs access denied. Check the API key, speech permissions, credits and voice access."
                    404 -> "ElevenLabs voice not found. Check the voice ID and your account access."
                    429 -> "ElevenLabs rate limit reached. Wait before retrying."
                    else -> "ElevenLabs request failed (HTTP ${response.code}). Check voice, model and account access."
                }
                if (attempt == 3 || (response.code != 429 && response.code !in 500..599)) throw ApiFailure(response.code, message)
                response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 30)?.times(1000) ?: (500L shl attempt)
            }
            delay(wait)
        }
        error("ElevenLabs request attempts exhausted")
    }
    suspend fun fishAudio(url: String, key: String, body: JsonObject): ByteArray = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Enter your Fish API key in Settings" }
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $key").header("model", FishTtsClient.MODEL)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val wait = execute(request).use { response ->
                if (response.isSuccessful) {
                    spending?.record(url, JsonObject(body + ("model" to str(FishTtsClient.MODEL))), obj(), currentCoroutineContext()[com.geminireader.data.SpendingBook])
                    return@withContext response.body.bytes()
                }
                val message = when (response.code) {
                    402 -> "Fish Audio free model is unavailable for this account. No paid model was requested."
                    401, 403 -> "Fish access denied. Check the API key, speech permissions, credits and voice access."
                    404 -> "Fish voice not found. Check the voice ID and your account access."
                    429 -> "Fish rate limit reached. Wait before retrying."
                    else -> "Fish request failed (HTTP ${response.code}). Check voice, model and account access."
                }
                if (attempt == 3 || (response.code != 429 && response.code !in 500..599)) throw ApiFailure(response.code, message)
                response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 30)?.times(1000) ?: (500L shl attempt)
            }
            delay(wait)
        }
        error("Fish request attempts exhausted")
    }
    suspend fun speechifyAudio(url: String, key: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Enter your Speechify API key in Settings" }
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val wait = execute(request).use { response ->
                if (response.isSuccessful) {
                    val result = json.parseToJsonElement(response.body.string()).jsonObject
                    spending?.record(url, body, result, currentCoroutineContext()[com.geminireader.data.SpendingBook])
                    return@withContext result
                }
                val message = when (response.code) {
                    402 -> "Speechify allowance exhausted. Check your plan or wait for your free allowance to renew."
                    401, 403 -> "Speechify access denied. Check the API key, speech permissions, credits and voice access."
                    404 -> "Speechify voice not found. Check the voice ID and your account access."
                    429 -> "Speechify rate limit reached. Wait before retrying."
                    else -> "Speechify request failed (HTTP ${response.code}). Check voice, model and account access."
                }
                if (attempt == 3 || (response.code != 429 && response.code !in 500..599)) throw ApiFailure(response.code, message)
                response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 30)?.times(1000) ?: (500L shl attempt)
            }
            delay(wait)
        }
        error("Speechify request attempts exhausted")
    }
    suspend fun inworldAudio(url: String, key: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Enter your Inworld API key in Settings" }
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url).header("Authorization", "Basic $key")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val wait = execute(request).use { response ->
                if (response.isSuccessful) {
                    val result = json.parseToJsonElement(response.body.string()).jsonObject
                    spending?.record(url, body, result, currentCoroutineContext()[com.geminireader.data.SpendingBook])
                    return@withContext result
                }
                val message = when (response.code) {
                    401, 403 -> "Inworld access denied. Check the API key, speech permissions, credits and voice access."
                    404 -> "Inworld voice not found. Check the voice ID and your account access."
                    429 -> "Inworld rate limit reached. Wait before retrying."
                    else -> "Inworld request failed (HTTP ${response.code}). Check voice, model and account access."
                }
                if (attempt == 3 || (response.code != 429 && response.code !in 500..599)) throw ApiFailure(response.code, message)
                response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 30)?.times(1000) ?: (500L shl attempt)
            }
            delay(wait)
        }
        error("Inworld request attempts exhausted")
    }
    suspend fun deepgramAudio(url: String, key: String, body: JsonObject): ByteArray = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Enter your Deepgram API key in Settings" }
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url).header("Authorization", "Token $key")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val wait = execute(request).use { response ->
                if (response.isSuccessful) {
                    spending?.record(url, body, obj(), currentCoroutineContext()[com.geminireader.data.SpendingBook])
                    return@withContext response.body.bytes()
                }
                val message = when (response.code) {
                    401, 403 -> "Deepgram access denied. Check the API key, speech permissions, credits and voice access."
                    404 -> "Deepgram voice not found. Check the voice ID and your account access."
                    429 -> "Deepgram rate limit reached. Wait before retrying."
                    else -> "Deepgram request failed (HTTP ${response.code}). Check voice, model and account access."
                }
                if (attempt == 3 || (response.code != 429 && response.code !in 500..599)) throw ApiFailure(response.code, message)
                response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 30)?.times(1000) ?: (500L shl attempt)
            }
            delay(wait)
        }
        error("Deepgram request attempts exhausted")
    }
    suspend fun cartesiaAudio(url: String, key: String, body: JsonObject): ByteArray = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Enter your Cartesia API key in Settings" }
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $key").header("Cartesia-Version", CartesiaTtsClient.VERSION)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val wait = execute(request).use { response ->
                if (response.isSuccessful) {
                    spending?.record(url, body, obj(), currentCoroutineContext()[com.geminireader.data.SpendingBook])
                    return@withContext response.body.bytes()
                }
                val message = when (response.code) {
                    401, 403 -> "Cartesia access denied. Check the API key, speech permissions, credits and voice access."
                    404 -> "Cartesia voice not found. Check the voice ID and your account access."
                    429 -> "Cartesia rate limit reached. Wait before retrying."
                    else -> "Cartesia request failed (HTTP ${response.code}). Check voice, model and account access."
                }
                if (attempt == 3 || (response.code != 429 && response.code !in 500..599)) throw ApiFailure(response.code, message)
                response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 30)?.times(1000) ?: (500L shl attempt)
            }
            delay(wait)
        }
        error("Cartesia request attempts exhausted")
    }
    suspend fun request(url: String, key: String, body: JsonObject? = null, token: String = "", project: String = ""): JsonObject = withContext(Dispatchers.IO) {
        var failure: Exception? = null
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            try {
                val request = Request.Builder().url(url)
                if (token.isNotBlank()) request.header("Authorization", "Bearer $token") else if (key.isNotBlank()) request.header("x-goog-api-key", key)
                if (project.isNotBlank()) request.header("x-goog-user-project", project)
                if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
                execute(request.build()).use { response ->
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
                    val result = json.parseToJsonElement(text).jsonObject
                    if (body != null) spending?.record(url, body, result, currentCoroutineContext()[com.geminireader.data.SpendingBook])
                    return@withContext result
                }
            } catch (e: IOException) {
                failure = e
                if (e is ApiFailure && e.code != 429 && e.code < 500) throw e
                if (e is java.io.InterruptedIOException && attempt >= 1) {
                    throw IOException("Service timed out after two attempts. Completed analysis batches are saved; retry to resume.", e)
                }
                if (attempt < 3) delay(500L shl attempt)
            }
        }
        throw failure ?: IOException("Request failed")
    }
}
@kotlinx.serialization.Serializable data class Speech(val text: String, val prompt: String, val voice: String, val pauseMs: Int = 350)
interface TtsEngine { suspend fun synthesize(speech: Speech, settings: Settings): Pcm }
object TtsEngines {
    fun create(settings: Settings, api: HttpApi, local: TtsEngine? = null, android: TtsEngine? = null): TtsEngine = when (settings.engine) {
        "android" -> requireNotNull(android) { "Android speech runtime is unavailable" }
        "fish" -> FishTtsClient(api)
        "speechify" -> SpeechifyTtsClient(api)
        "inworld" -> InworldTtsClient(api)
        "deepgram" -> DeepgramTtsClient(api)
        "cartesia" -> CartesiaTtsClient(api)
        "elevenlabs" -> ElevenTtsClient(api)
        "groq" -> GroqTtsClient(api)
        "kokoro" -> requireNotNull(local) { "On-device Kokoro runtime is unavailable" }
        "vertex" -> VertexTtsClient(api)
        "cloud" -> CloudTtsClient(api)
        "gemini" -> GeminiApiTtsClient(api)
        else -> error("Select a supported speech engine")
    }
}
object VertexEndpoint {
    fun base(s: Settings): String = s.vertexUrl.trimEnd('/').ifBlank { if (s.vertexLocation == "global") "https://aiplatform.googleapis.com" else "https://${s.vertexLocation}-aiplatform.googleapis.com" }
    fun generate(s: Settings, model: String): String {
        require(s.vertexProject.matches(Regex("[a-zA-Z0-9-]+"))) { "Enter your Vertex Google Cloud project ID in Settings" }
        require(s.vertexLocation.matches(Regex("[a-z0-9-]+"))) { "Enter a Vertex region, for example us-central1" }
        require(model.removePrefix("models/").matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid Vertex model name" }
        // Flash-Lite 3.1 supports global/us/eu, not individual regions such as us-central1.
        // Route this model independently so regional speech generation is unaffected.
        val routed = if (model.removePrefix("models/") == "gemini-3.1-flash-lite" && s.vertexUrl.isBlank() &&
            s.vertexLocation !in setOf("global", "us", "eu")) s.copy(vertexLocation = "global") else s
        return "${base(routed)}/v1beta1/projects/${routed.vertexProject}/locations/${routed.vertexLocation}/publishers/google/models/${model.removePrefix("models/")}:generateContent"
    }
    suspend fun request(api: HttpApi, s: Settings, model: String, body: JsonObject): JsonObject {
        return VertexAuth.request(api, s, generate(s, model), body)
    }
}
class VertexTtsClient(private val api: HttpApi): TtsEngine {
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm = retryGeneration {
        ApiAudio.gemini(VertexEndpoint.request(api, settings, settings.model, GeminiApiTtsClient.body(speech)))
    }
}
object ApiAudio {
    fun missing(response: JsonObject): MissingAudio {
        val candidate = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val block = response["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content.orEmpty()
        val finish = candidate?.get("finishReason")?.jsonPrimitive?.content.orEmpty()
        val blocked = block.isNotBlank() && block != "BLOCK_REASON_UNSPECIFIED" || finish in setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY")
        val reason = (block.ifBlank { finish }.ifBlank { "no audio part" }).filter { it.isLetterOrDigit() || it == '_' || it == ' ' }.take(64)
        return MissingAudio(blocked, reason)
    }
    private fun nonempty(pcm: Pcm): Pcm {
        if (pcm.bytes.isEmpty()) throw MissingAudio(false, "empty audio")
        return pcm
    }
    fun cloud(response: JsonObject): Pcm {
        val rejection = missing(response)
        if (rejection.blocked) throw rejection
        val encoded = response["audioContent"]?.jsonPrimitive?.content
        if (encoded.isNullOrBlank()) throw MissingAudio(false, "empty audio")
        return nonempty(Wav.decode(Base64.getDecoder().decode(encoded)))
    }
    fun gemini(response: JsonObject): Pcm {
        val rejection = missing(response)
        if (rejection.blocked) throw rejection
        val parts = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
        val audio = parts?.firstNotNullOfOrNull { it.jsonObject["inlineData"]?.jsonObject }
            ?: response["output_audio"]?.jsonObject
            ?: response["outputs"]?.jsonArray?.firstNotNullOfOrNull { element -> element.jsonObject.takeIf { it["type"]?.jsonPrimitive?.content == "audio" } }
            ?: throw missing(response)
        val encoded = audio["data"]?.jsonPrimitive?.content
        if (encoded.isNullOrBlank()) throw MissingAudio(false, "empty audio")
        val data = Base64.getDecoder().decode(encoded)
        if (data.size >= 4 && String(data, 0, 4) == "RIFF") return nonempty(Wav.decode(data))
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
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm = retryGeneration {
        ApiAudio.cloud(api.request("${settings.cloudUrl.trimEnd('/')}/v1/text:synthesize", settings.apiKey, body(speech, settings), settings.oauthToken, settings.project))
    }
}
class GeminiApiTtsClient(private val api: HttpApi): TtsEngine {
    companion object {
        fun transcript(speech: Speech) = "# AUDIO PROFILE\nNarrator: ${speech.voice}\n## THE SCENE\nAn audiobook.\n### DIRECTOR'S NOTES\n${speech.prompt}\nSpeak only the transcript.\n#### TRANSCRIPT\n${speech.text}"
        fun body(speech: Speech) = obj("contents" to arr(obj("role" to str("user"), "parts" to arr(obj("text" to str(transcript(speech)))))), "generationConfig" to obj("responseModalities" to arr(str("AUDIO")), "speechConfig" to obj("voiceConfig" to obj("prebuiltVoiceConfig" to obj("voiceName" to str(speech.voice))))))
        fun interactions(speech: Speech, model: String) = obj("model" to str(model), "input" to str(transcript(speech)), "response_format" to obj("type" to str("audio")), "generation_config" to obj("speech_config" to arr(obj("voice" to str(speech.voice)))))
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
        val base = settings.geminiUrl.trimEnd('/')
        val key = settings.geminiKey.ifBlank { settings.apiKey }

        try {
            return retryGeneration(attempts = 2) {
                ApiAudio.gemini(api.request("$base/v1beta/models/${settings.model.removePrefix("models/")}:generateContent", key, body(speech)))
            }
        } catch (e: CancellationException) { throw e } catch (e: ApiFailure) {
            if (e.code !in listOf(400, 404, 405, 501) && e.code < 500) throw e
        } catch (e: MissingAudio) {
            if (e.blocked) throw e
        }

        return retryGeneration {
            ApiAudio.gemini(api.request("$base/v1beta/interactions", key, interactions(speech, settings.model.removePrefix("models/"))))
        }
    }
}
