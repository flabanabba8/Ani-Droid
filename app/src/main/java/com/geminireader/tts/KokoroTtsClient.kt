package com.geminireader.tts

import android.content.Context
import androidx.annotation.Keep
import kotlin.coroutines.CoroutineContext
import com.geminireader.analysis.KokoroVoices
import com.geminireader.data.Settings
import com.geminireader.data.json
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object KokoroPcm {
    fun encode(samples: FloatArray, rate: Int): Pcm {
        require(samples.isNotEmpty() && samples.size <= Int.MAX_VALUE / 2 && rate in 8000..192000) { "Kokoro returned invalid audio" }
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            require(sample.isFinite()) { "Kokoro returned invalid audio samples" }
            bytes.putShort((sample.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        }
        return Pcm(bytes.array(), rate)
    }
}

// JNI looks up this exact typed method; an invokedynamic lambda only exposes invoke(Object).
@Keep
internal class KokoroGenerationCallback(private val context: CoroutineContext) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (context.isActive) 1 else 0
}

/** One model per process. Generation and release are serialized because the native runtime is stateful. */
class KokoroTtsClient(context: Context) : TtsEngine {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var native: OfflineTts? = null
    val downloads = KokoroDownloads(appContext)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private val activeDownload = MutableStateFlow(false)
    val downloading = activeDownload.asStateFlow()
    private val ready = MutableStateFlow(downloads.installed())
    val installed = ready.asStateFlow()
    private val progress = MutableStateFlow(if (ready.value) "Ready for offline speech." else "Download Kokoro to enable offline speech.")
    val status = progress.asStateFlow()

    private suspend fun runtime(): OfflineTts {
        native?.let { return it }
        progress.value = "Verifying Kokoro files…"
        downloads.verifyForLoad()
        currentCoroutineContext().ensureActive()
        KokoroNativeLoader.initialize(downloads.runtimeFolder)
        val folder = downloads.modelFolder
        progress.value = "Loading Kokoro on this phone…"
        val model = OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(folder, "model.onnx").path,
                    voices = File(folder, "voices.bin").path,
                    tokens = File(folder, "tokens.txt").path,
                    dataDir = File(folder, "espeak-ng-data").path,
                    lexicon = File(folder, "lexicon-us-en.txt").path,
                    lang = "en-us"
                ), numThreads = 2, debug = false, provider = "cpu"
            ), maxNumSentences = 1
        ))
        try {
            check(model.sampleRate() == 24000 && model.numSpeakers() >= KokoroVoices.names.size) { "Unexpected Kokoro model format" }
        } catch (e: Exception) { model.release(); throw e }
        native = model
        return model
    }

    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm = withContext(Dispatchers.IO) {
        mutex.withLock {
            val voice = speech.voice.takeIf { it in KokoroVoices.names } ?: settings.kokoroVoice
            val speaker = KokoroVoices.speakerId(voice)
            require(speech.text.isNotBlank() && speech.text.length <= 4000) { "Kokoro speech must contain 1–4000 characters" }
            val context = currentCoroutineContext()
            try {
                context.ensureActive()
                val model = runtime()
                context.ensureActive()
                progress.value = "Generating speech on this phone…"
                val audio = model.generateWithCallback(speech.text, sid = speaker, speed = 1f, callback = KokoroGenerationCallback(context))
                context.ensureActive() // Never cache partial audio returned after cancellation.
                KokoroPcm.encode(audio.samples, audio.sampleRate)
            } finally {
                progress.value = if (native == null) if (ready.value) "Ready for offline speech." else "Download Kokoro in Settings." else "Ready for offline speech on this phone."
            }
        }
    }

    fun startDownload() {
        if (activeDownload.value) return
        activeDownload.value = true
        downloadJob = downloadScope.launch {
            try { mutex.withLock { downloads.install { progress.value = it } } }
            catch (e: CancellationException) { progress.value = "Download cancelled. Tap Download to retry." }
            catch (e: Exception) { progress.value = e.message ?: "Download failed; retry." }
            finally { ready.value = downloads.installed(); activeDownload.value = false }
        }
    }
    fun cancelDownload() { downloadJob?.cancel() }
    suspend fun deleteDownloaded() {
        downloadJob?.cancelAndJoin()
        withContext(Dispatchers.IO) { mutex.withLock {
            native?.release(); native = null
            downloads.delete(); ready.value = false
            progress.value = "Kokoro deleted. Download it again to use offline speech."
        } }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        downloadJob?.cancelAndJoin()
        mutex.withLock {
            native?.release(); native = null
            progress.value = if (ready.value) "Ready for offline speech." else "Download Kokoro in Settings."
        }
    }
}
