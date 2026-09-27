package com.geminireader.analysis

import com.geminireader.data.Settings

object FishVoices {
    data class Voice(val id: String, val name: String, val gender: String)
    const val DEFAULT = "933563129e564b19a115bedd57b7406a"
    val all = listOf(
        Voice("933563129e564b19a115bedd57b7406a", "Sarah", "female"),
        Voice("bf322df2096a46f18c579d0baa36f41d", "Adrian", "male"),
        Voice("b347db033a6549378b48d00acb0d06cd", "Selene", "female"),
        Voice("79d0bd3e4e5444b18f7b6d89b5927bf1", "Jordan", "male"),
        Voice("e3cd384158934cc9a01029cd7d278634", "Laura", "female"),
        Voice("536d3a5e000945adb7038665781a4aca", "Ethan", "male"))
    val names = all.map { it.id }
    fun valid(id: String) = id.matches(Regex("[0-9a-f]{32}"))
    fun label(id: String) = all.firstOrNull { it.id == id }?.name ?: id.ifBlank { "Automatic" }
    fun distinct(c: Character, s: Settings): String {
        if (valid(c.voice)) return c.voice
        if (c.suggestedVoice in names) return c.suggestedVoice
        val pool = all.filter { it.gender.equals(c.gender, true) }
        return if (pool.isEmpty()) s.fishVoice else pool[Math.floorMod(c.id.hashCode(), pool.size)].id
    }
}
