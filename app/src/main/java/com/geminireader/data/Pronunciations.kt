package com.geminireader.data

import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable data class Pronunciation(val id: String = UUID.randomUUID().toString(), val written: String, val spoken: String, val scope: String = "book", val owner: String = "")
@Serializable data class VoiceProfile(val id: String = UUID.randomUUID().toString(), val label: String, val voice: String = "", val style: String = "")
@Serializable data class Series(val id: String = UUID.randomUUID().toString(), val name: String, val profiles: List<VoiceProfile> = emptyList())
@Serializable data class SeriesLink(val series: String = "", val voices: Map<String, String> = emptyMap())

class PerformanceRepository(private val root: File) {
    private inline fun <reified T> read(name: String, fallback: T): T = runCatching { json.decodeFromString<T>(File(root, name).readText()) }.getOrDefault(fallback)
    fun pronunciations(): List<Pronunciation> = read("pronunciations.json", emptyList())
    fun save(entries: List<Pronunciation>) = atomicWrite(File(root, "pronunciations.json"), json.encodeToString(entries))
    fun series(): List<Series> = read("series.json", emptyList())
    fun saveSeries(entries: List<Series>) = atomicWrite(File(root, "series.json"), json.encodeToString(entries))
    fun links(): Map<String, SeriesLink> = read("series-links.json", emptyMap())
    fun link(book: String): SeriesLink = links()[book] ?: SeriesLink()
    fun saveLink(book: String, link: SeriesLink) = atomicWrite(File(root, "series-links.json"), json.encodeToString(links() + (book to link)))
    fun deleteProfile(seriesId: String, profileId: String) {
        // Remove the profile first: any interrupted link cleanup still safely falls back to book voices.
        saveSeries(series().map { if (it.id == seriesId) it.copy(profiles = it.profiles.filterNot { p -> p.id == profileId }) else it })
        val updated = links().mapValues { (_, link) -> if (link.series == seriesId) link.copy(voices = link.voices.filterValues { it != profileId }) else link }
        atomicWrite(File(root, "series-links.json"), json.encodeToString(updated))
    }
    fun applicable(book: String): List<Pronunciation> = pronunciations().filter { it.scope == "global" || (it.scope == "book" && it.owner == book) || (it.scope == "series" && it.owner.isNotBlank() && it.owner == link(book).series) }
}

object PronunciationRules {
    fun validate(entry: Pronunciation) {
        require(entry.written.isNotBlank() && entry.spoken.isNotBlank()) { "Enter both the written phrase and its pronunciation" }
        require(entry.written.length <= 100 && entry.spoken.length <= 200) { "Keep phrases under 100 and pronunciations under 200 characters" }
        require(entry.scope in listOf("book", "series", "global"))
    }
    fun apply(text: String, entries: List<Pronunciation>): String {
        val ranked = entries.sortedByDescending { when (it.scope) { "book" -> 3; "series" -> 2; else -> 1 } }
        val unique = ranked.distinctBy { it.written.lowercase(java.util.Locale.ROOT) }.filter { it.written.isNotBlank() }.sortedByDescending { it.written.length }
        if (unique.isEmpty()) return text
        val pattern = Regex("(?<![\\p{L}\\p{N}_])(?:" + unique.joinToString("|") { Regex.escape(it.written) } + ")(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        // One pass: replacements never cascade. Apostrophes remain outside the word match.
        return pattern.replace(text) { match -> unique.firstOrNull { it.written.equals(match.value, ignoreCase = true) }?.spoken ?: match.value }
    }
}
