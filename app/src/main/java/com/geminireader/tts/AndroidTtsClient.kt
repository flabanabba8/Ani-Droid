package com.geminireader.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.geminireader.data.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** One serialized connection: changing voices or stopping must not affect another passage. */
class AndroidTtsClient(context: Context) : TtsEngine {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private var currentEngine = ""
    data class Engine(val id: String, val label: String)
    data class Catalog(val engine: String, val voices: List<Voice>)
    fun engines(): List<Engine> = context.packageManager.queryIntentServices(
        Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).map {
        Engine(it.serviceInfo.packageName, it.loadLabel(context.packageManager).toString())
    }.distinctBy { it.id }.sortedBy { it.label }

    private suspend fun runtime(engine: String): TextToSpeech {
        if (tts != null && (engine.isBlank() || currentEngine == engine)) return tts!!
        require(engines().isNotEmpty()) { "Install an Android text-to-speech engine, then refresh voices." }
        require(engine.isBlank() || engines().any { it.id == engine }) { "Selected Android speech engine is no longer installed." }
        tts?.shutdown(); tts = null
        val ready = CompletableDeferred<Int>()
        val instance = TextToSpeech(context, { ready.complete(it) }, engine.ifBlank { null })
        try {
            check(withTimeout(15000) { ready.await() } == TextToSpeech.SUCCESS) { "Android speech engine could not start. Check its voice data in Android settings." }
            currentEngine = engine.ifBlank { instance.defaultEngine.orEmpty() }
            tts = instance
            return instance
        } catch (e: Throwable) { instance.shutdown(); throw e }
    }
    suspend fun catalog(engine: String): Catalog = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val runtime = runtime(engine)
            Catalog(currentEngine, runtime.voices.orEmpty().filter {
                it.locale.language == "en" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }.sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.locale.toLanguageTag() }.thenBy { it.name }))
        }
    }
    override suspend fun synthesize(speech: Speech, settings: Settings): Pcm = mutex.withLock {
        require(speech.text.isNotBlank() && speech.text.length <= TextToSpeech.getMaxSpeechInputLength()) { "Android speech passage is empty or too long." }
        val file = File(context.cacheDir, "android-tts-${UUID.randomUUID()}.wav")
        try {
            withContext(Dispatchers.Main.immediate) {
                val runtime = runtime(settings.androidTtsEngine)
                val voice = runtime.voices.orEmpty().firstOrNull { it.name == settings.androidTtsVoice }
                    ?: error("Selected Android voice is unavailable. Refresh voices in Settings.")
                require(!settings.androidTtsOfflineOnly || !voice.isNetworkConnectionRequired) { "Select an offline Android voice or turn off Offline voices only." }
                require(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()) { "Download this voice in Android text-to-speech settings first." }
                check(runtime.setVoice(voice) == TextToSpeech.SUCCESS) { "Android speech engine rejected the selected voice." }
                runtime.setSpeechRate(1f); runtime.setPitch(1f)
                val id = UUID.randomUUID().toString()
                val done = CompletableDeferred<Unit>()
                runtime.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
                    @Deprecated("Required by Android")
                    override fun onError(utteranceId: String?) { onError(utteranceId, TextToSpeech.ERROR) }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id) done.completeExceptionally(IllegalStateException("Android speech generation failed ($errorCode). Check installed voice data."))
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == id) done.completeExceptionally(CancellationException("Android speech stopped"))
                    }
                })
                try {
                    check(runtime.synthesizeToFile(speech.text, Bundle(), file, id) == TextToSpeech.SUCCESS) { "Android speech engine could not queue this passage." }
                    withTimeout(120000) { done.await() }
                } finally { runtime.stop(); runtime.setOnUtteranceProgressListener(null) }
            }
            withContext(Dispatchers.IO) { Wav.decode(file.readBytes()).also { require(it.bytes.isNotEmpty()) { "Android speech engine returned empty audio." } } }
        } finally { file.delete() }
    }
    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.Main.immediate) { tts?.shutdown(); tts = null; currentEngine = "" }
    }
}
