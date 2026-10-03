package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.geminireader.data.Book
import com.geminireader.text.ReadReference

@Composable fun ReadReferenceDialog(book: Book, chapter: Int, paragraph: Int, initialQuery: String = "", close: () -> Unit) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var names by remember { mutableStateOf(emptyList<String>()) }
    var excerpts by remember { mutableStateOf(emptyList<com.geminireader.text.ReadExcerpt>()) }
    LaunchedEffect(book.id, chapter, paragraph) { names = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ReadReference.names(book, chapter, paragraph) } }
    LaunchedEffect(book.id, chapter, paragraph, query) { excerpts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ReadReference.find(book, chapter, paragraph, query) } }
    AlertDialog(onDismissRequest = close, title = { Text("Spoiler-safe reference") }, text = {
        Column {
            Text("Source excerpts before chapter ${chapter + 1}, paragraph ${paragraph + 1}. No future paragraphs, other volumes or analyzed cast notes are used. Name suggestions are text matches, not confirmed identities.")
            OutlinedTextField(query, { query = it.take(100) }, label = { Text("Name or phrase") })
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (query.isBlank()) items(names) { name -> TextButton(onClick = { query = name }) { Text(name) } }
                else {
                    if (excerpts.isEmpty()) item { Text("No earlier mentions found.") }
                    items(excerpts) { entry -> Text("Chapter ${entry.chapter + 1}, paragraph ${entry.paragraph + 1}\n${entry.text}\n") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}
