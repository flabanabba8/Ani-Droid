package com.geminireader.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

@Composable fun ReaderUi(app: ReaderApp) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) app.handle(Intent(Intent.ACTION_VIEW, uri)) }
    var importMenu by remember { mutableStateOf(false) }
    var driveHelp by rememberSaveable { mutableStateOf(false) }
    BackHandler(app.screen != "library") { app.screen = if (app.screen in listOf("characters", "pronunciation", "series")) "reader" else "library" }
    val dark = app.settings.theme == "dark" || (app.settings.theme == "system" && androidx.compose.foundation.isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect { (view.context as? Activity)?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !dark } }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xffa2d5bf)) else lightColorScheme(primary = Color(0xff245b4c), background = Color(0xfffaf8f1), surface = Color(0xfffaf8f1))) {
        ImportReview(app)
        if (driveHelp) AlertDialog(
            onDismissRequest = { driveHelp = false },
            title = { Text("Import from Google Drive") },
            text = { Text("In the file picker, open the navigation menu and choose Google Drive, then select your account and book.\n\nIf Drive is missing, install or open the Google Drive app and sign in, then try again. An internet connection may be needed to download the book.\n\nPageCast keeps a local copy for reading offline. Your Drive file stays unchanged; reading progress is not synced to Drive.") },
            confirmButton = { TextButton(onClick = { driveHelp = false; picker.launch(arrayOf("*/*")) }) { Text("Browse Drive files") } },
            dismissButton = { TextButton(onClick = { driveHelp = false }) { Text("Cancel") } }
        )
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { app.screen = "library" }) { Text(androidx.compose.ui.res.stringResource(com.geminireader.R.string.app_name)) }
                    if (app.screen == "library") Box {
                        TextButton(enabled = !app.busy, onClick = { importMenu = true }) { Text("Import book") }
                        DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                            DropdownMenuItem(text = { Text("Browse files") }, onClick = { importMenu = false; picker.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text("From Google Drive") }, onClick = { importMenu = false; driveHelp = true })
                        }
                    }
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
    val chapterIndex = app.chapter
    val chapter = book.chapters[chapterIndex]
    var toc by remember { mutableStateOf(false) }
    var notesDialog by remember { mutableStateOf(false) }
    var referenceQuery by remember { mutableStateOf("") }
    var referenceDialog by remember { mutableStateOf(false) }
    var repairDialog by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableStateOf("30") }
    var sleepFade by remember { mutableStateOf(true) }
    var bookTools by remember { mutableStateOf(false) }
    var inspecting by remember(book.id, app.chapter) { mutableStateOf<Segment?>(null) }
    var editing by rememberSaveable(book.id, app.chapter) { mutableStateOf<Int?>(null) }
    var draft by rememberSaveable(book.id, app.chapter) { mutableStateOf("") }
    var original by rememberSaveable(book.id, app.chapter) { mutableStateOf("") }
    var showRewriteOriginal by remember(editing) { mutableStateOf(false) }
    var rewriting by remember { mutableStateOf(false) }
    var rewriteJob by remember { mutableStateOf<Job?>(null) }
    var rewriteMessage by rememberSaveable(book.id, app.chapter) { mutableStateOf("") }
    var exportWholeBook by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<PlaybackEngine.ExportSelection?>(null) }
    var queueDialog by remember { mutableStateOf(false) }
    var queueAll by remember { mutableStateOf(false) }
    var queueCount by remember { mutableStateOf("3") }
    var chargingOnly by remember { mutableStateOf(true) }
    var wifiOnly by remember { mutableStateOf(true) }
    var millionRate by remember { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    var removingPrepared by remember { mutableStateOf(false) }
    val audiobookPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { app.finishExport(it) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { app.finishExport(it) }
    val list = rememberLazyListState()
    val readerScope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    val playback = app.playback
    val rejected = remember(book.id, app.chapter, app.rejectionRevision) {
        app.rejectedPassages.list(book.id).filter { it.chapter == app.chapter && !Segmenter.isSceneBreak(chapter.paragraphs.getOrNull(it.segment.paragraph).orEmpty()) }
    }
    val rejectedByParagraph = remember(rejected) { rejected.groupBy { it.segment.paragraph } }
    val active = playback.active?.takeIf { playback.activeBookId == book.id && playback.activeChapter == app.chapter }
    val activeSentences = remember(active?.text) { active?.let { Segmenter.sentences(it.text) }.orEmpty() }
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
                        DropdownMenuItem(text = { Text("Playback recovery / repair list") }, onClick = { bookTools = false; repairDialog = true })
                        DropdownMenuItem(text = { Text("Bookmarks and quotes") }, onClick = { bookTools = false; notesDialog = true })
                        DropdownMenuItem(text = { Text("Bookmark current sentence") }, onClick = {
                            bookTools = false
                            val p = active?.paragraph ?: app.paragraph.coerceIn(0, chapter.paragraphs.lastIndex)
                            val text = active?.text ?: chapter.paragraphs[p]
                            val range = Segmenter.sentences(text).firstOrNull { (playback.progress * text.length).toInt() in it } ?: (0 until text.length)
                            val start = (active?.start ?: 0) + range.first
                            val end = ((active?.start ?: 0) + range.last + 1).coerceAtMost(chapter.paragraphs[p].length)
                            app.task { app.notes.save(book.id, com.geminireader.data.ReadingNote(chapter = chapterIndex, paragraph = p, start = start, end = end, quote = chapter.paragraphs[p].substring(start, end))); app.status = "Sentence bookmarked (estimated audio timing)" }
                        })
                        DropdownMenuItem(text = { Text("Sleep timer") }, onClick = { bookTools = false; sleepDialog = true })
                        DropdownMenuItem(text = { Text("Spoiler-safe character reference") }, onClick = { bookTools = false; referenceQuery = ""; referenceDialog = true })
                        DropdownMenuItem(text = { Text("Characters") }, onClick = { bookTools = false; app.screen = "characters" })
                        DropdownMenuItem(text = { Text("Pronunciation") }, onClick = { bookTools = false; app.screen = "pronunciation" })
                        DropdownMenuItem(text = { Text("Series voices") }, onClick = { bookTools = false; app.screen = "series" })
                        DropdownMenuItem(text = { Text("Prepare chapters overnight") }, onClick = { bookTools = false; queueDialog = true })
                        DropdownMenuItem(text = { Text("Export audio") }, enabled = !app.busy && !app.preparing, onClick = { bookTools = false; runCatching { app.exportChapter() }.onSuccess { exporting = it }.onFailure { app.status = it.message.orEmpty() } })
                        DropdownMenuItem(text = { Text(if (app.preparing) "Cancel preparation" else "Prepare chapter offline") }, onClick = { bookTools = false; if (app.preparing) app.cancelPreparation() else preparing = true })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(chapter.paragraphs) { index, text ->
                val paragraphRejections = rejectedByParagraph[index].orEmpty()
                val annotated = buildAnnotatedString {
                    append(text)
                    if (active?.paragraph == index) {
                        addStyle(SpanStyle(background = Color(0xffe0eaca), color = Color(0xff292817)), active.start, active.end)
                        val offset = (playback.progress * active.text.length).toInt()
                        val sentence = activeSentences.firstOrNull { offset in it }
                        if (sentence != null) addStyle(SpanStyle(background = Color(0xffffd982), color = Color(0xff292817)), active.start + sentence.first, active.start + sentence.last + 1)
                    }
                    paragraphRejections.forEach { rejection ->
                        val segment = rejection.segment
                        if (segment.start >= 0 && segment.end <= text.length && segment.start < segment.end && text.substring(segment.start, segment.end) == segment.text)
                            addStyle(SpanStyle(background = Color(0xff8b1e2d), color = Color.White), segment.start, segment.end)
                    }
                }
                Column {
                if (paragraphRejections.isNotEmpty()) {
                    Text("Provider rejected this passage · ${paragraphRejections.map { it.reason }.distinct().joinToString()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = !app.busy, onClick = {
                        editing = index; original = text; draft = text; rewriteMessage = ""
                    }) { Text("Rewrite rejected passage") }
                    TextButton(enabled = !app.busy && !app.preparing, onClick = {
                        app.paragraph = index; playback.stop(); playback.play(book, app.chapter, index)
                    }) { Text("Retry generation") }
                }
                var textLayout by remember(text) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                val playParagraph = { if (!Segmenter.isSceneBreak(text)) { app.paragraph = index; playback.play(book, app.chapter, index) } }
                val inspectParagraph = {
                    inspecting = active?.takeIf { it.paragraph == index } ?: Segmenter.dialogue(chapter.paragraphs).firstOrNull { it.paragraph == index && it.q != null } ?: Segment(index, 0, text.length, text)
                }
                Text(annotated, Modifier.fillMaxWidth().pointerInput(text, active, chapterIndex) {
                    detectTapGestures(onTap = { playParagraph() }, onLongPress = { inspectParagraph() }, onDoubleTap = { point ->
                        textLayout?.let { layout ->
                            val boundary = layout.getWordBoundary(layout.getOffsetForPosition(point))
                            referenceQuery = text.substring(boundary.start.coerceIn(0, text.length), boundary.end.coerceIn(0, text.length)).trim()
                            if (referenceQuery.isNotBlank()) referenceDialog = true
                        }
                    })
                }.semantics {
                    onClick("Play from paragraph") { playParagraph(); true }
                    onLongClick("Inspect passage") { inspectParagraph(); true }
                }, onTextLayout = { textLayout = it }, fontFamily = FontFamily.Serif, fontSize = app.settings.fontSize.sp, lineHeight = (app.settings.fontSize * 1.5f).sp)
                }
            }
        }
        if (playback.sleepMode != "off") TextButton(onClick = { sleepDialog = true }) {
            Text(if (playback.sleepMode == "duration") "Sleep in ${(playback.sleepRemainingMs + 59_999) / 60_000} min" else "Sleep at end of ${playback.sleepMode}")
        }
        if (active != null) Text("${playback.speaker} · estimated sentence timing", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall)
        if (rejected.isNotEmpty() && chapter.paragraphs.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = {
            follow = false
            val target = rejected.first().segment.paragraph.coerceIn(0, chapter.paragraphs.lastIndex)
            // Scroll animations require Compose's frame clock, not the application worker scope.
            readerScope.launch { list.animateScrollToItem(target) }
        }) { Text("Show rejected passage (${rejected.size})", color = MaterialTheme.colorScheme.error) }
        TextButton(enabled = !app.busy, onClick = { app.dismissRejections(book.id, rejected) }) { Text("Dismiss") }
        }
        if (playback.speechFailed && playback.activeBookId == book.id) TextButton(onClick = { playback.retrySpeech() }) { Text("Retry failed speech") }
        if (playback.activeBookId == book.id) Text(BufferPolicy.label(playback.readyMs), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { playback.seekBy(-15_000) }) { Text("−15s") }
            TextButton(onClick = { playback.seekSentence(false) }) { Text("Previous sentence") }
            TextButton(onClick = { playback.seekSentence(true) }) { Text("Next sentence") }
            TextButton(onClick = { playback.seekBy(15_000) }) { Text("+15s") }
        }
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
    if (notesDialog) NotesDialog(app, book, jump = { note ->
        val ch = note.chapter.coerceIn(0, book.chapters.lastIndex)
        val p = note.paragraph.coerceIn(0, book.chapters[ch].paragraphs.lastIndex)
        app.selectChapter(ch); app.paragraph = p; app.saveReadingPosition(); follow = false
        readerScope.launch { list.scrollToItem(p) }
    }) { notesDialog = false }
    if (referenceDialog) ReadReferenceDialog(book, app.chapter, active?.paragraph ?: app.paragraph, referenceQuery) { referenceDialog = false }
    if (queueDialog) AlertDialog(onDismissRequest = { queueDialog = false }, title = { Text("Prepare chapters") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row { Switch(queueAll, { queueAll = it }); Text("Whole book", Modifier.padding(8.dp)) }
            if (!queueAll) OutlinedTextField(queueCount, { queueCount = it.filter(Char::isDigit).take(4) }, label = { Text("Chapters starting here") })
            Row { Switch(chargingOnly, { chargingOnly = it }); Text("Only while charging", Modifier.padding(8.dp)) }
            Row { Switch(wifiOnly, { wifiOnly = it }); Text("Wi-Fi only (unmetered on Android 8)", Modifier.padding(8.dp)) }
            val chapters = if (queueAll) book.chapters.indices.toList() else (app.chapter..minOf(book.chapters.lastIndex, app.chapter + (queueCount.toIntOrNull() ?: 1).coerceAtLeast(1) - 1)).toList()
            val chars = chapters.sumOf { ch -> book.chapters[ch].paragraphs.sumOf { it.length.toLong() } }
            Text("${chapters.size} chapters · $chars source characters")
            OutlinedTextField(millionRate, { millionRate = it.take(16) }, label = { Text("Your USD rate per million characters (optional)") })
            val rate = millionRate.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            Text(if (app.settings.engine in listOf("kokoro", "android") && app.settings.characterMode == "narrator") "Local speech: no PageCast API charge." else if (rate != null) "Speech estimate: $${"%.2f".format(chars * rate / 1_000_000)}; excludes analysis, retries and provider differences." else "Cost unknown. Cloud speech and analysis can incur charges.")
            Text("Android schedules background work. Completed audio survives interruptions. Changing settings requires requeueing. Existing prepared audio is reused.")
            com.geminireader.playback.PreparationQueue.read(app)?.let { Text("Saved queue: ${it.chapters.size} chapters remaining") }
            Button(enabled = !app.preparing && !app.busy, onClick = { app.task { playback.stop(); com.geminireader.playback.PreparationQueue.schedule(app, com.geminireader.playback.PreparationRequest(book.id, chapters, com.geminireader.playback.PreparationQueue.fingerprint(app.settings), chargingOnly, wifiOnly)); queueDialog = false } }) { Text("Queue selected chapters") }
            TextButton(onClick = { app.cancelPreparation(); queueDialog = false }) { Text("Pause queue") }
        }
    }, confirmButton = { TextButton(onClick = { queueDialog = false }) { Text("Close") } })
    if (repairDialog) AlertDialog(onDismissRequest = { repairDialog = false }, title = { Text("Playback recovery") }, text = {
        LazyColumn {
            item { Row { Switch(app.settings.skipFailedSpeech, { enabled -> app.task { app.settingsStore.save(app.settings.copy(skipFailedSpeech = enabled)) } }); Text("Skip failed speech after retries", Modifier.padding(8.dp)) } }
            item { Text("Skipped text is saved below. No provider is changed and no text is rewritten.") }
            val skipped = app.skippedPassages.list(book.id)
            if (skipped.isEmpty()) item { Text("No skipped passages.") }
            items(skipped) { entry ->
                Text("Chapter ${entry.chapter + 1}, paragraph ${entry.segment.paragraph + 1}: ${entry.segment.text.take(160)}")
                TextButton(onClick = { repairDialog = false; app.selectChapter(entry.chapter); app.paragraph = entry.segment.paragraph; playback.play(book, entry.chapter, entry.segment.paragraph) }) { Text("Go to passage and retry") }
            }
        }
    }, confirmButton = { TextButton(onClick = { repairDialog = false }) { Text("Close") } })
    if (sleepDialog) AlertDialog(onDismissRequest = { sleepDialog = false }, title = { Text("Sleep timer") }, text = {
        Column {
            Text("Current: ${playback.sleepMode}")
            OutlinedTextField(sleepMinutes, { sleepMinutes = it.filter(Char::isDigit).take(3) }, label = { Text("Minutes (1–240)") })
            Row { Switch(sleepFade, { sleepFade = it }); Text("Fade during last 15 seconds", Modifier.padding(8.dp)) }
            TextButton(onClick = { playback.setSleep("duration", sleepMinutes.toIntOrNull() ?: 30, sleepFade); sleepDialog = false }) { Text("Start timer") }
            TextButton(onClick = { playback.setSleep("paragraph"); sleepDialog = false }) { Text("End of paragraph") }
            TextButton(onClick = { playback.setSleep("chapter"); sleepDialog = false }) { Text("End of chapter") }
            TextButton(onClick = { playback.setSleep("off"); sleepDialog = false }) { Text("Turn off") }
        }
    }, confirmButton = { TextButton(onClick = { sleepDialog = false }) { Text("Close") } })
    exporting?.let { selection -> AlertDialog(onDismissRequest = { exporting = null }, title = { Text("Export audio") }, text = {
        Column {
            Text(selection.description + "\nExport requests no new speech. Compact audiobook export requires complete prepared chapters. Chapter-marker support varies by player.")
            Row { Switch(exportWholeBook, { exportWholeBook = it }); Text("Whole book (M4A/M4B)", Modifier.padding(8.dp)) }
            TextButton(onClick = { exporting = null; app.stageAudiobook(exportWholeBook) { audiobookPicker.launch("PageCast-audiobook.m4b") } }) { Text("Save M4B with chapters and cover") }
            TextButton(onClick = { exporting = null; app.stageAudiobook(exportWholeBook) { audiobookPicker.launch("PageCast-audio.m4a") } }) { Text("Save M4A") }
        }
    }, confirmButton = { TextButton(onClick = { exporting = null; app.stageExport(selection) { exportPicker.launch("reader-chapter-${app.chapter + 1}.wav") } }) { Text("Save chapter WAV") } }, dismissButton = { TextButton(onClick = { exporting = null }) { Text("Cancel") } }) }
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
                if (Segmenter.isSceneBreak(chapter.paragraphs[segment.paragraph])) item { Text("Scene break: skipped during speech.") }
                if (rejected.any { it.segment.paragraph == segment.paragraph }) {
                    item { TextButton(enabled = !app.busy, onClick = {
                        app.dismissRejections(book.id, rejected.filter { it.segment.paragraph == segment.paragraph })
                        inspecting = null
                    }) { Text("Dismiss rejection") } }
                    item { TextButton(enabled = !app.busy, onClick = {
                        editing = segment.paragraph; original = chapter.paragraphs[segment.paragraph]; draft = original; rewriteMessage = ""; inspecting = null
                    }) { Text("Rewrite rejected passage") } }
                    item { TextButton(enabled = !app.busy && !app.preparing, onClick = {
                        app.paragraph = segment.paragraph; inspecting = null; playback.stop(); playback.play(book, app.chapter, segment.paragraph)
                    }) { Text("Retry generation") } }
                }
                item { TextButton(enabled = !app.busy, onClick = {
                    editing = segment.paragraph
                    original = chapter.paragraphs[segment.paragraph]
                    draft = original
                    rewriteMessage = ""
                    inspecting = null
                }) { Text("Edit passage") } }
                item { TextButton(onClick = { app.task { app.notes.save(book.id, com.geminireader.data.ReadingNote(chapter = chapterIndex, paragraph = segment.paragraph, start = segment.start, end = segment.end, quote = segment.text)); app.status = "Quote saved" }; inspecting = null }) { Text("Save quote / bookmark") } }
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
    editing?.let { index ->
        AlertDialog(onDismissRequest = { if (!app.busy) { rewriteJob?.cancel(); editing = null } }, title = { Text("Edit passage") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Changes are saved to this imported book and used for speech. Saving stops playback.")
                if (rejected.any { it.segment.paragraph == index }) {
                    Text("Suggest milder wording with ${app.settings.analysisModel}, then review before saving. May incur API costs; speech may still be rejected.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !app.busy && !rewriting && draft.isNotBlank(), onClick = {
                        rewriting = true; rewriteMessage = ""
                        val source = draft; val ch = app.chapter
                        rewriteJob = readerScope.launch {
                            try {
                                val suggestion = app.rewritePassage(book, ch, index, original, source)
                                draft = suggestion
                                rewriteMessage = if (suggestion == source) "The model suggested no changes. You can edit manually." else "Rewrite ready. Review the text, then Save to apply it."
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { rewriteMessage = e.message ?: "Rewrite failed. Your draft is unchanged." }
                            finally { rewriting = false }
                        }
                    }) { Text(if (rewriting) "Rewriting…" else "Suggest milder wording") }
                    if (rewriting) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (rewriteMessage.isNotBlank()) Text(rewriteMessage, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Passage text") },
                    enabled = !app.busy && !rewriting, isError = draft.isBlank(), minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth(), supportingText = { if (draft.isBlank()) Text("Enter passage text") })
                if (draft != original) {
                    TextButton(onClick = { showRewriteOriginal = !showRewriteOriginal }) { Text(if (showRewriteOriginal) "Hide original" else "Show original") }
                    if (showRewriteOriginal) Text(original, style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = {
            TextButton(enabled = !app.busy && !rewriting && draft.isNotBlank() && draft != original, onClick = {
                app.editPassage(book.id, app.chapter, index, original, draft) { editing = null }
            }) { Text("Save") }
        }, dismissButton = { TextButton(enabled = !app.busy, onClick = { rewriteJob?.cancel(); editing = null }) { Text("Cancel") } })
    }
}
