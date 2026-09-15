package com.geminireader.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.geminireader.text.Segmenter
import com.geminireader.text.Segment
import com.geminireader.analysis.VoiceDirector
import com.geminireader.playback.BufferPolicy
import com.geminireader.playback.PlaybackEngine
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geminireader.ReaderApp
import com.geminireader.data.BookMeta
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable fun ReaderUi(app: ReaderApp) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) app.handle(Intent(Intent.ACTION_VIEW, uri)) }
    BackHandler(app.screen != "library") { app.screen = if (app.screen in listOf("characters", "pronunciation", "series")) "reader" else "library" }
    val dark = app.settings.theme == "dark" || (app.settings.theme == "system" && androidx.compose.foundation.isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect { (view.context as? Activity)?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !dark } }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xffa2d5bf)) else lightColorScheme(primary = Color(0xff245b4c), background = Color(0xfffaf8f1), surface = Color(0xfffaf8f1))) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { app.screen = "library" }) { Text("Gemini Reader") }
                    if (app.screen == "library") TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import book") }
                    TextButton(onClick = { app.screen = "settings" }) { Text("Settings") }
                }
                if (app.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (app.status.isNotBlank()) Text(app.status, Modifier.fillMaxWidth().clickable { app.status = "" }.background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp), style = MaterialTheme.typography.bodySmall)
                when { app.screen == "settings" -> SettingsScreen(app)
                    app.screen == "pronunciation" && app.book != null -> PronunciationScreen(app)
                    app.screen == "series" && app.book != null -> SeriesScreen(app)
                    app.screen == "characters" && app.book != null -> CharactersScreen(app)
                    app.screen == "reader" && app.book != null -> ReaderScreen(app)
                    else -> LibraryScreen(app) }
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
    var bookTools by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf<Segment?>(null) }
    var exporting by remember { mutableStateOf<PlaybackEngine.ExportSelection?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var removingPrepared by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { app.finishExport(it) }
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val playback = app.playback
    val rejected = remember(book.id, app.chapter, app.rejectionRevision) {
        app.rejectedPassages.list(book.id).filter { it.chapter == app.chapter }
    }
    val active = playback.active?.takeIf { playback.activeBookId == book.id && playback.activeChapter == app.chapter }
    LaunchedEffect(book.id, app.chapter) { list.scrollToItem(app.paragraph.coerceIn(0, chapter.paragraphs.lastIndex)) }
    LaunchedEffect(active?.paragraph, follow) { if (follow && active != null) list.animateScrollToItem(active.paragraph) }
    LaunchedEffect(book.id, app.chapter, list) {
        snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            if (!playback.speaking && !playback.loading) { app.paragraph = index; app.saveReadingPosition() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { toc = true }, modifier = Modifier.weight(1f)) { Text("${app.chapter + 1}/${book.chapters.size} · ${chapter.title}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                Box {
                    TextButton(onClick = { bookTools = true }) { Text("Book tools") }
                    DropdownMenu(expanded = bookTools, onDismissRequest = { bookTools = false }) {
                        DropdownMenuItem(text = { Text("Characters") }, onClick = { bookTools = false; app.screen = "characters" })
                        DropdownMenuItem(text = { Text("Pronunciation") }, onClick = { bookTools = false; app.screen = "pronunciation" })
                        DropdownMenuItem(text = { Text("Series voices") }, onClick = { bookTools = false; app.screen = "series" })
                        DropdownMenuItem(text = { Text("Export audio") }, enabled = !app.busy && !app.preparing, onClick = { bookTools = false; runCatching { app.exportChapter() }.onSuccess { exporting = it }.onFailure { app.status = it.message.orEmpty() } })
                        DropdownMenuItem(text = { Text(if (app.preparing) "Cancel preparation" else "Prepare chapter offline") }, onClick = { bookTools = false; if (app.preparing) app.cancelPreparation() else preparing = true })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(chapter.paragraphs) { index, text ->
                val annotated = buildAnnotatedString {
                    append(text)
                    if (active?.paragraph == index) {
                        addStyle(SpanStyle(background = Color(0xffe0eaca), color = Color(0xff292817)), active.start, active.end)
                        val offset = (playback.progress * active.text.length).toInt()
                        val sentence = Segmenter.sentences(active.text).firstOrNull { offset in it }
                        if (sentence != null) addStyle(SpanStyle(background = Color(0xffffd982), color = Color(0xff292817)), active.start + sentence.first, active.start + sentence.last + 1)
                    }
                    rejected.filter { it.segment.paragraph == index }.forEach { rejection ->
                        val segment = rejection.segment
                        if (segment.start >= 0 && segment.end <= text.length && segment.start < segment.end && text.substring(segment.start, segment.end) == segment.text)
                            addStyle(SpanStyle(background = Color(0xff8b1e2d), color = Color.White), segment.start, segment.end)
                    }
                }
                Column {
                if (rejected.any { it.segment.paragraph == index }) Text("Provider rejected this passage · ${rejected.filter { it.segment.paragraph == index }.map { it.reason }.distinct().joinToString()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Text(annotated, Modifier.fillMaxWidth().combinedClickable(onClick = { app.paragraph = index; playback.play(book, app.chapter, index) }, onLongClick = {
                    inspecting = active?.takeIf { it.paragraph == index } ?: Segmenter.dialogue(chapter.paragraphs).firstOrNull { it.paragraph == index && it.q != null } ?: Segment(index, 0, text.length, text)
                }), fontFamily = FontFamily.Serif, fontSize = app.settings.fontSize.sp, lineHeight = (app.settings.fontSize * 1.5f).sp)
                }
            }
        }
        if (active != null) Text("${playback.speaker} · estimated sentence timing", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall)
        if (rejected.isNotEmpty()) TextButton(onClick = { follow = false; app.scope.launch { list.animateScrollToItem(rejected.first().segment.paragraph.coerceIn(0, chapter.paragraphs.lastIndex)) } }) { Text("Show rejected passage (${rejected.size})", color = MaterialTheme.colorScheme.error) }
        if (playback.speechFailed && playback.activeBookId == book.id) TextButton(onClick = { playback.retrySpeech() }) { Text("Retry failed speech") }
        if (playback.activeBookId == book.id) Text(BufferPolicy.label(playback.readyMs), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = { playback.toggle() }) { Text(if (playback.activeBookId == book.id && playback.activeChapter == app.chapter && playback.player.playWhenReady && (playback.speaking || playback.loading || playback.player.mediaItemCount > 0)) "Pause" else "Play") }
            TextButton(onClick = { playback.changeSpeed(if (playback.speed >= 2f) .75f else playback.speed + .25f) }) { Text("${playback.speed}×") }
            TextButton(onClick = { follow = !follow }) { Text(if (follow) "Follow on" else "Follow off") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { app.selectChapter(app.chapter - 1) }, enabled = app.chapter > 0) { Text("Previous") }
            TextButton(onClick = { app.selectChapter(app.chapter + 1) }, enabled = app.chapter < book.chapters.lastIndex) { Text("Next chapter") }
        }
    }
    exporting?.let { selection -> AlertDialog(onDismissRequest = { exporting = null }, title = { Text("Export generated audio") }, text = { Text(selection.description + "\nExport itself requests no new speech. Normal playback buffering may continue.") }, confirmButton = {
        TextButton(onClick = { exporting = null; app.stageExport(selection) { exportPicker.launch("reader-chapter-${app.chapter + 1}.wav") } }) { Text("Save WAV") }
    }, dismissButton = { TextButton(onClick = { exporting = null }) { Text("Cancel") } }) }
    if (preparing) AlertDialog(onDismissRequest = { preparing = false }, title = { Text("Prepare this entire chapter?") }, text = { Column { Text("${chapter.title}: ${chapter.paragraphs.sumOf { it.length }} text characters. This analyzes the chapter and generates missing speech, which can incur charges. Audio is retained outside the disposable cache. Keep the app open; cancellation retains completed segments for retry. Changes to voices or pronunciations require preparing again. No other chapters are prepared."); TextButton(onClick = { preparing = false; removingPrepared = true }) { Text("Remove saved chapter audio…") } } }, confirmButton = { TextButton(onClick = { preparing = false; app.prepareOffline() }) { Text("Prepare chapter") } }, dismissButton = { TextButton(onClick = { preparing = false }) { Text("Cancel") } })
    if (removingPrepared) AlertDialog(onDismissRequest = { removingPrepared = false }, title = { Text("Remove prepared audio?") }, text = { Text("Deletes this chapter's retained audio and preparation manifest, not book text or analysis. Regenerating it may incur charges. Exported files are unaffected.") }, confirmButton = { TextButton(onClick = { removingPrepared = false; app.removePreparedChapter() }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removingPrepared = false }) { Text("Cancel") } })
    if (toc) AlertDialog(onDismissRequest = { toc = false }, title = { Text("Contents") }, text = {
        LazyColumn { itemsIndexed(book.chapters) { index, entry -> TextButton(onClick = { app.selectChapter(index); toc = false }) { Text("${index + 1}. ${entry.title}") } } }
    }, confirmButton = { TextButton(onClick = { toc = false }) { Text("Close") } })
    inspecting?.let { segment ->
        val line = app.analysis.lines.firstOrNull { it.q == segment.q }
        val character = app.cast.firstOrNull { it.id == line?.speaker }
        val speech = VoiceDirector.direct(segment, app.settings, character, line?.delivery.orEmpty(), true)
        AlertDialog(onDismissRequest = { inspecting = null }, title = { Text(character?.name ?: "Narrator") }, text = {
            LazyColumn {
                item { Text(segment.text) }; item { Text("Voice: ${speech.voice}\n${speech.prompt}", Modifier.padding(vertical = 16.dp)) }
                val quotes = Segmenter.dialogue(chapter.paragraphs).filter { it.paragraph == segment.paragraph && it.q != null }.distinctBy { it.q }
                if (quotes.size > 1 || segment.q == null) items(quotes) { quote -> TextButton(onClick = { inspecting = quote }) { Text("Inspect ${quote.q}: ${quote.text.take(60)}") } }
                if (segment.q != null) {
                    item { Text("Reassign this dialogue") }
                    item { TextButton(onClick = { app.reassign(segment.q, "unknown"); inspecting = null }) { Text("Narrator / unknown") } }
                    items(app.cast) { person -> TextButton(onClick = { app.reassign(segment.q, person.id); inspecting = null }) { Text(person.name) } }
                }
            }
        }, confirmButton = { TextButton(onClick = { inspecting = null }) { Text("Close") } })
    }
}
