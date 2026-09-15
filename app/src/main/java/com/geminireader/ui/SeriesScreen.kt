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
    var editing by remember { mutableStateOf<String?>(null) }
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
            item { Choice("Distinct voice", voice, listOf("") + VoiceCatalog.names, VoiceCatalog::label) { voice = it } }
            item { OutlinedTextField(style, { style = it.take(600) }, label = { Text("Performance instructions only") }) }
            item { Button(onClick = { app.task { require(label.isNotBlank()) { "Enter a profile label" }; val profile = VoiceProfile(id = editing ?: java.util.UUID.randomUUID().toString(), label = label.trim(), voice = voice, style = style.trim()); app.playback.stop(); app.performances.saveSeries(all.map { if (it.id == selected.id) it.copy(profiles = it.profiles.filter { p -> p.id != profile.id } + profile) else it }); label = ""; style = ""; editing = null; revision++ } }) { Text(if (editing == null) "Add voice profile" else "Save voice profile") } }
            items(selected.profiles, key = { it.id }) { profile -> Column {
                Text("${profile.label} · ${profile.voice.ifBlank { "Book voice" }} · ${profile.style}")
                TextButton(onClick = { editing = profile.id; label = profile.label; voice = profile.voice; style = profile.style }) { Text("Edit profile") }
            } }
            item { Text("Link speakers already known to you", style = MaterialTheme.typography.titleMedium) }
            items(app.cast, key = { it.id }) { character ->
                Choice(character.name, link.voices[character.id].orEmpty(), listOf("") + selected.profiles.map { it.id }, { id -> selected.profiles.firstOrNull { it.id == id }?.label ?: "Use book voice" }) { id -> app.task { app.playback.stop(); app.performances.saveLink(book.id, link.copy(voices = link.voices + (character.id to id))); revision++ } }
            }
        }
    }
}
