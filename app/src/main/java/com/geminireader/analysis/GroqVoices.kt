package com.geminireader.analysis

import com.geminireader.data.Settings

/** Groq Orpheus English voice catalog, checked 2026-09-15. */
object GroqVoices {
    const val ENGLISH = "canopylabs/orpheus-v1-english"
    val models = listOf(ENGLISH)
    fun namesFor(model: String): List<String> {
        require(model == ENGLISH) { "Select Groq Orpheus English" }
        return names
    }
    val female = listOf("autumn", "diana", "hannah")
    val male = listOf("austin", "daniel", "troy")
    val names = female + male
    fun label(voice: String) = if (voice.isBlank()) "Automatic" else voice.replaceFirstChar { it.uppercase() }
    fun distinct(character: Character, settings: Settings): String {
        val available = namesFor(settings.groqModel)
        if (character.voice in available) return character.voice
        if (character.suggestedVoice in available) return character.suggestedVoice
        val pool = when (character.gender.lowercase()) { "female" -> available.take(3); "male" -> available.drop(3); else -> listOf(settings.groqVoice) }
        return pool[Math.floorMod(character.id.hashCode(), pool.size)]
    }
}
