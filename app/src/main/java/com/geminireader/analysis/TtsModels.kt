package com.geminireader.analysis

/** Provider IDs checked against Google's speech-generation documentation, 2026-10-03.
 * https://docs.cloud.google.com/text-to-speech/docs/gemini-tts
 * https://ai.google.dev/gemini-api/docs/speech-generation
 */
object TtsModels {
    fun choices(engine: String, current: String, fetched: List<String>): List<String> {
        val defaults = if (engine == "gemini") listOf(
            "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts"
        ) else listOf(
            "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-tts", "gemini-2.5-pro-tts", "gemini-2.5-flash-lite-preview-tts"
        )
        return (listOf(current) + defaults + fetched.filter { it.contains("tts", ignoreCase = true) }).filter { it.isNotBlank() }.distinct()
    }
}
