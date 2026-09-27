package com.geminireader.analysis

object AnalysisModels {
    val defaults = listOf("gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro")
    fun choices(current: String, fetched: List<String>) = (defaults + current + fetched.filter {
        it.startsWith("gemini-") && listOf("tts", "audio", "live", "image", "embedding").none { kind -> it.contains(kind) }
    }).filter { it.isNotBlank() }.distinct()
}
