package com.geminireader.data

import com.geminireader.tts.ApiAudio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class SpendingBook(val id: String, val title: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpendingBook>
}

@Serializable data class SpendingRow(
    val month: String, val provider: String, val model: String,
    val bookId: String = "", val bookTitle: String = "Previews / other",
    val usd: Double = 0.0, val requests: Long = 0, val unpriced: Long = 0, val approximated: Long = 0
)
@Serializable data class SpendingHistory(val since: String = LocalDate.now().toString(), val rows: List<SpendingRow> = emptyList())

/** Standard paid-tier USD rates checked 2026-09-15; see wiki/spending.md. */
object SpendingRates {
    fun price(model: String, input: Long, output: Long, cached: Long = 0): Double? {
        val rates = when (model) {
            "gemini-3.1-flash-tts-preview" -> 1.0 to 20.0
            "gemini-2.5-flash-tts", "gemini-2.5-flash-preview-tts" -> 0.5 to 10.0
            "gemini-2.5-pro-tts", "gemini-2.5-pro-preview-tts" -> 1.0 to 20.0
            "gemini-2.5-flash" -> 0.3 to 2.5
            "gemini-2.5-flash-lite" -> 0.1 to 0.4
            "gemini-2.5-pro" -> if (input > 200_000) 2.5 to 15.0 else 1.25 to 10.0
            else -> return null
        }
        val cache = cached.coerceIn(0, input)
        return ((input - cache) * rates.first + cache * rates.first * 0.1 + output * rates.second) / 1_000_000
    }
}

class SpendingTracker(private val file: File) {
    private val problem = MutableStateFlow("")
    val error = problem.asStateFlow()
    private val history = MutableStateFlow(try {
        if (file.exists()) json.decodeFromString<SpendingHistory>(file.readText()) else SpendingHistory()
    } catch (_: Exception) {
        problem.value = "Spending history could not be read. Tracking is unavailable."
        SpendingHistory()
    })
    val state = history.asStateFlow()

