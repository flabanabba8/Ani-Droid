package com.geminireader.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geminireader.ReaderApp
import com.geminireader.data.BookMeta
import java.io.File

@Composable fun ReaderUi(app: ReaderApp) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) app.handle(Intent(Intent.ACTION_VIEW, uri)) }
    BackHandler(app.screen != "library") { app.screen = "library" }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff245b4c), background = Color(0xfffaf8f1), surface = Color(0xfffaf8f1))) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { app.screen = "library" }) { Text("Gemini Reader") }
                    if (app.screen == "library") TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import book") }
                }
                if (app.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (app.status.isNotBlank()) Text(app.status, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp), style = MaterialTheme.typography.bodySmall)
                if (app.screen == "reader" && app.book != null) ReaderScreen(app) else LibraryScreen(app)
            }
        }
    }
}

@Composable private fun LibraryScreen(app: ReaderApp) {
    var deleting by remember { mutableStateOf<BookMeta?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Your library", style = MaterialTheme.typography.headlineLarge) }
        item { Text("EPUB · PDF · TXT · HTML · Markdown · FB2 · DOCX", style = MaterialTheme.typography.bodySmall) }
        if (app.library.isEmpty()) item { Text("Import a book to begin reading.\nMOBI/AZW3, DRM and scanned PDFs without a text layer are not supported.", Modifier.padding(vertical = 32.dp)) }
        items(app.library, key = { it.id }) { meta ->
            Card(Modifier.fillMaxWidth().clickable { app.open(meta.id) }) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val bitmap = remember(meta.id) { BitmapFactory.decodeFile(File(app.books.directory(meta.id), "cover").path)?.asImageBitmap() }
                    if (bitmap != null) Image(bitmap, "Cover of ${meta.title}", Modifier.size(60.dp, 90.dp))
                    else Box(Modifier.size(60.dp, 90.dp).background(MaterialTheme.colorScheme.primaryContainer)) { Text(meta.format.uppercase(), Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall) }
                    Column(Modifier.weight(1f)) {
                        Text(meta.title, style = MaterialTheme.typography.titleMedium)
                        if (meta.author.isNotBlank()) Text(meta.author, style = MaterialTheme.typography.bodySmall)
                        val position = remember(meta, app.screen) { app.books.position(meta.id) }
                        Text("${meta.chapters} sections · ${position.chapter + 1}/${meta.chapters}", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { deleting = meta }) { Text("Delete") }
                    }
                }
            }
        }
    }
    deleting?.let { meta -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${meta.title}?") }, text = { Text("Removes this imported copy, reading position and character analysis.") }, confirmButton = { TextButton(onClick = { app.delete(meta.id); deleting = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable private fun ReaderScreen(app: ReaderApp) {
    val book = app.book ?: return
    val chapter = book.chapters[app.chapter]
    var toc by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(book.id, app.chapter) { list.scrollToItem(app.paragraph.coerceIn(0, chapter.paragraphs.lastIndex)) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            TextButton(onClick = { toc = true }) { Text("Contents · ${app.chapter + 1}/${book.chapters.size} · ${chapter.title}", maxLines = 2) }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(chapter.paragraphs) { index, text ->
                Text(text, Modifier.fillMaxWidth().clickable { app.paragraph = index; app.savePosition() }, fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 30.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { app.selectChapter(app.chapter - 1) }, enabled = app.chapter > 0) { Text("Previous") }
            TextButton(onClick = { app.selectChapter(app.chapter + 1) }, enabled = app.chapter < book.chapters.lastIndex) { Text("Next chapter") }
        }
    }
    if (toc) AlertDialog(onDismissRequest = { toc = false }, title = { Text("Contents") }, text = {
        LazyColumn { itemsIndexed(book.chapters) { index, entry -> TextButton(onClick = { app.selectChapter(index); toc = false }) { Text("${index + 1}. ${entry.title}") } } }
    }, confirmButton = { TextButton(onClick = { toc = false }) { Text("Close") } })
}
