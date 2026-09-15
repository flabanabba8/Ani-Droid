package com.geminireader.analysis

import com.geminireader.data.Settings

data class VoiceProfile(val name: String, val gender: String, val trait: String) {
    val label get() = "$name · $gender · $trait"
    val context get() = "$name: Google-listed $gender voice; default character: $trait."
}

/** Published voice metadata, not measured pitch or a listening evaluation. See docs/voices.md. */
object VoiceCatalog {
    const val VERSION = "google-2026-09-15-v1"
    val all = listOf(
        VoiceProfile("Achernar", "female", "soft"),
        VoiceProfile("Achird", "male", "friendly"),
        VoiceProfile("Algenib", "male", "gravelly"),
        VoiceProfile("Algieba", "male", "smooth"),
        VoiceProfile("Alnilam", "male", "firm"),
        VoiceProfile("Aoede", "female", "breezy"),
        VoiceProfile("Autonoe", "female", "bright"),
        VoiceProfile("Callirrhoe", "female", "easy-going"),
        VoiceProfile("Charon", "male", "informative"),
        VoiceProfile("Despina", "female", "smooth"),
        VoiceProfile("Enceladus", "male", "breathy"),
        VoiceProfile("Erinome", "female", "clear"),
        VoiceProfile("Fenrir", "male", "excitable"),
        VoiceProfile("Gacrux", "female", "mature"),
        VoiceProfile("Iapetus", "male", "clear"),
        VoiceProfile("Kore", "female", "firm"),
        VoiceProfile("Laomedeia", "female", "upbeat"),
        VoiceProfile("Leda", "female", "youthful"),
        VoiceProfile("Orus", "male", "firm"),
        VoiceProfile("Pulcherrima", "female", "forward"),
        VoiceProfile("Puck", "male", "upbeat"),
        VoiceProfile("Rasalgethi", "male", "informative"),
        VoiceProfile("Sadachbia", "male", "lively"),
        VoiceProfile("Sadaltager", "male", "knowledgeable"),
        VoiceProfile("Schedar", "male", "even"),
        VoiceProfile("Sulafat", "female", "warm"),
        VoiceProfile("Umbriel", "male", "easy-going"),
        VoiceProfile("Vindemiatrix", "female", "gentle"),
        VoiceProfile("Zephyr", "female", "bright"),
        VoiceProfile("Zubenelgenubi", "male", "casual")
    )
    val names = all.map { it.name }
    fun find(name: String) = all.firstOrNull { it.name == name }
    fun label(name: String) = find(name)?.label ?: name.ifBlank { "Automatic (analysis recommendation)" }
    fun performance(voice: String, gender: String): String = when {
        find(voice)?.gender == "male" && gender.equals("female", true) ->
            "Use a lighter, higher register relative to this voice's natural speaking register, with less chest weight."
        find(voice)?.gender == "female" && gender.equals("male", true) ->
            "Use a slightly lower pitch relative to this voice's natural speaking register and fuller chest resonance."
        else -> "Stay near this voice's natural register; distinguish the character through pace, resonance and articulation."
    } + " Keep changes comfortable and believable, not falsetto, caricature or an exaggerated growl. Explicit character instructions take precedence; unknown or nonbinary gender alone does not imply a pitch shift."

    fun analysisContext(settings: Settings): String = """
Voice catalog ($VERSION):
${all.joinToString("\n") { it.context }}
Selected narrator: ${find(settings.narratorVoice)?.context ?: settings.narratorVoice}
Character mode: ${settings.characterMode}. Performance keeps the selected narrator for all characters; distinct uses a separate selected voice for each character.
For suggestedVoice, choose one catalog name whose documented trait suits the character's personality, age and manner of speech, using textual evidence. Prefer a gender-matched voice when gender is explicit, but never infer speaker identity from voice gender. Reuse a suitable previous suggestion for consistency.
voiceStyle must describe the character's target delivery (pace, timbre, articulation, energy and any text-supported accent), not a pitch shift tied to a different narrator. Use the catalog to explain how to retain or soften the selected voice's default trait for the role. The final director applies relative pitch: male voice playing female character -> modestly higher/lighter; female voice playing male character -> modestly lower/fuller. Same or unknown gender -> no automatic pitch shift. Apply this to the actual voice, not always the narrator. Character-specific explicit directions override the default. Do not fabricate exact pitch ranges, semitones, accents or ages from catalog traits. The catalog gives tendencies, not fixed emotional limitations.
""".trimIndent()

    fun cacheIdentity(settings: Settings) = "$VERSION|${settings.narratorVoice}|${settings.characterMode}"
}
