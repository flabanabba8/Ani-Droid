package com.geminireader.analysis

import com.geminireader.data.Settings

object InworldVoices {
    data class Voice(val id: String, val name: String, val gender: String)
    const val DEFAULT = "Sarah"
    val all = listOf(
        Voice(DEFAULT, "Sarah", "female"),
        Voice("Dennis", "Dennis", "male"),
        Voice("Chloe", "Chloe · thoughtful", "female"),
        Voice("Clive", "Clive · British", "male"),
        Voice("Deborah", "Deborah · calm", "female"),
        Voice("Simon", "Simon · steady", "male"))
    val names = all.map { it.id }
    fun valid(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
    fun label(id: String) = all.firstOrNull { it.id == id }?.name ?: id.ifBlank { "Automatic" }
    fun distinct(c: Character, s: Settings): String {
        if (valid(c.voice)) return c.voice
        if (c.suggestedVoice in names) return c.suggestedVoice
        val pool = all.filter { it.gender.equals(c.gender, true) }
        return if (pool.isEmpty()) s.inworldVoice else pool[Math.floorMod(c.id.hashCode(), pool.size)].id
    }
}
