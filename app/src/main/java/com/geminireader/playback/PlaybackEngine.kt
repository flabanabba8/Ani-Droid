package com.geminireader.playback

import android.content.Intent
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.geminireader.ReaderApp
import com.geminireader.data.*
import com.geminireader.text.*
import com.geminireader.tts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackEngine(private val app: ReaderApp) {
    val player = ExoPlayer.Builder(app).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        setHandleAudioBecomingNoisy(true)
    }
    val api = HttpApi(spending = app.spending)
    val cache = AudioCache(File(app.cacheDir, "speech"))
    private var job: Job? = null
    private var generation = 0
    private var complete = false
    private var playbackBook: Book? = null
    private var playbackChapter = 0
    private var segments = emptyList<Segment>()
    private var preparedSettings: Settings? = null
    private var restoring = false
    private val durations = mutableListOf<Long>()
    var readyMs by mutableLongStateOf(0L)
    private fun updateBuffer() { readyMs = BufferPolicy.remainingMs(durations, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition, speed) }
    var active by mutableStateOf<Segment?>(null)
    var activeBookId by mutableStateOf("")
    var activeChapter by mutableStateOf(0)
    var speaking by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var speechFailed by mutableStateOf(false)
    private var retryParagraph = 0
    fun retrySpeech() {
        val target = playbackBook ?: return
        val ch = playbackChapter
        val canResume = player.currentMediaItem != null
        persist()
        play(target, ch, retryParagraph, resume = canResume)
    }
    var progress by mutableFloatStateOf(0f)
    var speed by mutableFloatStateOf(1f)
    var speaker by mutableStateOf("Narrator")
    var prompt by mutableStateOf("")
    var speechFor: (Segment, Settings, Boolean) -> Speech = { segment, settings, endParagraph -> Speech(segment.text, settings.narratorPrompt, settings.speechVoice, if (endParagraph) settings.paragraphPauseMs else settings.withinPauseMs) }
    var prepareChapter: suspend (Book, Int, Settings) -> List<Segment> = { book, chapter, _ -> Segmenter.narration(book.chapters[chapter].paragraphs) }
    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { speaking = isPlaying; persist() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { persist() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val parts = mediaItem?.mediaId?.split(':') ?: return
                if (parts.firstOrNull()?.toIntOrNull() != generation) return
                val index = parts.getOrNull(1)?.toIntOrNull() ?: return
                active = segments.getOrNull(index)
                active?.let { segment ->
                    val s = speechFor(segment, app.settings, segments.getOrNull(index + 1)?.paragraph != segment.paragraph)
                    prompt = s.prompt
                    speaker = mediaItem.mediaMetadata.artist?.toString() ?: "Narrator"
                    if (app.book?.id == activeBookId && app.chapter == activeChapter) app.paragraph = segment.paragraph
                }
                persist()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && complete && player.playWhenReady) nextChapter()
            }
            override fun onPlayerError(error: PlaybackException) { app.status = "Playback failed: ${error.errorCodeName}"; loading = false }
        })
        app.scope.launch {
            var ticks = 0
            while (isActive) {
                val duration = player.duration
                progress = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
                updateBuffer()
                if (++ticks % 20 == 0 && active != null) persist()
                delay(100)
            }
        }
    }
    fun persist() {
        if (restoring) return
        val id = playbackBook?.id ?: return
        val index = player.currentMediaItem?.mediaId?.substringAfter(':')?.toIntOrNull() ?: return
        val segment = segments.getOrNull(index) ?: return
        val position = Position(playbackChapter, segment.paragraph, segment.start, player.currentPosition,
            player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment.orEmpty(), speed)
        // Small atomic bookmark writes are serialized on the player thread; no older async write can win.
        runCatching { app.books.position(id, position) }.onFailure { app.status = "Could not save playback position" }
    }
    fun stop() { persist(); restoring = true; generation++; job?.cancel(); complete = false; player.stop(); player.clearMediaItems(); active = null; activeBookId = ""; playbackBook = null; loading = false; cache.pinned.clear(); durations.clear(); readyMs = 0; restoring = false }
    suspend fun stopAndJoin() { val previous = job; stop(); previous?.join() }
    fun toggle() {
        if (activeBookId == app.book?.id && activeChapter == app.chapter && (player.mediaItemCount > 0 || loading)) { player.playWhenReady = !player.playWhenReady; persist() }
        else app.book?.let { play(it, app.chapter, app.paragraph, resume = true) }
    }
    fun changeSpeed(value: Float) { speed = value; player.playbackParameters = PlaybackParameters(value); updateBuffer(); persist() }
    fun play(book: Book, chapter: Int, paragraph: Int, resume: Boolean = false) {
        if (app.savingPassage) return
        if (app.preparing) { app.status = "Wait for chapter preparation or cancel it before playback"; return }
        speechFailed = false; retryParagraph = paragraph
        app.rememberBook(book.id)
        if (!resume && activeBookId == book.id && activeChapter == chapter && preparedSettings == app.settings) {
            val ready = segments.indexOfFirst { it.paragraph == paragraph }
            val uri = if (ready in 0 until player.mediaItemCount) player.getMediaItemAt(ready).localConfiguration?.uri else null
            if (uri?.path?.let { File(it).exists() } == true) {
                uri.lastPathSegment?.let { cache.pinned.add(it) }
                player.seekTo(ready, 0)
                if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play(); persist()
                return
            }
        }
        val saved = if (resume) app.books.position(book.id) else Position(chapter, paragraph)
        val startParagraph = if (resume && saved.chapter == chapter) saved.paragraph else paragraph
        stop()
        val gen = generation
        restoring = true
        playbackBook = book; playbackChapter = chapter; activeBookId = book.id; activeChapter = chapter
        app.startService(Intent(app, PlaybackService::class.java))
        player.playWhenReady = true; loading = true; app.status = "Preparing audio…"
        val settings = app.settings
        if (resume) changeSpeed(saved.speed.coerceIn(.5f, 3f))
        job = app.scope.launch {
            try {
                coroutineScope {
                val chapterSegments = prepareChapter(book, chapter, settings).filter { it.paragraph > startParagraph || (it.paragraph == startParagraph && (!resume || it.end > saved.segment)) }
                segments = chapterSegments
                preparedSettings = settings
                require(chapterSegments.isNotEmpty()) { "No text to play" }
                val direct = speechFor
                val engine = TtsEngines.create(settings, api, app.kokoro, app.androidTts)
                // Local inference is serial. Schedule in reading order so a later sentence
                // cannot acquire the native mutex ahead of the sentence needed for playback.
                val inFlight = if (settings.engine in listOf("kokoro")) 1 else 2
                val permits = Semaphore(inFlight)
                val pending = linkedMapOf<Int, Deferred<Pair<File, Boolean>>>()
                var scheduled = 0
                for (index in segments.indices) {
                    while (index > 0 && readyMs >= settings.bufferSeconds.coerceIn(15, 600) * 1000L) delay(100)
                    val ahead = minOf(segments.size, index + inFlight)
                    while (scheduled < ahead) {
                        val i = scheduled++
                        pending[i] = async(Dispatchers.IO) { permits.withPermit {
                            val speech = direct(chapterSegments[i], settings, chapterSegments.getOrNull(i + 1)?.paragraph != chapterSegments[i].paragraph)
                            val durable = app.offline.audio(book.id, chapter, "${AudioCache.key(speech, settings)}.wav")
                            if (durable.isFile) { WavExport.inspect(durable); durable to true }
                            else { val reused = cache.contains(speech, settings); app.bookAudio(book.id, chapter, chapterSegments[i]) { cache.get(speech, settings, engine) } to reused }
                        } }
                    }
                    val (file, reused) = pending.remove(index)!!.await()
                    val durationMs = withContext(Dispatchers.IO) { WavExport.inspect(file).durationMs }
                    ensureActive()
                    if (gen != generation) return@coroutineScope
                    val wasEnded = player.playbackState == Player.STATE_ENDED
                    val label = speakerLabel(segments[index])
                    val item = MediaItem.Builder().setUri(file.toURI().toString()).setMediaId("$gen:$index")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(book.title).setArtist(label).setAlbumTitle(book.chapters[chapter].title).build()).build()
                    player.addMediaItem(item)
                    durations += durationMs
                    if (index == 0) {
                        if (resume && reused && saved.chapter == chapter) player.seekTo(0, BufferPolicy.resumeOffset(saved.audioKey, file.name, saved.offsetMs, durationMs))
                        player.prepare(); restoring = false; persist(); loading = false
                        if (app.status == "Preparing audio…" || app.status.startsWith("Analyzing")) app.status = ""
                    }
                    else if (wasEnded) { player.seekTo(index, 0); player.prepare() }
                    updateBuffer()
                }
                complete = true
                if (player.playbackState == Player.STATE_ENDED && player.playWhenReady) nextChapter()
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { app.status = e.message ?: "Could not prepare speech"; loading = false; restoring = false; speechFailed = true }
        }
    }
    var speakerLabel: (Segment) -> String = { "Narrator" }
    data class ExportSelection(val files: List<File>, val description: String)
    fun exportSelection(): ExportSelection {
        require(player.mediaItemCount > 0 && activeBookId.isNotBlank()) { "Play some book audio before exporting" }
        val files = (0 until player.mediaItemCount).map { File(requireNotNull(player.getMediaItemAt(it).localConfiguration?.uri?.path)) }
        require(files.all { it.isFile }) { "Some audio is no longer cached; play that passage again" }
        val last = segments.getOrNull(files.lastIndex) ?: error("No book audio")
        return ExportSelection(files, "${files.size} generated segments; paragraphs ${segments.first().paragraph + 1}–${last.paragraph + 1}. Only generated audio is included, possibly ending partway through the last paragraph. WAV uses original 1× speed.")
    }
    private fun nextChapter() {
        val book = playbackBook ?: return
        if (playbackChapter < book.chapters.lastIndex) {
            val next = playbackChapter + 1
            if (app.book?.id == book.id) { app.chapter = next; app.paragraph = 0 }
            play(book, next, 0)
        } else { app.status = "Book finished"; player.pause() }
    }
    fun preview(speech: Speech) {
        if (app.preparing) { app.status = "Cancel chapter preparation before previewing"; return }
        stop(); loading = true
        val gen = generation
        app.startService(Intent(app, PlaybackService::class.java))
        job = app.scope.launch {
            try {
                val s = app.settings
                val file = cache.get(speech, s, TtsEngines.create(s, api, app.kokoro, app.androidTts))
                if (gen == generation) { player.setMediaItem(MediaItem.Builder().setUri(file.toURI().toString()).setMediaMetadata(MediaMetadata.Builder().setTitle("Voice preview").setArtist(speech.voice).build()).build()); player.prepare(); player.play(); app.status = "Preview ready" }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { app.status = e.message ?: "Preview failed" } finally { loading = false }
        }
    }
}
