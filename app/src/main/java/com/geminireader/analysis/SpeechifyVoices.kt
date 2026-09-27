package com.geminireader.analysis

import com.geminireader.data.Settings

object SpeechifyVoices {
    data class Voice(val id: String, val name: String, val gender: String)
    const val DEFAULT = "geffen_32"
    val all = listOf(
        Voice("geffen_32", "Geffen", "female"),
        Voice("alec", "Alec", "male"),
        Voice("carly", "Carly", "female"),
        Voice("archie", "Archie", "male"),
        Voice("beatrice_32", "Beatrice", "female"),
        Voice("benjamin", "Benjamin", "male"))
    val names = all.map { it.id }
    fun valid(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
    fun label(id: String) = all.firstOrNull { it.id == id }?.name ?: id.ifBlank { "Automatic" }
    fun distinct(c: Character, s: Settings): String {
        if (valid(c.voice)) return c.voice
        if (c.suggestedVoice in names) return c.suggestedVoice
        val pool = all.filter { it.gender.equals(c.gender, true) }
        return if (pool.isEmpty()) s.speechifyVoice else pool[Math.floorMod(c.id.hashCode(), pool.size)].id
    }
}
