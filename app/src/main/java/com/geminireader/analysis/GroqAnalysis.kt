package com.geminireader.analysis

import com.geminireader.data.Settings
import com.geminireader.data.json
import com.geminireader.tts.*
import kotlinx.serialization.json.*

/** Adapter for our text-analysis requests; preserves the existing validated response path. */
object GroqAnalysis {
    const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    val models = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b")

    fun schema(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::schema))
        is JsonObject -> {
            val converted = value.mapValues { (key, item) ->
                if (key == "type") str(item.jsonPrimitive.content.lowercase()) else schema(item)
            }
            JsonObject(if (converted["type"]?.jsonPrimitive?.content == "object")
                converted + ("additionalProperties" to JsonPrimitive(false)) else converted)
        }
        else -> value
    }

    fun body(request: JsonObject, model: String): JsonObject {
        require(model in models) { "Select a supported Groq analysis model" }
        val messages = mutableListOf<JsonElement>()
        fun parts(content: JsonObject) = content["parts"]!!.jsonArray.joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
        request["systemInstruction"]?.jsonObject?.let { messages += obj("role" to str("system"), "content" to str(parts(it))) }
        request["contents"]!!.jsonArray.forEach { messages += obj("role" to str("user"), "content" to str(parts(it.jsonObject))) }
        return obj("model" to str(model), "messages" to JsonArray(messages),
            "reasoning_effort" to str("low"), "max_completion_tokens" to JsonPrimitive(8192),
            "response_format" to obj("type" to str("json_schema"), "json_schema" to obj(
                "name" to str("reader_analysis"), "strict" to JsonPrimitive(true),
                "schema" to schema(request["generationConfig"]!!.jsonObject["responseSchema"]!!))))
    }

    fun response(value: JsonObject): JsonObject {
        val choice = value["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("Groq analysis returned no result")
        require(choice["finish_reason"]?.jsonPrimitive?.content == "stop") { "Groq analysis was incomplete; retry with a shorter passage" }
        val message = choice["message"]!!.jsonObject
        require(message["refusal"] == null || message["refusal"] == JsonNull) { "Groq declined this text request" }
        val text = message["content"]?.jsonPrimitive?.contentOrNull
        require(!text.isNullOrBlank()) { "Groq analysis returned no text" }
        json.parseToJsonElement(text).jsonObject // Reject non-JSON instead of saving a partial batch.
        return obj("candidates" to arr(obj("finishReason" to str("STOP"),
            "content" to obj("parts" to arr(obj("text" to str(text)))))))
    }

    suspend fun request(api: HttpApi, settings: Settings, request: JsonObject): JsonObject =
        response(api.groqChat(ENDPOINT, settings.groqApiKey, body(request, settings.groqTextModel)))
}
