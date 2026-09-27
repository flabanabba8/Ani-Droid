package com.geminireader.analysis

import com.geminireader.data.*
import com.geminireader.tts.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class GroqAnalysisTest {
    @Test fun adaptsStrictSchemaAndRejectsIncompleteResults() {
        val request = PassageRewriter.request("A short passage.", "openai/gpt-oss-20b")
        val body = GroqAnalysis.body(request, "openai/gpt-oss-20b")
        assertEquals(2, body["messages"]!!.jsonArray.size)
        val schema = body["response_format"]!!.jsonObject["json_schema"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.boolean)
        val result = obj("choices" to arr(obj("finish_reason" to str("stop"),
            "message" to obj("content" to str("""{"text":"A quiet scene."}""")))))
        assertEquals("A quiet scene.", PassageRewriter.parse(GroqAnalysis.response(result)))
        try { GroqAnalysis.response(obj("choices" to arr(obj("finish_reason" to str("length"))))); fail() }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun nestedCharacterSchemaUsesGroqVoicesAndRetainsManualVoice() {
        val schema = GroqAnalysis.schema(CharacterAnalyzer.voiceSchema(GroqVoices.names)).jsonObject
        val character = schema["properties"]!!.jsonObject["characters"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(false, character["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals(GroqVoices.names, character["properties"]!!.jsonObject["suggestedVoice"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        val s = Settings(engine = "groq")
        assertEquals("hannah", GroqVoices.distinct(Character("a", "A", suggestedVoice = "hannah"), s))
        assertEquals("troy", GroqVoices.distinct(Character("a", "A", voice = "troy", suggestedVoice = "hannah"), s))
        assertEquals("groq", s.textEngine)
        assertEquals("vertex", s.copy(engine = "vertex").textEngine)
    }

    @Test fun recordsChatTokensByBookAndMarksMissingUsage() {
        val dir = Files.createTempDirectory("groq-analysis-cost").toFile()
        try {
            val tracker = SpendingTracker(dir.resolve("spending.json"))
            val body = obj("model" to str("openai/gpt-oss-20b"))
            tracker.record(GroqAnalysis.ENDPOINT, body, obj("usage" to obj("prompt_tokens" to JsonPrimitive(1000), "completion_tokens" to JsonPrimitive(100))), SpendingBook("a", "A"))
            assertEquals(0.000105, tracker.state.value.rows.single().usd, 1e-10)
            tracker.record(GroqAnalysis.ENDPOINT, body, obj("usage" to obj("prompt_tokens" to JsonPrimitive(1000),
                "completion_tokens" to JsonPrimitive(100), "prompt_tokens_details" to obj("cached_tokens" to JsonPrimitive(500)))), SpendingBook("cached", "Cached"))
            assertEquals(0.000086, tracker.state.value.rows.first { it.bookId == "cached" }.usd, 1e-10)
            tracker.record(GroqAnalysis.ENDPOINT, body, obj(), SpendingBook("b", "B"))
            assertEquals(1L, tracker.state.value.rows.first { it.bookId == "b" }.unpriced)
        } finally { dir.deleteRecursively() }
    }
}