    // Runs once per received generation response, before audio validation/retries. No text or credentials are stored.
    @Synchronized fun record(url: String, body: JsonObject, response: JsonObject, book: SpendingBook? = null) {
        if (problem.value.isNotEmpty()) return
        try {
            val endpoint = url.toHttpUrl()
            val provider = when {
                endpoint.host == "aiplatform.googleapis.com" || endpoint.host.endsWith("-aiplatform.googleapis.com") -> "Vertex AI"
                endpoint.host == "generativelanguage.googleapis.com" -> "Gemini API"
                endpoint.host == "texttospeech.googleapis.com" -> "Cloud TTS"
                endpoint.host == "api.fish.audio" -> "Fish Audio"
                endpoint.host == "api.speechify.ai" -> "Speechify"
                endpoint.host == "api.inworld.ai" -> "Inworld"
                endpoint.host == "api.deepgram.com" -> "Deepgram"
                endpoint.host == "api.cartesia.ai" -> "Cartesia"
                endpoint.host == "api.elevenlabs.io" -> "ElevenLabs"
                endpoint.host == "api.groq.com" -> "Groq"
                else -> return // Local mocks and custom services have no known Google charges.
            }
            val path = endpoint.encodedPath
            if (provider == "Groq" && path !in listOf("/openai/v1/audio/speech", "/openai/v1/chat/completions")) return
            if (provider == "Fish Audio" && path != "/v1/tts") return
            if (provider == "Speechify" && path != "/v1/audio/speech") return
            if (provider == "Inworld" && path != "/tts/v1/voice") return
            if (provider == "Deepgram" && path != "/v2/speak") return
            if (provider == "Cartesia" && path != "/tts/bytes") return
            if (provider == "ElevenLabs" && !path.startsWith("/v1/text-to-speech/")) return
            if (provider !in listOf("Groq", "ElevenLabs", "Cartesia", "Deepgram", "Inworld", "Speechify", "Fish Audio") && !path.endsWith(":generateContent") && !path.endsWith("/interactions") && !path.endsWith("text:synthesize")) return
            val model = if (provider == "Deepgram") endpoint.queryParameter("model") ?: "Unknown model" else if (path.endsWith(":generateContent")) path.substringAfterLast('/').substringBefore(':')
                else body["model_id"]?.jsonPrimitive?.content ?: body["model"]?.jsonPrimitive?.content
                    ?: body["voice"]?.jsonObject?.get("modelName")?.jsonPrimitive?.content ?: "Unknown model"
            val usage = (response["usageMetadata"] ?: response["usage"]) as? JsonObject
            fun tokens(vararg keys: String): Long? = keys.firstNotNullOfOrNull { usage?.get(it)?.jsonPrimitive?.longOrNull }?.coerceAtLeast(0)
            var input = tokens("promptTokenCount", "total_input_tokens", "prompt_tokens")
            var output = tokens("candidatesTokenCount", "total_output_tokens", "completion_tokens")
            var approximate = false
            if (model.contains("tts") && (input == null || output == null)) {
                val pcm = runCatching { if (provider == "Cloud TTS") ApiAudio.cloud(response) else ApiAudio.gemini(response) }.getOrNull()
                if (pcm != null) {
                    if (input == null) {
                        // Only text-bearing request fields, not base64, schema or configuration JSON.
                        fun textLength(value: JsonElement): Int = when (value) {
                            is JsonObject -> value.entries.sumOf { (key, v) -> if (key in setOf("text", "prompt", "input") && v is JsonPrimitive) v.content.length else textLength(v) }
                            is JsonArray -> value.sumOf { textLength(it) }
                            else -> 0
                        }
                        input = ceil(textLength(body) / 4.0).toLong()
                    }
                    if (output == null) output = ceil(pcm.bytes.size / (2.0 * pcm.rate) * 25).toLong()
                    approximate = true
                }
            }
            val cost = if (provider == "Fish Audio") {
                if (model == "s2.1-pro-free" && !LocalDate.now().isAfter(LocalDate.of(2026, 11, 30))) 0.0 else null
            } else if (provider == "Speechify") {
                val reported = response["billable_characters_count"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
                // Plan-dependent: value usage at the Starter overage reference rate.
                approximate = true
                if (model == "simba-3.2" && reported != null) reported * 10.0 / 1_000_000 else null
            } else if (provider == "Inworld") {
                val reported = tokens("processedCharactersCount", "processed_characters_count")
                val text = body["text"]?.jsonPrimitive?.content.orEmpty()
                approximate = reported == null
                if (model == "inworld-tts-2") (reported ?: text.codePointCount(0, text.length).toLong()) * 25.0 / 1_000_000 else null
            } else if (provider == "Deepgram") {
                val text = body["text"]?.jsonPrimitive?.content.orEmpty()
                approximate = true
                if (com.geminireader.analysis.DeepgramVoices.valid(model)) text.codePointCount(0, text.length) * 45.0 / 1_000_000 else null
            } else if (provider == "Groq") {
                if (path.endsWith("/chat/completions")) {
                    val rates = when (model) {
                        "openai/gpt-oss-20b" -> Triple(0.075, 0.30, 0.037)
                        "openai/gpt-oss-120b" -> Triple(0.15, 0.60, 0.075)
                        else -> null
                    }
                    if (rates != null && input != null && output != null) {
                        val cached = ((usage?.get("prompt_tokens_details") as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0L).coerceIn(0, input)
                        ((input - cached) * rates.first + cached * rates.third + output * rates.second) / 1_000_000
                    } else null
                } else {
                val text = body["input"]?.jsonPrimitive?.content.orEmpty()
                when (model) { "canopylabs/orpheus-v1-english" -> 22.0; else -> null }?.let { text.codePointCount(0, text.length) * it / 1_000_000 }
                }
            } else if (input != null && output != null) SpendingRates.price(model, input, output + (tokens("thoughtsTokenCount") ?: 0), tokens("cachedContentTokenCount") ?: 0) else null
            val month = LocalDate.now().toString().take(7)
            val rows = history.value.rows.toMutableList()
            val index = rows.indexOfFirst { it.month == month && it.provider == provider && it.model == model && it.bookId == book?.id.orEmpty() }
            val old = rows.getOrNull(index) ?: SpendingRow(month, provider, model, book?.id.orEmpty(), book?.title ?: "Previews / other")
            val updated = old.copy(usd = old.usd + (cost ?: 0.0), requests = old.requests + 1,
                unpriced = old.unpriced + if (cost == null) 1 else 0, approximated = old.approximated + if (approximate && cost != null) 1 else 0)
            if (index < 0) rows += updated else rows[index] = updated
            val next = history.value.copy(rows = rows)
            atomicWrite(file, json.encodeToString(next))
            history.value = next
        } catch (_: Exception) {
            // Accounting failure must never cause a paid synthesis request to be retried.
            problem.value = "Spending tracking failed. The displayed total may be incomplete."
        }
    }
}
