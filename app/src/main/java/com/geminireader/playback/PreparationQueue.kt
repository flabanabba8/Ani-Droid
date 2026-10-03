package com.geminireader.playback

import android.app.job.*
import android.content.ComponentName
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import com.geminireader.ReaderApp
import com.geminireader.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable data class PreparationRequest(val book: String, val chapters: List<Int>, val settingsHash: String, val charging: Boolean, val wifi: Boolean)
object PreparationQueue {
    const val ID = 204
    private fun file(app: ReaderApp) = File(app.filesDir, "preparation-queue.json")
    fun read(app: ReaderApp): PreparationRequest? = runCatching { json.decodeFromString<PreparationRequest>(file(app).readText()) }.getOrNull()
    fun save(app: ReaderApp, value: PreparationRequest) = atomicWrite(file(app), json.encodeToString(value))
    fun fingerprint(s: Settings): String {
        val safe = s.copy(apiKey = "", geminiKey = "", oauthToken = "", vertexToken = "", vertexBrokerPin = "", vertexBrokerSecret = "", groqApiKey = "", elevenApiKey = "", cartesiaApiKey = "", deepgramApiKey = "", inworldApiKey = "", speechifyApiKey = "", fishApiKey = "")
        return MessageDigest.getInstance("SHA-256").digest(json.encodeToString(safe).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun schedule(app: ReaderApp, request: PreparationRequest) {
        require(request.chapters.isNotEmpty())
        save(app, request)
        val job = JobInfo.Builder(ID, ComponentName(app, PreparationService::class.java)).setPersisted(true).setRequiresCharging(request.charging)
        if (request.wifi && android.os.Build.VERSION.SDK_INT >= 28) job.setRequiredNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
        else if (request.wifi) job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        else if (app.settings.engine !in listOf("kokoro", "android")) job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        check(app.getSystemService(JobScheduler::class.java).schedule(job.build()) == JobScheduler.RESULT_SUCCESS) { "Android could not schedule preparation" }
        app.status = "Preparation queued; Android will run it when the selected conditions allow"
    }
    fun cancel(app: ReaderApp) { app.getSystemService(JobScheduler::class.java).cancel(ID) }
}
class PreparationService : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as ReaderApp
        work = app.scope.launch {
            while (app.preparing || app.busy || app.playback.player.playWhenReady) delay(1000)
            app.preparing = true; app.preparationJob = currentCoroutineContext()[Job]
            var interrupted = false
            try {
                app.playback.stopAndJoin()
                val request = PreparationQueue.read(app) ?: return@launch
                if (request.wifi) {
                    val network = app.getSystemService(android.net.ConnectivityManager::class.java)
                    while (network.getNetworkCapabilities(network.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) delay(1000)
                }
                val settings = app.settingsStore.flow.first()
                require(PreparationQueue.fingerprint(settings) == request.settingsHash) { "Settings changed. Review and requeue preparation before generating audio." }
                val book = withContext(Dispatchers.IO) { app.books.load(request.book) }
                for (ch in request.chapters) {
                    ensureActive()
                    app.prepareChapterOffline(book, ch, settings)
                    PreparationQueue.save(app, request.copy(chapters = request.chapters.dropWhile { it != ch }.drop(1)))
                }
                app.status = "Queued chapters are ready offline"
            } catch (e: CancellationException) { interrupted = true; throw e }
            catch (e: Exception) { app.status = "Preparation paused: ${e.message}. Requeue to resume saved audio." }
            finally { app.preparing = false; app.playback.cache.pinned.clear(); if (!interrupted) jobFinished(params, false) }
        }
        return true
    }
    override fun onDestroy() { work?.cancel(); super.onDestroy() }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); work = null; return true }
}
