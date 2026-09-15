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
import com.geminireader.tts.Speech

@Composable fun PronunciationScreen(app: ReaderApp) {
    val book = app.book ?: return
    var revision by remember { mutableIntStateOf(0) }
    val entries = remember(revision) { app.performances.applicable(book.id) }
    var editing by remember { mutableStateOf<Pronunciation?>(null) }
    var written by remember { mutableStateOf("") }
    var spoken by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("book") }
    val series = app.performances.link(book.id).series
    fun entry() = Pronunciation(editing?.id ?: java.util.UUID.randomUUID().toString(), written.trim(), spoken.trim(), scope, when (scope) { "book" -> book.id; "series" -> series; else -> "" })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Pronunciation dictionary", style = MaterialTheme.typography.headlineMedium) }
        item { TextButton(onClick = { app.screen = "reader" }) { Text("Back to book") } }
        item { Text("Type a word or phrase and the way it should be spoken. Only speech changes; book text and character analysis keep the original spelling. Preview may generate billed audio.") }
        item { OutlinedTextField(written, { written = it.take(100) }, label = { Text("Written word or phrase") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(spoken, { spoken = it.take(200) }, label = { Text("Speak as (phonetic spelling)") }, modifier = Modifier.fillMaxWidth()) }
        item { Choice("Scope", scope, if (series.isBlank()) listOf("book", "global") else listOf("book", "series", "global")) { scope = it } }
        item { Row {
            Button(onClick = { app.task { val value = entry(); PronunciationRules.validate(value); app.playback.stop(); app.performances.save(app.performances.pronunciations().filter { it.id != value.id && !(it.scope == value.scope && it.owner == value.owner && it.written.equals(value.written, true)) } + value); revision++; editing = null; written = ""; spoken = ""; app.status = "Pronunciation saved" } }, enabled = !app.busy) { Text(if (editing == null) "Add pronunciation" else "Save pronunciation") }
            TextButton(onClick = { app.task { val value = entry(); PronunciationRules.validate(value); app.playback.preview(Speech("I said ${value.spoken}. Then I said ${value.spoken} again.", app.settings.narratorPrompt, app.settings.narratorVoice, 350)) } }) { Text("Preview") }
        } }
        if (editing != null) item { TextButton(onClick = { editing = null; written = ""; spoken = "" }) { Text("Cancel edit") } }
        items(entries, key = { it.id }) { value -> Card { Column(Modifier.padding(12.dp)) {
            Text("${value.written} → ${value.spoken}")
            Text(value.scope, style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(onClick = { editing = value; written = value.written; spoken = value.spoken; scope = value.scope }) { Text("Edit") }
                TextButton(onClick = { app.task { app.playback.stop(); app.performances.save(app.performances.pronunciations().filter { it.id != value.id }); revision++; app.status = "Pronunciation deleted" } }) { Text("Delete") }
            }
        } } }
    }
}
