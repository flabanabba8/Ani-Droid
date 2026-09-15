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
import com.geminireader.tts.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class ReaderApp : Application() {
    private val intentMutex = Mutex()
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
    var models by mutableStateOf(emptyList<String>())
    fun validateSettings(value: Settings): Settings {
        require(value.model.matches(Regex("[a-zA-Z0-9._/-]+")) && value.analysisModel.matches(Regex("[a-zA-Z0-9._/-]+"))) { "Enter valid model names" }
        require(value.narratorVoice in VoiceDirector.female + VoiceDirector.male) { "Select a supported narrator voice" }
        require(value.engine in listOf("vertex", "cloud", "gemini")) { "Select a supported engine" }
        if (value.engine == "vertex" && value.vertexProject.isNotBlank()) VertexEndpoint.generate(value, value.model)
        for (url in listOf(value.cloudUrl, value.geminiUrl, value.vertexUrl).filter { it.isNotBlank() }) {
            val parsed = url.toHttpUrl()
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) { "Base URLs must not contain credentials, queries or fragments" }
            require(parsed.isHttps || (BuildConfig.DEBUG && parsed.host in listOf("10.0.2.2", "127.0.0.1", "localhost"))) { "Use HTTPS (debug mock allows loopback HTTP)" }
        }
        return value.copy(prefetch = value.prefetch.coerceIn(1,8), withinPauseMs = value.withinPauseMs.coerceIn(0,2000), paragraphPauseMs = value.paragraphPauseMs.coerceIn(0,5000), fontSize = value.fontSize.coerceIn(14,32), cacheMb = value.cacheMb.coerceIn(32,2048))
    }
    fun saveSettings(value: Settings, test: Boolean = false) = task {
        val valid = validateSettings(value); playback.stop(); settingsStore.save(valid); settings = valid; status = "Settings saved"
        if (test) {
            val text = "Hello. Your reader is ready for the next chapter."
            playback.preview(VoiceDirector.direct(Segment(0, 0, text.length, text), valid, null, "", true))
        }
    }
    fun fetchModels(value: Settings) = task {
        val valid = validateSettings(value)
        val names = mutableListOf<String>(); var next = ""
        do {
            val vertex = valid.engine == "vertex"
            val endpoint = if (vertex) "${VertexEndpoint.base(valid)}/v1beta1/publishers/google/models" else "${valid.geminiUrl.trimEnd('/')}/v1beta/models"
            val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("pageSize", "100")
            if (next.isNotEmpty()) url.addQueryParameter("pageToken", next)
            if (vertex) { VertexEndpoint.generate(valid, valid.analysisModel); require(valid.vertexToken.isNotBlank()) { "Enter a Vertex OAuth token first" } }
            val response = playback.api.request(url.build().toString(), if (vertex) "" else valid.geminiKey.ifBlank { valid.apiKey }, token = if (vertex) valid.vertexToken else "", project = if (vertex) valid.vertexProject else "")
            names += (response["publisherModels"] ?: response["models"])?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content?.substringAfterLast('/') }
            next = response["nextPageToken"]?.jsonPrimitive?.content.orEmpty()
        } while (next.isNotBlank() && names.size < 1000)
        models = names.distinct().sorted(); status = "Fetched ${models.size} models"
    }
    override fun onCreate() {
        super.onCreate(); books = BookRepository(File(filesDir, "books")); settingsStore = SettingsStore(this); playback = PlaybackEngine(this)
        analyzer = CharacterAnalyzer(books, playback.api, scope)
        analyzer.onProgress = { id, ch, done, total -> scope.launch {
            if (playback.loading && playback.activeBookId == id && playback.activeChapter == ch)
                status = "Analyzing chapter: $done/$total batches saved…"
        } }
        playback.prepareChapter = { readingBook, readingChapter, s ->
            val paragraphs = readingBook.chapters[readingChapter].paragraphs
            val result = if (s.characterMode == "narrator") Analysis() else {
                status = "Analyzing chapter (reusing saved/in-progress batches)…"
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
    fun analyzeChapter() = task {
        val readingBook = book ?: return@task
        val readingChapter = chapter
        status = "Analyzing chapter (resuming saved batches)…"
        analyzer.retry(readingBook, readingChapter, settings)
        loadCharacters(); status = "Chapter analysis ready"
    }
    fun savePosition() { val id = book?.id ?: return; val pos = Position(chapter, paragraph); scope.launch(Dispatchers.IO) { books.position(id, pos) } }
    fun delete(id: String) = task { if (playback.activeBookId == id) playback.stop(); analyzer.cancelBook(id); withContext(Dispatchers.IO) { books.delete(id) }; if (book?.id == id) book = null; refresh() }
    fun handle(intent: Intent) = task {
        intentMutex.withLock {
        settings = settingsStore.flow.first()
        if (BuildConfig.DEBUG && intent.hasExtra("debug_vertex_project")) {
            val tokenFile = File(filesDir, "debug-vertex-token")
            val token = try { tokenFile.readText().trim() } finally { tokenFile.delete() }
            require(token.isNotBlank()) { "Missing debug Vertex token" }
            val updated = validateSettings(settings.copy(engine = "vertex", vertexProject = intent.getStringExtra("debug_vertex_project")!!,
                vertexLocation = intent.getStringExtra("debug_vertex_location") ?: "us-central1", vertexToken = token, vertexUrl = ""))
            playback.stop(); settingsStore.save(updated); settings = updated
            status = "Vertex credentials updated"
        }
        if (BuildConfig.DEBUG && intent.hasExtra("debug_mock")) {
            val url = intent.getStringExtra("debug_mock")!!
            settings = settings.copy(apiKey = "mock", geminiKey = "mock", oauthToken = "", project = "", cloudUrl = url, geminiUrl = url, vertexUrl = url, vertexProject = "mock-project", vertexToken = "mock", engine = intent.getStringExtra("debug_engine") ?: "vertex")
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
}
