package com.geminireader.analysis

import com.geminireader.data.Settings

object CartesiaVoices {
    data class Voice(val id: String, val name: String, val gender: String)
    const val DEFAULT = "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4"
    val all = listOf(
        Voice(DEFAULT, "Skylar · friendly guide", "female"),
        Voice("47c38ca4-5f35-497b-b1a3-415245fb35e1", "Daniel · modern assistant", "male"),
        Voice("62ae83ad-4f6a-430b-af41-a9bede9286ca", "Gemma · decisive", "female"),
        Voice("ef191366-f52f-447a-a398-ed8c0f2943a1", "Archie · approachable", "male"),
        Voice("9626c31c-bec5-4cca-baa8-f8ba9e84c8bc", "Jacqueline · reassuring", "female"),
        Voice("b24f41fd-00a3-4cd8-992a-a0c9f13f3ef1", "Clive · measured expert", "male"))
    val names = all.map { it.id }
    val models = listOf("sonic-3.6")
    fun valid(id: String) = id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    fun label(id: String) = all.firstOrNull { it.id == id }?.name ?: id.ifBlank { "Automatic" }
    fun distinct(c: Character, s: Settings): String {
        if (valid(c.voice)) return c.voice
        if (c.suggestedVoice in names) return c.suggestedVoice
        val pool = all.filter { it.gender.equals(c.gender, true) }
        return if (pool.isEmpty()) s.cartesiaVoice else pool[Math.floorMod(c.id.hashCode(), pool.size)].id
    }
}
