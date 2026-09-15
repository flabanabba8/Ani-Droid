package com.geminireader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geminireader.data.*
import com.geminireader.importer.BookImporter
import com.geminireader.playback.PlaybackEngine
import com.geminireader.analysis.*
import com.geminireader.analysis.Character
import com.geminireader.text.*
import kotlinx.coroutines.*
import java.io.File

class ReaderApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var books: BookRepository
    var library by mutableStateOf(emptyList<BookMeta>())
    var book by mutableStateOf<Book?>(null)
    var chapter by mutableStateOf(0)
    var paragraph by mutableStateOf(0)
    var screen by mutableStateOf("library")
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var settings by mutableStateOf(Settings())
    lateinit var settingsStore: SettingsStore
    lateinit var playback: PlaybackEngine
    lateinit var analyzer: CharacterAnalyzer
    var cast by mutableStateOf(emptyList<Character>())
    var analysis by mutableStateOf(Analysis())
    override fun onCreate() {
        super.onCreate(); books = BookRepository(File(filesDir, "books")); settingsStore = SettingsStore(this); playback = PlaybackEngine(this)
        analyzer = CharacterAnalyzer(books, playback.api)
        playback.prepareChapter = { readingBook, readingChapter, s ->
            val paragraphs = readingBook.chapters[readingChapter].paragraphs
            val result = if (s.characterMode == "narrator") Analysis() else {
                status = "Analyzing…"
                try { analyzer.analyze(readingBook, readingChapter, s) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = "Character analysis unavailable; reading as narrator. ${e.message}"; Analysis() }
            }
            val roster = withContext(Dispatchers.IO) { analyzer.cast(readingBook.id) }
            if (book?.id == readingBook.id) { cast = roster; if (chapter == readingChapter) analysis = result }
            val byId = roster.associateBy { it.id }
            val lines = result.lines.associateBy { it.q }
            playback.speechFor = { segment, settings, end -> val line = lines[segment.q]; VoiceDirector.direct(segment, settings, byId[line?.speaker], line?.delivery.orEmpty(), end) }
            playback.speakerLabel = { segment -> byId[lines[segment.q]?.speaker]?.name ?: "Narrator" }
            if (s.characterMode == "narrator" || result.lines.isEmpty()) Segmenter.narration(paragraphs)
            else Segmenter.mergeUnknown(Segmenter.dialogue(paragraphs), paragraphs, result.lines.filter { it.speaker in byId }.map { it.q }.toSet())
        }
        scope.launch { settingsStore.flow.collect { settings = it } }; scope.launch { refresh() }
    }
    suspend fun refresh() { library = withContext(Dispatchers.IO) { books.list() } }
    fun task(action: suspend () -> Unit) = scope.launch {
        busy = true
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { status = e.message ?: "Operation failed" } finally { busy = false }
    }
    fun open(id: String) = task {
        val loaded = withContext(Dispatchers.IO) { books.load(id) to books.position(id) }
        book = loaded.first; chapter = loaded.second.chapter.coerceIn(0, loaded.first.chapters.lastIndex)
        paragraph = loaded.second.paragraph.coerceIn(0, loaded.first.chapters[chapter].paragraphs.lastIndex)
        screen = "reader"; status = ""
        loadCharacters()
    }
    suspend fun loadCharacters() { val id = book?.id ?: return; val ch = chapter; val values = withContext(Dispatchers.IO) { analyzer.cast(id) to (analyzer.cached(id, ch) ?: Analysis()) }; if (book?.id == id && chapter == ch) { cast = values.first; analysis = values.second } }
    fun selectChapter(index: Int) { playback.stop(); chapter = index; paragraph = 0; savePosition(); scope.launch { loadCharacters() } }
    fun saveCharacter(value: Character) = task { val id = book?.id ?: return@task; playback.stop(); cast = cast.map { if (it.id == value.id) value.copy(edited = true) else it }; withContext(Dispatchers.IO) { analyzer.saveCast(id, cast) }; status = "Character saved" }
    fun reassign(q: String, speaker: String) = task { val id = book?.id ?: return@task; playback.stop(); withContext(Dispatchers.IO) { analyzer.reassign(id, chapter, q, speaker) }; loadCharacters(); status = "Speaker updated" }
    fun analyzeWholeBook() = task {
        val readingBook = book ?: return@task
        for (index in readingBook.chapters.indices) { status = "Analyzing ${index + 1}/${readingBook.chapters.size}…"; analyzer.analyze(readingBook, index, settings) }
        loadCharacters(); status = "Book analysis complete"
    }
    fun savePosition() { val id = book?.id ?: return; val pos = Position(chapter, paragraph); scope.launch(Dispatchers.IO) { books.position(id, pos) } }
    fun delete(id: String) = task { if (playback.activeBookId == id) playback.stop(); withContext(Dispatchers.IO) { books.delete(id) }; if (book?.id == id) book = null; refresh() }
    fun handle(intent: Intent) = task {
        if (BuildConfig.DEBUG && intent.hasExtra("debug_mock")) {
            val url = intent.getStringExtra("debug_mock")!!
            settings = settings.copy(apiKey = "mock", geminiKey = "mock", cloudUrl = url, geminiUrl = url, engine = intent.getStringExtra("debug_engine") ?: "cloud")
            settingsStore.save(settings)
        }
        @Suppress("DEPRECATION")
        val uri = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else intent.data
        val debugName = if (BuildConfig.DEBUG) intent.getStringExtra("debug_import") else null
        if (uri != null || debugName != null) {
            status = "Importing…"
            val imported = withContext(Dispatchers.IO) {
                val importer = BookImporter(this@ReaderApp)
                val result = if (debugName != null) {
                    require(debugName.matches(Regex("[a-zA-Z0-9_.-]+"))) { "Invalid debug filename" }
                    importer.file(File(filesDir, "debug-import/$debugName"))
                } else importer.uri(uri!!)
                books.save(result.book, result.cover)
            }
            playback.stop(); book = imported; chapter = 0; paragraph = 0; screen = "reader"; refresh(); loadCharacters(); status = "Imported ${imported.title}"
        }
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("debug_book")?.let { id -> book = withContext(Dispatchers.IO) { books.load(id) }; screen = "reader" }
            if (intent.hasExtra("debug_chapter")) book?.let { chapter = intent.getIntExtra("debug_chapter", 0).coerceIn(0, it.chapters.lastIndex); paragraph = 0 }
            loadCharacters()
            if (intent.hasExtra("debug_play")) book?.let { paragraph = intent.getIntExtra("debug_play", 0).coerceIn(0, it.chapters[chapter].paragraphs.lastIndex); screen = "reader"; playback.play(it, chapter, paragraph) }
        }
    }
}
