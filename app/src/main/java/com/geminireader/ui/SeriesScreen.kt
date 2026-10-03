package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geminireader.ReaderApp
import com.geminireader.data.*
import com.geminireader.analysis.VoiceCatalog
import com.geminireader.analysis.KokoroVoices
import com.geminireader.analysis.GroqVoices

@Composable fun SeriesScreen(app: ReaderApp) {
    val book = app.book ?: return
    var revision by remember { mutableIntStateOf(0) }
    val all = remember(revision) { app.performances.series() }
    val link = remember(revision) { app.performances.link(book.id) }
    val selected = all.firstOrNull { it.id == link.series }
    var name by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var intensity by remember { mutableFloatStateOf(.5f) }
    var delivery by remember { mutableStateOf("natural") }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, VoiceProfile>?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Series voice profiles", style = MaterialTheme.typography.headlineMedium) }
        item { TextButton(onClick = { app.screen = "reader" }) { Text("Back to book") } }
        item { Text("Only your voice profiles are shared, never analyzed descriptions or hidden aliases. Linking is manual: do not link a disguised identity until its reveal. Current chapter analysis can still see ahead; this is not a spoiler-free analysis mode.") }
        item { Choice("This book's series", link.series, listOf("") + all.map { it.id }, { id -> all.firstOrNull { it.id == id }?.name ?: "None" }) { id -> app.task { app.playback.stop(); app.performances.saveLink(book.id, SeriesLink(id)); revision++ } } }
        item { OutlinedTextField(name, { name = it.take(100) }, label = { Text("New series name") }) }
        item { Button(onClick = { app.task { require(name.isNotBlank()) { "Enter a series name" }; val value = Series(name = name.trim()); app.performances.saveSeries(all + value); app.playback.stop(); app.performances.saveLink(book.id, SeriesLink(value.id)); name = ""; revision++ } }) { Text("Create and join series") } }
        if (selected != null) {
            item { Text("Create a reusable voice profile", style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(label, { label = it.take(100) }, label = { Text("Your profile label (avoid spoilers)") }) }
            item { if (app.settings.engine == "fish") FishVoicePicker("Distinct voice", voice, automatic = true) { voice = it } else if (app.settings.engine == "speechify") SpeechifyVoicePicker("Distinct voice", voice, automatic = true) { voice = it } else if (app.settings.engine == "inworld") InworldVoicePicker("Distinct voice", voice, automatic = true) { voice = it } else if (app.settings.engine == "deepgram") DeepgramVoicePicker("Distinct voice", voice, automatic = true) { voice = it } else if (app.settings.engine == "cartesia") CartesiaVoicePicker("Distinct voice", voice, automatic = true) { voice = it }
                else if (app.settings.engine == "elevenlabs") ElevenVoicePicker("Distinct voice", voice, automatic = true) { voice = it }
                else Choice("Distinct voice", voice, listOf("") + (if (app.settings.engine == "kokoro") KokoroVoices.names else if (app.settings.engine == "groq") GroqVoices.namesFor(app.settings.groqModel) else VoiceCatalog.names), { if (app.settings.engine == "kokoro") KokoroVoices.label(it) else if (app.settings.engine == "groq") GroqVoices.label(it) else VoiceCatalog.label(it) }) { voice = it } }
            if (app.settings.engine !in listOf("kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish")) item { OutlinedTextField(style, { style = it.take(600) }, label = { Text("Performance instructions only") }) }
            item { Text("Expression: ${(intensity * 100).toInt()}%"); Slider(intensity, { intensity = it }); OutlinedTextField(delivery, { delivery = it.take(200) }, label = { Text("Stable delivery (supported engines)") }) }
            item { Button(onClick = { app.task { require(app.settings.engine != "fish" || voice.isBlank() || com.geminireader.analysis.FishVoices.valid(voice.trim())) { "Enter a valid Fish voice ID" }; require(app.settings.engine != "speechify" || voice.isBlank() || com.geminireader.analysis.SpeechifyVoices.valid(voice.trim())) { "Enter a valid Speechify voice ID" }; require(app.settings.engine != "inworld" || voice.isBlank() || com.geminireader.analysis.InworldVoices.valid(voice.trim())) { "Enter a valid Inworld voice ID" }; require(app.settings.engine != "deepgram" || voice.isBlank() || com.geminireader.analysis.DeepgramVoices.valid(voice.trim())) { "Enter a valid Deepgram voice model" }; require(app.settings.engine != "cartesia" || voice.isBlank() || com.geminireader.analysis.CartesiaVoices.valid(voice.trim())) { "Enter a valid Cartesia voice ID" }; require(label.isNotBlank()) { "Enter a profile label" }; require(app.settings.engine != "elevenlabs" || voice.isBlank() || com.geminireader.analysis.ElevenVoices.valid(voice.trim())) { "Enter a valid ElevenLabs voice ID" }; val profile = VoiceProfile(id = editing ?: java.util.UUID.randomUUID().toString(), label = label.trim(), voice = voice, style = style.trim(), intensity = intensity, delivery = delivery); app.playback.stop(); app.performances.saveSeries(all.map { if (it.id == selected.id) it.copy(profiles = it.profiles.filter { p -> p.id != profile.id } + profile) else it }); label = ""; style = ""; editing = null; revision++ } }) { Text(if (editing == null) "Add voice profile" else "Save voice profile") } }
            items(selected.profiles, key = { it.id }) { profile -> Column {
                Text("${profile.label} · ${profile.voice.ifBlank { "Book voice" }} · ${profile.style}")
                TextButton(onClick = { editing = profile.id; label = profile.label; voice = profile.voice; style = profile.style; intensity = profile.intensity; delivery = profile.delivery }) { Text("Edit profile") }
                TextButton(onClick = { deleting = selected.id to profile }) { Text("Delete profile") }
            } }
            item { Text("Link speakers already known to you", style = MaterialTheme.typography.titleMedium) }
            items(app.cast, key = { it.id }) { character ->
                Choice(character.name, link.voices[character.id].orEmpty(), listOf("") + selected.profiles.map { it.id }, { id -> selected.profiles.firstOrNull { it.id == id }?.label ?: "Use book voice" }) { id -> app.task { app.playback.stop(); app.performances.saveLink(book.id, link.copy(voices = link.voices + (character.id to id))); revision++ } }
            }
        }
    }
    deleting?.let { (seriesId, profile) ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${profile.label}?") },
            text = { Text("Removes this saved voice profile and its character links across this series. Characters return to their book voice settings. Book text and exported audio are kept. Prepared chapters using this profile may need preparing again. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { deleting = null; app.task {
                app.cancelPreparation(); app.playback.stop()
                app.performances.deleteProfile(seriesId, profile.id)
                if (editing == profile.id) { editing = null; label = ""; voice = ""; style = "" }
                revision++; app.status = "Voice profile deleted; linked characters use their book voices"
            } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}
