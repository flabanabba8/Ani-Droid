package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.geminireader.ReaderApp
import com.geminireader.importer.*

@Composable fun ImportReview(app: ReaderApp) {
    val imported = app.pendingImport ?: return
    var options by remember(imported) { mutableStateOf(CleanupOptions()) }
    val headers = remember(imported) { ImportCleanup.headers(imported.book) }
    val changed = remember(imported, options) { imported.book.chapters.flatMap { ch -> ch.paragraphs.mapNotNull { before -> val after = ImportCleanup.paragraph(before, options); if (after == before) null else before to after } } }
    AlertDialog(onDismissRequest = { app.pendingImport = null }, title = { Text("Review ${imported.book.title}") }, text = {
        LazyColumn {
            item { Text("Optional cleanup changes only the imported copy. Review removals carefully: numbers and repeated text can be part of the story. The original file is unchanged.") }
            item { Row { Checkbox(options.pageNumbers, { options = options.copy(pageNumbers = it) }); Text("Remove standalone page numbers") } }
            item { Row { Checkbox(options.footnoteMarkers, { options = options.copy(footnoteMarkers = it) }); Text("Remove numeric footnote markers") } }
            item { Row { Checkbox(options.wrappedLines, { options = options.copy(wrappedLines = it) }); Text("Join wrapped lines inside paragraphs") } }
            item { Choice("Repeated header to remove", options.header, listOf("") + headers, { it.ifBlank { "None" } }) { options = options.copy(header = it) } }
            item { Text("${changed.size} paragraphs would change. Preview:") }
            items(changed.take(30)) { (before, after) -> Text("Before: $before\nAfter: ${after.ifBlank { "[removed]" }}\n") }
            if (changed.size > 30) item { Text("Showing the first 30 changes.") }
            item { TextButton(enabled = !app.busy, onClick = { app.finishImport(imported) }) { Text("Import original text") } }
        }
    }, confirmButton = { TextButton(enabled = !app.busy && changed.isNotEmpty(), onClick = { app.task { val cleaned = ImportCleanup.apply(imported.book, options); app.finishImportNow(imported.copy(book = cleaned)) } }) { Text("Import cleaned copy") } }, dismissButton = { TextButton(onClick = { app.pendingImport = null }) { Text("Cancel") } })
}
