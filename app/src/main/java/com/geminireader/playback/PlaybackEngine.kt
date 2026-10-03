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
    private var pausedAt = 0L
    private val durations = mutableListOf<Long>()
    var readyMs by mutableLongStateOf(0L)
    private fun updateBuffer() { readyMs = BufferPolicy.remainingMs(durations, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition, speed) }
    var active by mutableStateOf<Segment?>(null)
    var activeBookId by mutableStateOf("")
    var activeChapter by mutableStateOf(0)
    var speaking by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var speechFailed by mutableStateOf(false)
    var sleepMode by mutableStateOf("off")
    var sleepRemainingMs by mutableLongStateOf(0L)
    private var sleepDeadline = 0L
    private var sleepFade = true
    fun setSleep(mode: String, minutes: Int = 30, fade: Boolean = true) {
        require(mode in listOf("off", "duration", "paragraph", "chapter"))
        sleepMode = mode; sleepFade = fade; player.volume = 1f
        sleepRemainingMs = if (mode == "duration") minutes.coerceIn(1, 240) * 60_000L else 0L
        sleepDeadline = android.os.SystemClock.elapsedRealtime() + sleepRemainingMs
    }
    private fun sleepNow() {
        player.pause(); setSleep("off"); app.status = "Sleep timer finished"
    }
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
    var requestedVoice by mutableStateOf("")
    var prompt by mutableStateOf("")
    var speechFor: (Segment, Settings, Boolean) -> Speech = { segment, settings, endParagraph -> Speech(segment.text, settings.narratorPrompt, settings.speechVoice, if (endParagraph) settings.paragraphPauseMs else settings.withinPauseMs) }
    var prepareChapter: suspend (Book, Int, Settings) -> List<Segment> = { book, chapter, _ -> Segmenter.narration(book.chapters[chapter].paragraphs) }
    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { speaking = isPlaying; persist() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) pausedAt = System.currentTimeMillis()
                else if (pausedAt > 0 && System.currentTimeMillis() - pausedAt >= 300_000) { pausedAt = 0; seekBy(-5000) }
                persist()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val parts = mediaItem?.mediaId?.split(':') ?: return
                if (parts.firstOrNull()?.toIntOrNull() != generation) return
                val index = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (sleepMode == "paragraph" && active != null && segments.getOrNull(index)?.paragraph != active?.paragraph) sleepNow()
                active = segments.getOrNull(index)
                active?.let { segment ->
                    val s = speechFor(segment, app.settings, segments.getOrNull(index + 1)?.paragraph != segment.paragraph)
                    prompt = mediaItem.mediaMetadata.description?.toString() ?: s.prompt
                    requestedVoice = mediaItem.mediaMetadata.subtitle?.toString() ?: s.voice
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
                if (sleepMode == "duration") {
                    sleepRemainingMs = (sleepDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    if (sleepRemainingMs == 0L) sleepNow()
                    else if (sleepFade) player.volume = (sleepRemainingMs / 15_000f).coerceIn(0f, 1f)
                }
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
            player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment.orEmpty(), speed, System.currentTimeMillis())
        // Small atomic bookmark writes are serialized on the player thread; no older async write can win.
        runCatching { app.books.position(id, position) }.onFailure { app.status = "Could not save playback position" }
    }
    fun stop() { persist(); restoring = true; generation++; job?.cancel(); complete = false; player.playWhenReady = false; player.stop(); player.clearMediaItems(); active = null; activeBookId = ""; playbackBook = null; loading = false; cache.pinned.clear(); durations.clear(); readyMs = 0; restoring = false }
    suspend fun stopAndJoin() { val previous = job; stop(); previous?.join() }
    fun toggle() {
        if (activeBookId == app.book?.id && activeChapter == app.chapter && player.playbackState == Player.STATE_ENDED && complete) { nextChapter(); return }
        if (activeBookId == app.book?.id && activeChapter == app.chapter && (player.mediaItemCount > 0 || loading)) { player.playWhenReady = !player.playWhenReady; persist() }
        else app.book?.let { play(it, app.chapter, app.paragraph, resume = true) }
    }
    fun seekBy(deltaMs: Long) {
        if (player.mediaItemCount == 0) return
        var index = player.currentMediaItemIndex.coerceAtLeast(0)
        var offset = player.currentPosition + deltaMs
        while (offset < 0 && index > 0) { index--; offset += durations.getOrElse(index) { 0 } }
        while (index < player.mediaItemCount - 1 && offset >= durations.getOrElse(index) { Long.MAX_VALUE }) {
            offset -= durations.getOrElse(index) { 0 }; index++
        }
        player.seekTo(index, offset.coerceIn(0, durations.getOrElse(index) { player.duration.coerceAtLeast(0) }))
        persist()
    }
    fun seekSentence(forward: Boolean) {
        val index = player.currentMediaItemIndex
        val segment = active ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val starts = Segmenter.sentences(segment.text).map { duration * it.first / segment.text.length.coerceAtLeast(1) }
        val target = if (forward) starts.firstOrNull { it > player.currentPosition + 200 } else starts.lastOrNull { it < player.currentPosition - 500 }
        if (target != null) player.seekTo(index, target)
        else if (forward && index + 1 < player.mediaItemCount) player.seekTo(index + 1, 0)
        else if (!forward && index > 0) {
            val previousIndex = player.getMediaItemAt(index - 1).mediaId.substringAfter(':').toIntOrNull()
            val previous = previousIndex?.let { segments.getOrNull(it) }
            val previousDuration = durations.getOrElse(index - 1) { 0L }
            val start = previous?.let { Segmenter.sentences(it.text).lastOrNull()?.first?.let { offset -> previousDuration * offset / it.text.length.coerceAtLeast(1) } } ?: 0L
            player.seekTo(index - 1, start)
        }
        else if (!forward) player.seekTo(0, 0)
        else app.status = "Next sentence is not buffered yet"
        persist()
    }
    fun changeSpeed(value: Float) { speed = value; player.playbackParameters = PlaybackParameters(value); updateBuffer(); persist() }
    fun play(book: Book, chapter: Int, paragraph: Int, resume: Boolean = false) {
        if (app.savingPassage) return
        if (app.preparing) { app.status = "Wait for chapter preparation or cancel it before playback"; return }
        speechFailed = false; retryParagraph = paragraph
        app.rememberBook(book.id)
        if (!resume && activeBookId == book.id && activeChapter == chapter && preparedSettings == app.settings) {
            val segmentIndex = segments.indexOfFirst { it.paragraph == paragraph }
            val ready = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == "$generation:$segmentIndex" } ?: -1
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
                supervisorScope {
                val chapterSegments = prepareChapter(book, chapter, settings).filter { it.paragraph > startParagraph || (it.paragraph == startParagraph && (!resume || it.end > saved.segment)) }
                segments = chapterSegments
                preparedSettings = settings
                require(chapterSegments.isNotEmpty()) { "No text to play" }
                val direct = speechFor
                val engine = TtsEngines.create(settings, api, app.kokoro, app.androidTts)
                // Local inference is serial. Schedule in reading order so a later sentence
                // cannot acquire the native mutex ahead of the sentence needed for playback.
                val inFlight = if (settings.engine == "kokoro") 1 else 2
                val permits = Semaphore(inFlight)
                val pending = linkedMapOf<Int, Deferred<Pair<File, Boolean>>>()
                suspend fun generate(i: Int): Pair<File, Boolean> = withContext(Dispatchers.IO) { permits.withPermit {
                    val speech = direct(chapterSegments[i], settings, chapterSegments.getOrNull(i + 1)?.paragraph != chapterSegments[i].paragraph)
                    val durable = app.offline.audio(book.id, chapter, "${AudioCache.key(speech, settings)}.wav")
                    if (durable.isFile) { WavExport.inspect(durable); durable to true }
                    else { val reused = cache.contains(speech, settings); app.bookAudio(book.id, chapter, chapterSegments[i]) { cache.get(speech, settings, engine) } to reused }
                } }
                var scheduled = 0
                for (index in segments.indices) {
                    while (index > 0 && readyMs >= settings.bufferSeconds.coerceIn(15, 600) * 1000L) delay(100)
                    val ahead = minOf(segments.size, index + inFlight)
                    while (scheduled < ahead) {
                        val i = scheduled++
                        pending[i] = async { generate(i) }
                    }
                    var retryStatus = ""
                    val generated = try { retryPrefetched(pending.remove(index)!!, onRetry = { attempt ->
                        retryStatus = "Retrying speech for paragraph ${segments[index].paragraph + 1} ($attempt/2)…"
                        app.status = retryStatus
                    }) { generate(index) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        retryParagraph = segments[index].paragraph
                        if (!settings.skipFailedSpeech) throw e
                        withContext(Dispatchers.IO) { app.skippedPassages.record(book.id, chapter, segments[index], "Generation failed after available retries") }
                        app.rejectionRevision++
                        app.status = "Skipped paragraph ${segments[index].paragraph + 1}; saved in repair list"
                        continue
                    }
                    val (file, reused) = generated
                    if (retryStatus.isNotEmpty() && app.status == retryStatus) app.status = ""
                    val durationMs = withContext(Dispatchers.IO) { WavExport.inspect(file).durationMs }
                    ensureActive()
                    if (gen != generation) return@supervisorScope
                    val wasEnded = player.playbackState == Player.STATE_ENDED
                    val label = speakerLabel(segments[index])
                    val requested = direct(segments[index], settings, segments.getOrNull(index + 1)?.paragraph != segments[index].paragraph)
                    val item = MediaItem.Builder().setUri(file.toURI().toString()).setMediaId("$gen:$index")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(book.title).setArtist(label).setSubtitle(requested.voice).setDescription(requested.prompt).setAlbumTitle(book.chapters[chapter].title).build()).build()
                    player.addMediaItem(item)
                    durations += durationMs
                    if (player.mediaItemCount == 1) {
                        if (resume && reused && saved.chapter == chapter) player.seekTo(0, (BufferPolicy.resumeOffset(saved.audioKey, file.name, saved.offsetMs, durationMs) - if (saved.savedAtMs > 0 && System.currentTimeMillis() - saved.savedAtMs >= 300_000) 5000 else 0).coerceAtLeast(0))
                        player.prepare(); restoring = false; persist(); loading = false
                        if (app.status == "Preparing audio…" || app.status.startsWith("Analyzing")) app.status = ""
                    }
                    else if (wasEnded) { player.seekTo(player.mediaItemCount - 1, 0); player.prepare() }
                    updateBuffer()
                }
                complete = true
                loading = false; restoring = false
                if (player.mediaItemCount == 0) { app.status = "No playable audio; see the repair list"; return@supervisorScope }
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
        val last = segments.getOrNull(player.getMediaItemAt(files.lastIndex).mediaId.substringAfter(':').toIntOrNull() ?: -1) ?: error("No book audio")
        return ExportSelection(files, "${files.size} generated segments; paragraphs ${segments.first().paragraph + 1}–${last.paragraph + 1}. Only generated audio is included, possibly ending partway through the last paragraph. WAV uses original 1× speed.")
    }
    private fun nextChapter() {
        if (sleepMode in listOf("chapter", "paragraph")) { sleepNow(); return }
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
