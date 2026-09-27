package com.geminireader.analysis

import com.geminireader.data.Settings

object DeepgramVoices {
    data class Voice(val id: String, val name: String, val gender: String)
    const val DEFAULT = "flux-hannah-en"
    val all = listOf(
        Voice(DEFAULT, "Hannah · American storyteller", "female"),
        Voice("flux-kit-en", "Kit · British narrator", "male"),
        Voice("flux-sienna-en", "Sienna · warm American", "female"),
        Voice("flux-cliff-en", "Cliff · deep American", "male"),
        Voice("flux-gemma-en", "Gemma · British", "female"),
        Voice("flux-colin-en", "Colin · warm British", "male"))
    val names = all.map { it.id }
    fun valid(id: String) = id.matches(Regex("flux-[a-z]{2,24}-en"))
    fun label(id: String) = all.firstOrNull { it.id == id }?.name ?: id.ifBlank { "Automatic" }
    fun distinct(c: Character, s: Settings): String {
        if (valid(c.voice)) return c.voice
        if (c.suggestedVoice in names) return c.suggestedVoice
        val pool = all.filter { it.gender.equals(c.gender, true) }
        return if (pool.isEmpty()) s.deepgramVoice else pool[Math.floorMod(c.id.hashCode(), pool.size)].id
    }
}
