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
import com.geminireader.text.Segment

@Composable fun CharactersScreen(app: ReaderApp) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Characters", style = MaterialTheme.typography.headlineLarge) }
        item { Row { TextButton(onClick = { app.screen = "reader" }) { Text("Back to book") }; Button(onClick = { app.analyzeWholeBook() }, enabled = !app.busy) { Text("Analyze whole book") } } }
        item { Button(onClick = { app.analyzeChapter() }, enabled = !app.busy) { Text("Analyze this chapter / retry") } }
        item { Text("Characters are detected before each chapter plays. Edit how the narrator performs them; distinct voices are used only in distinct-voice mode.", style = MaterialTheme.typography.bodySmall) }
        if (app.cast.isEmpty()) item { Text("No characters yet. Play a chapter or analyze the book.") }
        items(app.cast, key = { it.id }) { character ->
            var style by remember(character) { mutableStateOf(character.voiceStyle) }
            var voice by remember(character) { mutableStateOf(character.voice) }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(character.name, style = MaterialTheme.typography.titleLarge)
                    Text("${character.gender} · ${character.description}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(style, { style = it.take(600) }, label = { Text("Performance instructions") }, modifier = Modifier.fillMaxWidth())
                    Choice("Distinct voice", voice, listOf("") + VoiceCatalog.names, VoiceCatalog::label) { voice = it }
                    Text("Automatic choice: ${VoiceCatalog.label(VoiceDirector.distinctVoice(character.copy(voice = ""), app.settings))}", style = MaterialTheme.typography.bodySmall)
                    Text("Current performance: ${VoiceCatalog.label(if (app.settings.characterMode == "distinct") VoiceDirector.distinctVoice(character.copy(voice = voice), app.settings) else app.settings.narratorVoice)}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { app.saveCharacter(character.copy(voiceStyle = style, voice = voice.trim())) }) { Text("Save") }
                        TextButton(onClick = { app.playback.preview(VoiceDirector.direct(Segment(0, 0, 33, "Hello. What a curious adventure!", "preview"), app.settings, character.copy(voiceStyle = style, voice = voice.trim()), "curious", true)) }) { Text("Preview") }
                    }
                }
            }
        }
    }
}
