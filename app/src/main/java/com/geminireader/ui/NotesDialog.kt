package com.geminireader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.geminireader.ReaderApp
import com.geminireader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun NotesDialog(app: ReaderApp, book: Book, jump: (ReadingNote) -> Unit, close: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val notes = remember(revision) { app.notes.list(book.id) }
    var selected by remember { mutableStateOf(notes.map { it.id }.toSet()) }
    var exportText by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) app.task {
        val text = exportText
        withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(text) } }
        app.status = "Selected quotes and notes exported"
    } }
    AlertDialog(onDismissRequest = close, title = { Text("Bookmarks and quotes") }, text = {
        LazyColumn {
            if (notes.isEmpty()) item { Text("Long-press a passage to save it, or bookmark the spoken sentence from Book tools.") }
            items(notes, key = { it.id }) { entry ->
                var note by remember(entry) { mutableStateOf(entry.note) }
                Row { Checkbox(entry.id in selected, { checked -> selected = if (checked) selected + entry.id else selected - entry.id }); Text("Chapter ${entry.chapter + 1}, paragraph ${entry.paragraph + 1}") }
                Text(entry.quote)
                if (!app.notes.unchanged(book, entry)) Text("The source text has changed; this saved quote is preserved.")
                OutlinedTextField(note, { note = it.take(10_000) }, label = { Text("Note") })
                Row {
                    TextButton(onClick = { app.task { app.notes.save(book.id, entry.copy(note = note)); revision++ } }) { Text("Save note") }
                    TextButton(onClick = { jump(entry); close() }) { Text("Go to") }
                    TextButton(onClick = { app.task { app.notes.delete(book.id, entry.id); revision++; selected = selected - entry.id } }) { Text("Delete") }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { exportText = app.notes.export(book, notes.filter { it.id in selected }); picker.launch("PageCast-quotes.txt") }) { Text("Export selected") } }, dismissButton = { TextButton(onClick = close) { Text("Close") } })
}
