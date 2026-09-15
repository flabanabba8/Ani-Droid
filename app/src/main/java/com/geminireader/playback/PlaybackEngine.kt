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
    val api = HttpApi()
    val cache = AudioCache(File(app.cacheDir, "speech"))
    private var job: Job? = null
    private var generation = 0
    private var complete = false
    private var playbackBook: Book? = null
    private var playbackChapter = 0
    private var segments = emptyList<Segment>()
    var active by mutableStateOf<Segment?>(null)
    var activeBookId by mutableStateOf("")
    var activeChapter by mutableStateOf(0)
    var speaking by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var speed by mutableFloatStateOf(1f)
    var speaker by mutableStateOf("Narrator")
    var prompt by mutableStateOf("")
    var speechFor: (Segment, Settings, Boolean) -> Speech = { segment, settings, endParagraph -> Speech(segment.text, settings.narratorPrompt, settings.narratorVoice, if (endParagraph) settings.paragraphPauseMs else settings.withinPauseMs) }
    var prepareChapter: suspend (Book, Int, Settings) -> List<Segment> = { book, chapter, _ -> Segmenter.narration(book.chapters[chapter].paragraphs) }
    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { speaking = isPlaying }
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
                if (++ticks % 20 == 0 && active != null) persist()
                delay(100)
            }
        }
    }
    private fun persist() {
        val id = playbackBook?.id ?: return
        val index = player.currentMediaItem?.mediaId?.substringAfter(':')?.toIntOrNull() ?: return
        val segment = segments.getOrNull(index) ?: return
        val position = Position(playbackChapter, segment.paragraph, segment.start, player.currentPosition)
        app.scope.launch(Dispatchers.IO) { app.books.position(id, position) }
    }
    fun stop() { persist(); generation++; job?.cancel(); complete = false; player.stop(); player.clearMediaItems(); active = null; activeBookId = ""; playbackBook = null; loading = false; cache.pinned.clear() }
    fun toggle() {
        if (activeBookId == app.book?.id && activeChapter == app.chapter && (player.mediaItemCount > 0 || loading)) { player.playWhenReady = !player.playWhenReady; persist() }
        else app.book?.let { play(it, app.chapter, app.paragraph, resume = true) }
    }
    fun changeSpeed(value: Float) { speed = value; player.playbackParameters = PlaybackParameters(value) }
    fun play(book: Book, chapter: Int, paragraph: Int, resume: Boolean = false) {
        val saved = if (resume) app.books.position(book.id) else Position(chapter, paragraph)
        stop()
        val gen = generation
        playbackBook = book; playbackChapter = chapter; activeBookId = book.id; activeChapter = chapter
        app.startService(Intent(app, PlaybackService::class.java))
        player.playWhenReady = true; loading = true; app.status = "Preparing audio…"
        val settings = app.settings
        job = app.scope.launch {
            try {
                coroutineScope {
                val chapterSegments = prepareChapter(book, chapter, settings).filter { it.paragraph > paragraph || (it.paragraph == paragraph && (!resume || it.end > saved.segment)) }
                segments = chapterSegments
                require(chapterSegments.isNotEmpty()) { "No text to play" }
                val direct = speechFor
                val engine = TtsEngines.create(settings, api)
                val permits = Semaphore(2)
                val pending = linkedMapOf<Int, Deferred<File>>()
                var scheduled = 0
                for (index in segments.indices) {
                    while (index - player.currentMediaItemIndex > settings.prefetch.coerceIn(1, 8)) delay(100)
                    val ahead = minOf(segments.size, index + settings.prefetch.coerceIn(1, 8))
                    while (scheduled < ahead) {
                        val i = scheduled++
                        pending[i] = async(Dispatchers.IO) { permits.withPermit { cache.get(direct(chapterSegments[i], settings, chapterSegments.getOrNull(i + 1)?.paragraph != chapterSegments[i].paragraph), settings, engine) } }
                    }
                    val file = pending.remove(index)!!.await()
                    ensureActive()
                    if (gen != generation) return@coroutineScope
                    val wasEnded = player.playbackState == Player.STATE_ENDED
                    val label = speakerLabel(segments[index])
                    val item = MediaItem.Builder().setUri(file.toURI().toString()).setMediaId("$gen:$index")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(book.title).setArtist(label).setAlbumTitle(book.chapters[chapter].title).build()).build()
                    player.addMediaItem(item)
                    if (index == 0) { player.prepare(); if (resume && saved.chapter == chapter) player.seekTo(0, saved.offsetMs); loading = false; if (app.status == "Preparing audio…" || app.status == "Analyzing…") app.status = "" }
                    else if (wasEnded) { player.seekTo(index, 0); player.prepare() }
                    // Eviction can remove completed items, but never the current/prefetched audio.
                    if (index > settings.prefetch + 2) {
                        val past = index - settings.prefetch - 2
                        player.getMediaItemAt(past).localConfiguration?.uri?.lastPathSegment?.let { cache.pinned.remove(it) }
                    }
                }
                complete = true
                if (player.playbackState == Player.STATE_ENDED && player.playWhenReady) nextChapter()
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { app.status = e.message ?: "Could not prepare speech"; loading = false }
        }
    }
    var speakerLabel: (Segment) -> String = { "Narrator" }
    private fun nextChapter() {
        val book = playbackBook ?: return
        if (playbackChapter < book.chapters.lastIndex) {
            val next = playbackChapter + 1
            if (app.book?.id == book.id) { app.chapter = next; app.paragraph = 0 }
            play(book, next, 0)
        } else { app.status = "Book finished"; player.pause() }
    }
    fun preview(speech: Speech) {
        stop(); loading = true
        val gen = generation
        app.startService(Intent(app, PlaybackService::class.java))
        job = app.scope.launch {
            try {
                val s = app.settings
                val file = cache.get(speech, s, TtsEngines.create(s, api))
                if (gen == generation) { player.setMediaItem(MediaItem.Builder().setUri(file.toURI().toString()).setMediaMetadata(MediaMetadata.Builder().setTitle("Voice preview").setArtist(speech.voice).build()).build()); player.prepare(); player.play(); app.status = "Preview ready" }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { app.status = e.message ?: "Preview failed" } finally { loading = false }
        }
    }
}
