package com.geminireader.analysis

import com.geminireader.data.Settings

/** Explicit speaker order from the bundled Sherpa Kokoro 1.0 model; English voices 0–27. */
object KokoroVoices {
    val names = ("af_alloy af_aoede af_bella af_heart af_jessica af_kore af_nicole af_nova af_river af_sarah af_sky " +
        "am_adam am_echo am_eric am_fenrir am_liam am_michael am_onyx am_puck am_santa " +
        "bf_alice bf_emma bf_isabella bf_lily bm_daniel bm_fable bm_george bm_lewis").split(" ")
    fun speakerId(voice: String): Int = names.indexOf(voice).also { require(it >= 0) { "Select a supported on-device Kokoro voice" } }
    private val languages = mapOf('a' to "American English", 'b' to "British English", 'e' to "Spanish", 'f' to "French", 'h' to "Hindi", 'i' to "Italian", 'p' to "Brazilian Portuguese")
    fun label(voice: String): String = if (voice in names) "$voice · ${languages[voice[0]]} · ${if (voice[1] == 'f') "female" else "male"}" else voice.ifBlank { "Automatic" }
    fun distinct(character: Character, settings: Settings): String {
        if (character.voice in names) return character.voice
        val candidates = names.filter { it[0] == settings.kokoroVoice[0] && when (character.gender.lowercase()) { "female" -> it[1] == 'f'; "male" -> it[1] == 'm'; else -> it == settings.kokoroVoice } }
        return if (candidates.isEmpty()) settings.kokoroVoice else candidates[Math.floorMod(character.id.hashCode(), candidates.size)]
    }
}
