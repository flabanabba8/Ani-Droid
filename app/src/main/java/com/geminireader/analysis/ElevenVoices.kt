package com.geminireader.analysis

import com.geminireader.data.Settings

object ElevenVoices {
    data class Voice(val id: String, val name: String, val gender: String)
    const val DEFAULT = "JBFqnCBsd6RMkjVDRZzb"
    val all = listOf(
        Voice(DEFAULT, "George · warm storyteller", "male"),
        Voice("nPczCjzI2devNBz1zQrb", "Brian · deep and comforting", "male"),
        Voice("pNInz6obpgDQGcFmaJgB", "Adam · firm", "male"),
        Voice("TX3LPaxmHKxFdv7VOQHJ", "Liam · energetic", "male"),
        Voice("EXAVITQu4vr4xnSDxMaL", "Sarah · reassuring", "female"),
        Voice("cgSgspJ2msm6clMCkdW9", "Jessica · bright and warm", "female"),
        Voice("XrExE9yKIg1WjnnlVkGX", "Matilda · professional", "female"),
        Voice("pFZP5JQG7iQjIQuC4Bku", "Lily · actress", "female"))
    val names = all.map { it.id }
    val models = listOf("eleven_flash_v2_5", "eleven_multilingual_v2", "eleven_v3")
    fun valid(id: String) = id.matches(Regex("[A-Za-z0-9_-]{20,64}")) && !CartesiaVoices.valid(id)
    fun label(id: String) = all.firstOrNull { it.id == id }?.name ?: id.ifBlank { "Automatic" }
    fun distinct(c: Character, s: Settings): String {
        if (valid(c.voice)) return c.voice
        if (c.suggestedVoice in names) return c.suggestedVoice
        val pool = all.filter { it.gender.equals(c.gender, true) }
        return if (pool.isEmpty()) s.elevenVoice else pool[Math.floorMod(c.id.hashCode(), pool.size)].id
    }
}
