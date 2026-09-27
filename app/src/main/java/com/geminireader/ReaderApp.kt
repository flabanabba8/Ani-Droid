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
    val androidTts by lazy { AndroidTtsClient(this) }
    val kokoro by lazy { KokoroTtsClient(this) }
    val spending by lazy { SpendingTracker(File(filesDir, "spending.json")) }
    lateinit var books: BookRepository
    val rejectedPassages by lazy { RejectedPassages(books) }
    var rejectionRevision by mutableStateOf(0)
    fun dismissRejections(bookId: String, rejected: List<RejectedPassage>) = task {
        withContext(Dispatchers.IO) { rejectedPassages.dismiss(bookId, rejected) }
        rejectionRevision++
    }
    suspend fun bookAudio(bookId: String, chapter: Int, segment: Segment, generate: suspend () -> File): File {
        try {
            val title = book?.takeIf { it.id == bookId }?.title
                ?: withContext(Dispatchers.IO) { books.load(bookId).title }
            val file = withContext(SpendingBook(bookId, title)) { generate() }
            withContext(Dispatchers.IO) { rejectedPassages.resolved(bookId, chapter, segment) }
            withContext(Dispatchers.Main) { rejectionRevision++ }
            return file
        } catch (e: MissingAudio) {
            if (e.blocked) withContext(NonCancellable) {
                withContext(Dispatchers.IO) { rejectedPassages.record(bookId, chapter, segment, e.reason) }
                withContext(Dispatchers.Main) { rejectionRevision++ }
            }
            throw e
        }
    }
    lateinit var performances: PerformanceRepository
    lateinit var offline: OfflineChapters
    var preparing by mutableStateOf(false)
    private var preparationJob: Job? = null
    fun cancelPreparation() { preparationJob?.cancel() }
    fun removePreparedChapter() = task {
        val target = book ?: return@task; val ch = chapter
        preparationJob?.cancelAndJoin(); playback.stop()
        withContext(Dispatchers.IO) { check(offline.folder(target.id, ch).deleteRecursively()) { "Could not remove prepared audio" } }
        status = "Prepared chapter audio removed; it can be generated again"
    }
    fun prepareOffline() {
        if (preparing || savingPassage) return
        val target = book ?: return
        val ch = chapter; val s = settings
        playback.stop(); preparing = true
        preparationJob = scope.launch {
            try {
                status = "Preparing chapter: analyzing…"
                val segments = playback.prepareChapter(target, ch, s)
                require(!status.startsWith("Character analysis unavailable")) { "Analysis failed; retry analysis or explicitly choose Narrator mode before preparing" }
                val direct = playback.speechFor; val speaker = playback.speakerLabel
                val plan = PreparedChapter(offline.signature(target, ch, s), segments.mapIndexed { index, segment ->
                    val speech = direct(segment, s, segments.getOrNull(index + 1)?.paragraph != segment.paragraph)
                    PreparedLine(segment, speech, speaker(segment), "${AudioCache.key(speech, s)}.wav")
                })
                withContext(Dispatchers.IO) { offline.save(target.id, ch, plan) }
                val engine = TtsEngines.create(s, playback.api, kokoro, androidTts)
                for ((index, line) in plan.lines.withIndex()) {
                    ensureActive(); status = "Preparing chapter: ${index + 1}/${plan.lines.size} segments"
                    withContext(Dispatchers.IO) {
                        val destination = offline.audio(target.id, ch, line.file)
                        if (!runCatching { WavExport.inspect(destination); true }.getOrDefault(false)) {
                            val file = bookAudio(target.id, ch, line.segment) { playback.cache.get(line.speech, s, engine) }
                            val temp = File(destination.parentFile, "${destination.name}.tmp")
                            try { file.copyTo(temp, overwrite = true); WavExport.inspect(temp); check(temp.renameTo(destination)) } finally { temp.delete() }
                        }
                    }
                }
                withContext(Dispatchers.IO) { offline.save(target.id, ch, plan.copy(complete = true)) }
                status = if (plan.signature == offline.signature(target, ch, settings)) "Chapter ready offline — full chapter export available" else "Preparation saved with older settings; prepare again to update"
            } catch (e: CancellationException) { status = "Preparation canceled; completed segments retained for retry"; throw e }
            catch (e: Exception) { status = "Chapter preparation failed: ${e.message}. Completed segments retained." }
            finally { preparing = false; playback.cache.pinned.clear() }
        }
    }
    fun exportChapter(): PlaybackEngine.ExportSelection {
        val target = book ?: error("Open a book")
        val ready = offline.ready(target, chapter, settings)
        return if (ready != null) PlaybackEngine.ExportSelection(ready.lines.map { offline.audio(target.id, chapter, it.file) }, "Complete prepared chapter: ${ready.lines.size} segments, original 1× speed.") else playback.exportSelection()
    }
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
    fun rememberBook(id: String) { atomicWrite(File(filesDir, "last-book.txt"), id) }
    private var pendingExport: File? = null
    fun stageExport(selection: PlaybackEngine.ExportSelection, ready: () -> Unit) = task {
        val file = File(cacheDir, "reader-export.wav")
        status = "Preparing WAV export…"
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            try { file.outputStream().use { WavExport.write(selection.files, it) { context.ensureActive() } } }
            catch (e: Exception) { file.delete(); throw e }
        }
        pendingExport = file; status = "Choose where to save the audio"; ready()
    }
    fun finishExport(uri: Uri?) = task {
        // The document picker can outlive our process; the completed staging file is recoverable.
        val file = pendingExport ?: File(cacheDir, "reader-export.wav").takeIf { it.isFile } ?: return@task
        try {
            if (uri != null) withContext(Dispatchers.IO) {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "Could not open export destination" }.use { output -> file.inputStream().use { it.copyTo(output) } }
            }
            status = if (uri == null) "Export canceled" else "Audio exported"
        } finally { file.delete(); pendingExport = null }
    }
    fun validateSettings(value: Settings): Settings {
        require(value.model.matches(Regex("[a-zA-Z0-9._/-]+")) && value.analysisModel.matches(Regex("[a-zA-Z0-9._/-]+"))) { "Enter valid model names" }
        require(value.narratorVoice in VoiceDirector.female + VoiceDirector.male) { "Select a supported narrator voice" }
        require(value.engine in Settings.engines) { "Select a supported engine" }
        if (value.engine == "android") {
            require(value.androidTtsEngine.isNotBlank() && value.androidTtsVoice.isNotBlank()) { "Select an installed Android speech engine and voice." }
            require(value.characterMode == "narrator") { "Android TTS currently supports Narrator mode." }
        }
        if (value.engine == "fish") {
            require(FishVoices.valid(value.fishVoice)) { "Enter a valid Fish voice ID" }
            require(value.fishAnalysisEngine in listOf("groq", "vertex", "gemini")) { "Select a text analysis provider" }
            require(value.groqTextModel in GroqAnalysis.models) { "Select a Groq analysis model" }
            require(value.characterMode in listOf("narrator", "distinct")) { "Select Narrator or Distinct mode" }
        }
        if (value.engine == "speechify") {
            require(SpeechifyVoices.valid(value.speechifyVoice)) { "Enter a valid Speechify voice ID" }
            require(value.speechifyAnalysisEngine in listOf("groq", "vertex", "gemini")) { "Select a text analysis provider" }
            require(value.groqTextModel in GroqAnalysis.models) { "Select a Groq analysis model" }
            require(value.characterMode in listOf("narrator", "distinct")) { "Select Narrator or Distinct mode" }
        }
        if (value.engine == "inworld") {
            require(InworldVoices.valid(value.inworldVoice)) { "Enter a valid Inworld voice ID" }
            require(value.inworldAnalysisEngine in listOf("groq", "vertex", "gemini")) { "Select a text analysis provider" }
            require(value.groqTextModel in GroqAnalysis.models) { "Select a Groq analysis model" }
            require(value.characterMode in listOf("narrator", "distinct")) { "Select Narrator or Distinct mode" }
        }
        if (value.engine == "deepgram") {
            require(DeepgramVoices.valid(value.deepgramVoice)) { "Enter a valid Deepgram voice ID" }
            require(value.deepgramAnalysisEngine in listOf("groq", "vertex", "gemini")) { "Select a text analysis provider" }
            require(value.groqTextModel in GroqAnalysis.models) { "Select a Groq analysis model" }
            require(value.characterMode in listOf("narrator", "distinct")) { "Select Narrator or Distinct mode" }
        }
        if (value.engine == "cartesia") {
            require(CartesiaVoices.valid(value.cartesiaVoice)) { "Enter a valid Cartesia voice ID" }
            require(value.cartesiaModel in CartesiaVoices.models) { "Select an Cartesia model" }
            require(value.cartesiaAnalysisEngine in listOf("groq", "vertex", "gemini")) { "Select a text analysis provider" }
            require(value.groqTextModel in GroqAnalysis.models) { "Select a Groq analysis model" }
            require(value.characterMode in listOf("narrator", "distinct")) { "Select Narrator or Distinct mode" }
        }
        if (value.engine == "elevenlabs") {
            require(ElevenVoices.valid(value.elevenVoice)) { "Enter a valid ElevenLabs voice ID" }
            require(value.elevenModel in ElevenVoices.models) { "Select an ElevenLabs model" }
            require(value.elevenAnalysisEngine in listOf("groq", "vertex", "gemini")) { "Select a text analysis provider" }
            require(value.groqTextModel in GroqAnalysis.models) { "Select a Groq analysis model" }
            require(value.characterMode in listOf("narrator", "distinct")) { "Select Narrator or Distinct mode" }
        }
        if (value.engine == "groq") {
            require(value.groqTextModel in GroqAnalysis.models) { "Select a Groq analysis model" }
            require(value.groqModel in com.geminireader.analysis.GroqVoices.models) { "Select a Groq model" }
            require(value.groqVoice in com.geminireader.analysis.GroqVoices.namesFor(value.groqModel)) { "Select a Groq voice" }
            require(value.groqAnalysisEngine in listOf("groq", "vertex", "gemini")) { "Select a text analysis provider" }
            require(value.characterMode in listOf("narrator", "distinct")) { "Groq supports Narrator or Distinct mode" }
        }
        if (value.engine == "kokoro") {
            require(value.kokoroVoice in KokoroVoices.names) { "Select a Kokoro narrator voice" }
            require(value.kokoroAnalysisEngine in listOf("vertex", "gemini")) { "Select a text analysis provider" }
            require(value.characterMode in listOf("narrator", "distinct")) { "Kokoro supports Narrator or Distinct mode" }
        }
        VertexAuth.validate(value)
        if (value.engine == "vertex" && value.vertexProject.isNotBlank()) VertexEndpoint.generate(value, value.model)
        for (url in listOf(value.cloudUrl, value.geminiUrl, value.vertexUrl).filter { it.isNotBlank() }) {
            val parsed = url.toHttpUrl()
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) { "Base URLs must not contain credentials, queries or fragments" }
            require(parsed.isHttps || (BuildConfig.DEBUG && parsed.host in listOf("10.0.2.2", "127.0.0.1", "localhost"))) { "Use HTTPS (debug mock allows loopback HTTP)" }
        }
        return value.copy(prefetch = value.prefetch.coerceIn(1,8), bufferSeconds = value.bufferSeconds.coerceIn(15,600), withinPauseMs = value.withinPauseMs.coerceIn(0,2000), paragraphPauseMs = value.paragraphPauseMs.coerceIn(0,5000), fontSize = value.fontSize.coerceIn(14,32), cacheMb = value.cacheMb.coerceIn(32,2048))
    }
    fun setTheme(theme: String) = task {
        require(theme in listOf("dark", "light", "system"))
        val updated = settings.copy(theme = theme)
        settingsStore.save(updated); settings = updated
    }
    fun saveSettings(value: Settings, test: Boolean = false) = task {
        val valid = validateSettings(value)
        if (settings.engine == "kokoro" && valid.engine != "kokoro") {
            preparationJob?.cancelAndJoin(); playback.stopAndJoin(); kokoro.release()
        } else if (settings.engine == "android" && valid.engine != "android") {
            preparationJob?.cancelAndJoin(); playback.stopAndJoin(); androidTts.release()
        } else playback.stop()
        settingsStore.save(valid); settings = valid; status = "Settings saved"
        if (test) {
            val text = "Hello. Your reader is ready for the next chapter."
            playback.preview(VoiceDirector.direct(Segment(0, 0, text.length, text), valid, null, "", true))
        }
    }
    fun deleteKokoro() = task {
        preparationJob?.cancelAndJoin(); playback.stopAndJoin(); kokoro.deleteDownloaded()
        status = "Kokoro downloads deleted"
    }
    fun fetchModels(value: Settings) = task {
        val valid = validateSettings(value)
        val names = mutableListOf<String>(); var next = ""
        do {
            val vertex = valid.textEngine == "vertex"
            val endpoint = if (vertex) "${VertexEndpoint.base(valid)}/v1beta1/publishers/google/models" else "${valid.geminiUrl.trimEnd('/')}/v1beta/models"
            val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("pageSize", "100")
            if (next.isNotEmpty()) url.addQueryParameter("pageToken", next)
            if (vertex) VertexEndpoint.generate(valid, valid.analysisModel)
            val response = if (vertex) VertexAuth.request(playback.api, valid, url.build().toString())
                else playback.api.request(url.build().toString(), valid.geminiKey.ifBlank { valid.apiKey })
            names += (response["publisherModels"] ?: response["models"])?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content?.substringAfterLast('/') }
            next = response["nextPageToken"]?.jsonPrimitive?.content.orEmpty()
        } while (next.isNotBlank() && names.size < 1000)
        models = names.distinct().sorted(); status = "Fetched ${models.size} models"
    }
    override fun onCreate() {
        super.onCreate(); books = BookRepository(File(filesDir, "books")); performances = PerformanceRepository(filesDir); offline = OfflineChapters(books, performances); settingsStore = SettingsStore(this); playback = PlaybackEngine(this)
        analyzer = CharacterAnalyzer(books, playback.api, scope)
        analyzer.onProgress = { id, ch, done, total -> scope.launch {
            if (playback.loading && playback.activeBookId == id && playback.activeChapter == ch)
                status = "Analyzing chapter: $done/$total batches saved…"
        } }
        playback.prepareChapter = prepare@ { readingBook, readingChapter, s ->
            val prepared = withContext(Dispatchers.IO) { offline.ready(readingBook, readingChapter, s) }
            if (prepared != null) {
                val entries = prepared.lines.associateBy { it.segment }
                playback.speechFor = { segment, _, _ -> entries.getValue(segment).speech }
                playback.speakerLabel = { segment -> entries.getValue(segment).speaker }
                return@prepare prepared.lines.map { it.segment }
            }
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
            val pronunciation = performances.applicable(readingBook.id)
            val link = performances.link(readingBook.id)
            val profiles = performances.series().firstOrNull { it.id == link.series }?.profiles.orEmpty().associateBy { it.id }
            playback.speechFor = { segment, settings, end ->
                val line = lines[segment.q]
                val original = byId[line?.speaker]
                val profile = profiles[link.voices[original?.id]]
                val person = if (profile == null) original else original?.copy(voice = profile.voice.ifBlank { original.voice }, voiceStyle = profile.style)
                val speech = VoiceDirector.direct(segment, settings, person, line?.delivery.orEmpty(), end)
                val spoken = PronunciationRules.apply(speech.text, pronunciation)
                require(spoken.toByteArray().size <= 4000) { "Pronunciation replacements made this segment too long; shorten the spoken replacements" }
                speech.copy(text = spoken)
            }
            playback.speakerLabel = { segment -> byId[lines[segment.q]?.speaker]?.name ?: "Narrator" }
            val segments = if (s.characterMode == "narrator" || result.lines.isEmpty()) Segmenter.narration(paragraphs)
            else Segmenter.mergeUnknown(Segmenter.dialogue(paragraphs), paragraphs, result.lines.filter { it.speaker in byId }.map { it.q }.toSet())
            when (s.engine) {
                "kokoro" -> segments.flatMap { Segmenter.sentenceChunks(it) }
                "android" -> segments.flatMap { Segmenter.sentenceChunks(it, maxChars = 1000) }
                "fish" -> segments.flatMap { Segmenter.sentenceChunks(it, maxChars = 1000) }
                "speechify" -> segments.flatMap { Segmenter.sentenceChunks(it, maxChars = 1000) }
                "inworld" -> segments.flatMap { Segmenter.sentenceChunks(it, maxChars = 1000) }
                "deepgram" -> segments.flatMap { Segmenter.sentenceChunks(it, maxChars = 1000) }
                "cartesia" -> segments.flatMap { Segmenter.sentenceChunks(it, maxChars = 1000) }
                "elevenlabs" -> segments.flatMap { Segmenter.sentenceChunks(it, maxChars = 1000) }
                "groq" -> segments.flatMap { Segmenter.sentenceChunks(it, maxChars = 200) }
                else -> segments
            }
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
        rememberBook(id)
        loadCharacters()
    }
    suspend fun loadCharacters() { val id = book?.id ?: return; val ch = chapter; val values = withContext(Dispatchers.IO) { analyzer.cast(id) to (analyzer.cached(id, ch) ?: Analysis()) }; if (book?.id == id && chapter == ch) { cast = values.first; analysis = values.second } }
    fun selectChapter(index: Int) { playback.stop(); chapter = index; paragraph = 0; savePosition(); scope.launch { loadCharacters() } }
    suspend fun rewritePassage(target: Book, ch: Int, index: Int, original: String, draft: String): String {
        val current = withContext(Dispatchers.IO) { books.load(target.id) }
        require(current.chapters.getOrNull(ch)?.paragraphs?.getOrNull(index) == original) { "Passage changed; reopen the editor before rewriting" }
        return withContext(SpendingBook(target.id, target.title)) { PassageRewriter(playback.api).rewrite(draft, settings) }
    }
    var savingPassage by mutableStateOf(false)
        private set
    fun editPassage(id: String, ch: Int, index: Int, original: String, replacement: String, saved: () -> Unit) = task {
        require(replacement.isNotBlank()) { "Passage cannot be empty" }
        if (savingPassage) return@task
        savingPassage = true
        try {
            preparationJob?.cancelAndJoin()
            playback.stopAndJoin()
            analyzer.cancelBook(id)
            val updated = withContext(Dispatchers.IO) { books.editPassage(id, ch, index, original, replacement) }
            if (book?.id == id) {
                book = updated
                loadCharacters()
            }
            rejectionRevision++
            status = "Passage saved. Play to read the updated text."
            saved()
        } finally { savingPassage = false }
    }
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
    fun savePosition() { val id = book?.id ?: return; books.position(id, Position(chapter, paragraph)) }
    fun saveReadingPosition() { val id = book?.id ?: return; val pos = Position(chapter, paragraph); scope.launch(Dispatchers.IO) { books.readingPosition(id, pos) } }
    fun delete(id: String) = task { preparationJob?.cancelAndJoin(); if (playback.activeBookId == id) playback.stop(); analyzer.cancelBook(id); withContext(Dispatchers.IO) { books.delete(id) }; if (book?.id == id) book = null; refresh() }
    fun handle(intent: Intent) = task {
        intentMutex.withLock {
        settings = settingsStore.flow.first()
        if (book == null && intent.action == Intent.ACTION_MAIN && !intent.hasExtra("debug_import") && !intent.hasExtra("debug_book")) {
            val last = runCatching { File(filesDir, "last-book.txt").readText().trim() }.getOrNull()
            if (last != null) runCatching {
                val loaded = books.load(last); val saved = books.position(last)
                book = loaded; chapter = saved.chapter.coerceIn(0, loaded.chapters.lastIndex)
                paragraph = saved.paragraph.coerceIn(0, loaded.chapters[chapter].paragraphs.lastIndex); screen = "reader"
                loadCharacters()
            }
        }
        if (BuildConfig.DEBUG && intent.hasExtra("debug_analysis_model")) {
            val updated = validateSettings(settings.copy(analysisModel = intent.getStringExtra("debug_analysis_model")!!))
            playback.stop(); settingsStore.save(updated); settings = updated
            status = "Analysis model updated"
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_broker", false)) {
            val file = File(filesDir, "debug-broker.json")
            val config = try { json.parseToJsonElement(file.readText()).jsonObject } finally { file.delete() }
            val updated = validateSettings(settings.copy(engine = "vertex", vertexUrl = "", vertexToken = "", vertexProject = config.getValue("project").jsonPrimitive.content,
                vertexBrokerUrl = config.getValue("url").jsonPrimitive.content, vertexBrokerPin = config.getValue("pin").jsonPrimitive.content,
                vertexBrokerSecret = config.getValue("secret").jsonPrimitive.content))
            playback.stop(); settingsStore.save(updated); settings = updated; status = "Automatic Vertex renewal configured"
        }
        if (BuildConfig.DEBUG && intent.hasExtra("debug_vertex_project")) {
            val tokenFile = File(filesDir, "debug-vertex-token")
            val token = try { tokenFile.readText().trim() } finally { tokenFile.delete() }
            require(token.isNotBlank()) { "Missing debug Vertex token" }
            val updated = validateSettings(settings.copy(engine = "vertex", vertexProject = intent.getStringExtra("debug_vertex_project")!!,
                vertexLocation = intent.getStringExtra("debug_vertex_location") ?: "us-central1", vertexToken = token, vertexUrl = "",
                vertexBrokerUrl = "", vertexBrokerPin = "", vertexBrokerSecret = ""))
            playback.stop(); settingsStore.save(updated); settings = updated
            status = "Vertex credentials updated"
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_groq_analysis", false)) {
            val updated = settings.copy(groqAnalysisEngine = "groq",
                characterMode = if (settings.engine == "groq") "distinct" else settings.characterMode)
            settingsStore.save(updated); settings = updated
            status = "Groq character analysis enabled"
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_fish_key", false)) {
            val file = File(filesDir, "debug-fish-key")
            val key = try { file.readText().trim() } finally { file.delete() }
            require(key.isNotBlank() && !key.any { it.isWhitespace() }) { "Invalid Fish key" }
            val updated = settings.copy(fishApiKey = key)
            settingsStore.save(updated); settings = updated
            File(filesDir, "debug-fish-paired").writeText("ok")
            status = "Fish key saved. Speech engine preserved."
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_speechify_key", false)) {
            val file = File(filesDir, "debug-speechify-key")
            val key = try { file.readText().trim() } finally { file.delete() }
            require(key.isNotBlank() && !key.any { it.isWhitespace() }) { "Invalid Speechify key" }
            val updated = settings.copy(speechifyApiKey = key)
            settingsStore.save(updated); settings = updated
            File(filesDir, "debug-speechify-paired").writeText("ok")
            status = "Speechify key saved. Speech engine preserved."
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_inworld_key", false)) {
            val file = File(filesDir, "debug-inworld-key")
            val key = try { file.readText().trim() } finally { file.delete() }
            require(key.isNotBlank() && !key.any { it.isWhitespace() }) { "Invalid Inworld key" }
            val updated = settings.copy(inworldApiKey = key)
            settingsStore.save(updated); settings = updated
            File(filesDir, "debug-inworld-paired").writeText("ok")
            status = "Inworld key saved. Speech engine preserved."
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_deepgram_key", false)) {
            val file = File(filesDir, "debug-deepgram-key")
            val key = try { file.readText().trim() } finally { file.delete() }
            require(key.isNotBlank() && !key.any { it.isWhitespace() }) { "Invalid Deepgram key" }
            val updated = settings.copy(deepgramApiKey = key)
            settingsStore.save(updated); settings = updated
            File(filesDir, "debug-deepgram-paired").writeText("ok")
            status = "Deepgram key saved. Speech engine preserved."
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_cartesia_key", false)) {
            val file = File(filesDir, "debug-cartesia-key")
            val key = try { file.readText().trim() } finally { file.delete() }
            require(key.isNotBlank() && !key.any { it.isWhitespace() }) { "Invalid Cartesia key" }
            val updated = settings.copy(cartesiaApiKey = key)
            settingsStore.save(updated); settings = updated
            File(filesDir, "debug-cartesia-paired").writeText("ok")
            status = "Cartesia key saved. Speech engine preserved."
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_eleven_key", false)) {
            val file = File(filesDir, "debug-eleven-key")
            val key = try { file.readText().trim() } finally { file.delete() }
            require(key.isNotBlank() && !key.any { it.isWhitespace() }) { "Invalid ElevenLabs key" }
            val updated = settings.copy(elevenApiKey = key)
            settingsStore.save(updated); settings = updated
            File(filesDir, "debug-eleven-paired").writeText("ok")
            status = "ElevenLabs key saved. Speech engine preserved."
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_groq_key", false)) {
            val file = File(filesDir, "debug-groq-key")
            val key = try { file.readText().trim() } finally { file.delete() }
            require(key.isNotBlank() && !key.any { it.isWhitespace() }) { "Invalid Groq key" }
            val updated = settings.copy(groqApiKey = key)
            settingsStore.save(updated); settings = updated
            File(filesDir, "debug-groq-paired").writeText("ok")
            status = "Groq key saved. Current speech engine unchanged."
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_kokoro", false)) {
            val updated = validateSettings(settings.copy(engine = "kokoro", kokoroVoice = "af_heart", characterMode = "narrator"))
            playback.stop(); settingsStore.save(updated); settings = updated
            status = "On-device Kokoro configured; Narrator mode works offline"
        }
        if (BuildConfig.DEBUG && intent.hasExtra("debug_mock")) {
            val url = intent.getStringExtra("debug_mock")!!
            settings = settings.copy(apiKey = "mock", geminiKey = "mock", oauthToken = "", project = "", cloudUrl = url, geminiUrl = url, vertexUrl = url, vertexProject = "mock-project", vertexToken = "mock", vertexBrokerUrl = "", vertexBrokerPin = "", vertexBrokerSecret = "", engine = intent.getStringExtra("debug_engine") ?: "vertex")
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
            rememberBook(imported.id)
        }
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("debug_book")?.let { id -> book = withContext(Dispatchers.IO) { books.load(id) }; screen = "reader" }
            if (intent.hasExtra("debug_chapter")) book?.let { chapter = intent.getIntExtra("debug_chapter", 0).coerceIn(0, it.chapters.lastIndex); paragraph = 0 }
            loadCharacters()
            if (intent.hasExtra("debug_play")) book?.let { paragraph = intent.getIntExtra("debug_play", 0).coerceIn(0, it.chapters[chapter].paragraphs.lastIndex); screen = "reader"; playback.play(it, chapter, paragraph) }
            if (intent.hasExtra("debug_preview_paragraph")) book?.let { target ->
                val index = intent.getIntExtra("debug_preview_paragraph", 0)
                playback.stop()
                val segments = playback.prepareChapter(target, chapter, settings)
                val segment = segments.first { it.paragraph == index }
                playback.preview(playback.speechFor(segment, settings, true))
            }
        }
        }
    }
}
