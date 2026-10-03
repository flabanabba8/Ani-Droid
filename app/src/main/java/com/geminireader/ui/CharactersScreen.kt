package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geminireader.ReaderApp
import com.geminireader.analysis.VoiceDirector
import com.geminireader.analysis.VoiceCatalog
import com.geminireader.analysis.KokoroVoices
import com.geminireader.analysis.GroqVoices
import com.geminireader.text.Segment

@Composable fun CharactersScreen(app: ReaderApp) {
    var audition by remember { mutableStateOf("Hello. What a curious adventure!") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Characters", style = MaterialTheme.typography.headlineLarge) }
        item { Row { TextButton(onClick = { app.screen = "reader" }) { Text("Back to book") }; Button(onClick = { app.analyzeWholeBook() }, enabled = !app.busy) { Text("Analyze whole book") } } }
        item { Button(onClick = { app.analyzeChapter() }, enabled = !app.busy) { Text("Analyze this chapter / retry") } }
        item { Text("Characters are detected before each chapter plays. Edit how they sound. Locking a voice overrides the narrator in Performance mode; Narrator mode still uses one voice for all text.", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(audition, { audition = it.take(500) }, label = { Text("Audition line (used for every voice)") }, modifier = Modifier.fillMaxWidth()) }
        if (app.cast.isEmpty()) item { Text("No characters yet. Play a chapter or analyze the book.") }
        items(app.cast, key = { it.id }) { character ->
            var style by remember(character) { mutableStateOf(character.voiceStyle) }
            var voice by remember(character) { mutableStateOf(character.voice) }
            var locked by remember(character) { mutableStateOf(character.performanceLocked) }
            var intensity by remember(character) { mutableFloatStateOf(character.intensity) }
            var delivery by remember(character) { mutableStateOf(character.delivery) }
            val candidate = character.copy(voiceStyle = style, voice = voice.trim().ifBlank { if (locked) VoiceDirector.distinctVoice(character, app.settings) else "" }, performanceLocked = locked, intensity = intensity, delivery = delivery)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(character.name, style = MaterialTheme.typography.titleLarge)
                    Text("${character.gender} · ${character.description}", style = MaterialTheme.typography.bodySmall)
                    if (app.settings.engine !in listOf("kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish")) OutlinedTextField(style, { style = it.take(600) }, label = { Text("Performance instructions") }, modifier = Modifier.fillMaxWidth())
                    if (app.settings.engine == "fish") FishVoicePicker("Distinct voice", voice, automatic = true) { voice = it } else if (app.settings.engine == "speechify") SpeechifyVoicePicker("Distinct voice", voice, automatic = true) { voice = it } else if (app.settings.engine == "inworld") InworldVoicePicker("Distinct voice", voice, automatic = true) { voice = it } else if (app.settings.engine == "deepgram") DeepgramVoicePicker("Distinct voice", voice, automatic = true) { voice = it } else if (app.settings.engine == "cartesia") CartesiaVoicePicker("Distinct voice", voice, automatic = true) { voice = it }
                else if (app.settings.engine == "elevenlabs") ElevenVoicePicker("Distinct voice", voice, automatic = true) { voice = it }
                    else Choice("Distinct voice", voice, listOf("") + (if (app.settings.engine == "kokoro") KokoroVoices.names else if (app.settings.engine == "groq") GroqVoices.namesFor(app.settings.groqModel) else VoiceCatalog.names), { if (app.settings.engine == "kokoro") KokoroVoices.label(it) else if (app.settings.engine == "groq") GroqVoices.label(it) else VoiceCatalog.label(it) }) { voice = it }
                    Row { Switch(locked, { locked = it }); Text("Lock voice and delivery", Modifier.padding(12.dp)) }
                    if (app.settings.engine in listOf("vertex", "gemini", "cloud")) {
                        Text("Expression: ${(intensity * 100).toInt()}% · restrained to dramatic")
                        Slider(intensity, { intensity = it })
                        OutlinedTextField(delivery, { delivery = it.take(200) }, label = { Text("Locked delivery") })
                    } else Text("This engine uses voice selection; delivery directions are unavailable.", style = MaterialTheme.typography.bodySmall)
                    Text("Automatic choice: ${VoiceCatalog.label(VoiceDirector.distinctVoice(character.copy(voice = ""), app.settings))}", style = MaterialTheme.typography.bodySmall)
                    Text("Current performance: ${VoiceCatalog.label(if (VoiceDirector.usesCharacterVoice(app.settings, candidate)) VoiceDirector.distinctVoice(candidate, app.settings) else app.settings.speechVoice)}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = (app.settings.engine != "fish" || voice.isBlank() || com.geminireader.analysis.FishVoices.valid(voice.trim())) && (app.settings.engine != "speechify" || voice.isBlank() || com.geminireader.analysis.SpeechifyVoices.valid(voice.trim())) && (app.settings.engine != "inworld" || voice.isBlank() || com.geminireader.analysis.InworldVoices.valid(voice.trim())) && (app.settings.engine != "deepgram" || voice.isBlank() || com.geminireader.analysis.DeepgramVoices.valid(voice.trim())) && (app.settings.engine != "cartesia" || voice.isBlank() || com.geminireader.analysis.CartesiaVoices.valid(voice.trim())) && (app.settings.engine != "elevenlabs" || voice.isBlank() || com.geminireader.analysis.ElevenVoices.valid(voice.trim())), onClick = { app.saveCharacter(candidate) }) { Text("Save") }
                        TextButton(enabled = (app.settings.engine != "fish" || voice.isBlank() || com.geminireader.analysis.FishVoices.valid(voice.trim())) && (app.settings.engine != "speechify" || voice.isBlank() || com.geminireader.analysis.SpeechifyVoices.valid(voice.trim())) && (app.settings.engine != "inworld" || voice.isBlank() || com.geminireader.analysis.InworldVoices.valid(voice.trim())) && (app.settings.engine != "deepgram" || voice.isBlank() || com.geminireader.analysis.DeepgramVoices.valid(voice.trim())) && (app.settings.engine != "cartesia" || voice.isBlank() || com.geminireader.analysis.CartesiaVoices.valid(voice.trim())) && (app.settings.engine != "elevenlabs" || voice.isBlank() || com.geminireader.analysis.ElevenVoices.valid(voice.trim())), onClick = { app.playback.preview(VoiceDirector.direct(Segment(0, 0, audition.ifBlank { "Hello." }.length, audition.ifBlank { "Hello." }, "preview"), app.settings, candidate, "curious", true)) }) { Text("Preview") }
                    }
                }
            }
        }
    }
}
