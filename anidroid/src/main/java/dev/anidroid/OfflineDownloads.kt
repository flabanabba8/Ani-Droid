package dev.anidroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun Playing.toJson(): JSONObject = JSONObject().put("title",title.toJson())
    .put("episode",JSONObject().put("id",episode.id).put("season",episode.season).put("number",episode.number).put("name",episode.name))
    .put("stream",stream.toJson()).put("audio",audio).put("source",source)
fun playingFromJson(json: JSONObject,offlineId: String? = null): Playing {
    val ep=json.getJSONObject("episode")
    return Playing(titleFromJson(json.getJSONObject("title")),Episode(ep.getString("id"),ep.getInt("season"),ep.getString("number"),ep.optString("name")),streamFromJson(json.getJSONObject("stream")),offlineId,json.optString("audio","original"),source=json.optString("source","auto"))
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object OfflineDownloads {
    private var instance: DownloadManager? = null
    private var mediaCache: SimpleCache? = null
    private val executor=Executors.newFixedThreadPool(2)
    private val captionClient by lazy { okhttp3.OkHttpClient.Builder().callTimeout(25,java.util.concurrent.TimeUnit.SECONDS).build() }
    private fun directory(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir,"offline").apply { mkdirs() }
    @Synchronized fun manager(context: Context): DownloadManager {
        instance?.let { return it }
        val app=context.applicationContext
        val db=StandaloneDatabaseProvider(app)
        mediaCache=SimpleCache(File(directory(app),"media"),NoOpCacheEvictor(),db)
        val downloaderFactory=DownloaderFactory { request ->
            RefreshingDownloader(app,request) { active -> val current=playingFromJson(JSONObject(String(active.data,Charsets.UTF_8)));DefaultDownloaderFactory(cacheFactory(app,current.stream,false),executor).createDownloader(active) }
        }
        return DownloadManager(app,DefaultDownloadIndex(db),downloaderFactory).also { manager ->
            instance=manager;manager.maxParallelDownloads=1;manager.minRetryCount=3
            manager.addListener(object: DownloadManager.Listener {
                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    File(directory(app),download.request.id).deleteRecursively();RefreshingDownloader.forget(app,download.request.id)
                }
            })
            manager.setRequirements(Requirements(if(wifiOnly(app)) Requirements.NETWORK or Requirements.NETWORK_UNMETERED else Requirements.NETWORK))
            if(app.getSharedPreferences("library",0).getBoolean("queuePaused",false)) manager.pauseDownloads() else manager.resumeDownloads()
        }
    }
    private fun cacheFactory(context: Context,stream: Stream,offline: Boolean): CacheDataSource.Factory {
        if(mediaCache==null) manager(context)
        return CacheDataSource.Factory().setCache(mediaCache!!)
            .setUpstreamDataSourceFactory(if(offline) null else DefaultHttpDataSource.Factory().setDefaultRequestProperties(stream.headers))
    }
    fun playbackSource(context: Context,stream: Stream,offline: Boolean) = DefaultDataSource.Factory(context,if(offline) cacheFactory(context,stream,true) else DefaultHttpDataSource.Factory().setDefaultRequestProperties(stream.headers))
    fun request(context: Context,id: String) = manager(context).downloadIndex.getDownload(id)?.request?.let {RefreshingDownloader.effective(context,it)}
    fun saved(context: Context,request: DownloadRequest)=playingFromJson(JSONObject(String(RefreshingDownloader.effective(context,request).data)),request.id)
    fun list(manager: DownloadManager): List<Download> = manager.downloadIndex.getDownloads().use { cursor ->
        buildList { while(cursor.moveToNext()) add(cursor.download) }
    }
    private fun identity(p: Playing): String = MessageDigest.getInstance("SHA-256").digest("${p.title.provider}:${p.title.id}:${p.episode.season}:${p.episode.number}:${p.audio}:${p.stream.height}".toByteArray()).joinToString("") { "%02x".format(it) }
    /** Languages fixed when a download starts, so a renewed link fetches the same renditions. */
    class Tracks(val text: String?,val audio: String?)
    suspend fun prepare(context: Context,playing: Playing,maxHeight: Int = 720,idOverride: String? = null,tracks: Tracks? = null): DownloadRequest {
        val app=context.applicationContext;manager(app)
        check(directory(app).usableSpace>100L*1024*1024) { "Free at least 100 MB before starting a download." }
        val showPrefs=JSONObject(app.getSharedPreferences("library",0).getString("showPreferences","{}") ?: "{}").optJSONObject("${playing.title.provider}:${playing.title.id}") ?: JSONObject()
        val textLanguage=tracks?.text ?: showPrefs.optString("subtitleLanguage","en")
        val height=minOf(playing.stream.height.takeIf { it>0 } ?: maxHeight,maxHeight)
        var saved=playing.copy(stream=playing.stream.copy(height=height,label="Up to ${height}p · Offline"))
        val id=idOverride ?: identity(saved)
        // Cache a default external subtitle alongside the video. HLS-embedded subtitle
        // renditions are selected by DownloadHelper below, together with audio/video.
        val caption=playing.stream.captions.firstOrNull {it.name==showPrefs.optString("subtitleLabel")} ?: playing.stream.captions.firstOrNull { it.name.contains("English",true) } ?: playing.stream.captions.firstOrNull()
        if(caption!=null) {
            val local=withContext(Dispatchers.IO) {
                val folder=File(directory(app),id).apply { mkdirs() }
                val extension=if(caption.url.substringBefore('?').endsWith(".srt")) "srt" else "vtt"
                val file=File(folder,"subtitle.$extension")
                val request=okhttp3.Request.Builder().url(caption.url).apply { playing.stream.headers.forEach { (k,v) -> header(k,v) } }.build()
                captionClient.newCall(request).execute().use { r ->
                    check(r.isSuccessful) { "Could not save subtitles. Retry the download." }
                    val source=r.body.source()
                    check(!source.request(4L*1024*1024+1)) { "Subtitle file is too large." }
                    file.writeBytes(source.readByteArray())
                }
                Caption(caption.name,Uri.fromFile(file).toString())
            }
            saved=saved.copy(stream=saved.stream.copy(captions=listOf(local)))
        }
        val mime=if(saved.stream.url.substringBefore('?').endsWith(".mp4")) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8
        val item=MediaItem.Builder().setUri(saved.stream.url).setMimeType(mime).build()
        val audioLanguage=tracks?.audio ?: showPrefs.optString("audioLanguage").takeIf {it.isNotBlank()} ?: if(playing.title.provider=="luffy") "en" else null
        val params=DownloadHelper.getDefaultTrackSelectorParameters(app).buildUpon().setMaxVideoSize(Int.MAX_VALUE,height)
            .setPreferredTextLanguage(textLanguage).setPreferredAudioLanguage(audioLanguage).setForceHighestSupportedBitrate(true).setExceedVideoConstraintsIfNecessary(false).build()
        val helper=DownloadHelper.forMediaItem(item,params,DefaultRenderersFactory(app),DefaultHttpDataSource.Factory().setDefaultRequestProperties(saved.stream.headers))
        val metadata=saved.toJson().put("resolvedAt",System.currentTimeMillis()).put("renew",saved.stream.url.startsWith("https://")).put("textLanguage",textLanguage).put("audioLanguage",audioLanguage ?: "").toString().toByteArray(Charsets.UTF_8)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { helper.release() }
            helper.prepare(object: DownloadHelper.Callback {
                override fun onPrepared(h: DownloadHelper, canDownload: Boolean) {
                    try {
                        check(canDownload) { "This stream cannot be downloaded." }
                        val videoSelected=(0 until h.periodCount).any { period ->
                            val info=h.getMappedTrackInfo(period)
                            (0 until info.rendererCount).any { renderer -> info.getRendererType(renderer)==androidx.media3.common.C.TRACK_TYPE_VIDEO && h.getTrackSelections(period,renderer).isNotEmpty() }
                        }
                        check(videoSelected) { "No video track fits your download quality. Choose a higher quality limit." }
                        h.addTextLanguagesToSelection(true,textLanguage)
                        val request=h.getDownloadRequest(id,metadata)
                        if(cont.isActive) cont.resume(request)
                    } catch(e: Exception) { if(cont.isActive) cont.resumeWithException(e) }
                    finally { h.release() }
                }
                override fun onPrepareError(h: DownloadHelper,error: IOException) { h.release();if(cont.isActive) cont.resumeWithException(error) }
            })
        }
    }
    fun wifiOnly(context: Context)=context.getSharedPreferences("library",0).getBoolean("wifiOnly",false)
    fun setWifiOnly(context: Context,enabled: Boolean) {
        context.getSharedPreferences("library",0).edit().putBoolean("wifiOnly",enabled).apply()
        DownloadService.sendSetRequirements(context,AniDownloadService::class.java,Requirements(if(enabled) Requirements.NETWORK or Requirements.NETWORK_UNMETERED else Requirements.NETWORK),true)
    }
    fun pauseAll(context: Context) { context.getSharedPreferences("library",0).edit().putBoolean("queuePaused",true).apply();DownloadService.sendPauseDownloads(context,AniDownloadService::class.java,true) }
    fun resumeAll(context: Context) { context.getSharedPreferences("library",0).edit().putBoolean("queuePaused",false).apply();DownloadService.sendResumeDownloads(context,AniDownloadService::class.java,true) }
    fun enqueue(context: Context,request: DownloadRequest) = DownloadService.sendAddDownload(context,AniDownloadService::class.java,request,true)
    fun pause(context: Context,id: String) = DownloadService.sendSetStopReason(context,AniDownloadService::class.java,id,1,true)
    fun resume(context: Context,id: String) = DownloadService.sendSetStopReason(context,AniDownloadService::class.java,id,0,true)
    fun remove(context: Context,id: String) = DownloadService.sendRemoveDownload(context,AniDownloadService::class.java,id,true)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AniDownloadService : DownloadService(71,1000L) {
    override fun onCreate() {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads","Video downloads",NotificationManager.IMPORTANCE_LOW))
        super.onCreate()
    }
    override fun getDownloadManager()=OfflineDownloads.manager(this)
    override fun getScheduler(): Scheduler = PlatformScheduler(this,72)
    override fun getForegroundNotification(downloads: MutableList<Download>,notMetRequirements: Int): Notification {
        val current=downloads.firstOrNull { it.state==Download.STATE_DOWNLOADING }
        val title=current?.let { runCatching { playingFromJson(JSONObject(String(it.request.data))).title.name }.getOrNull() } ?: "Ani-Droid downloads"
        val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title)
            .setContentText(if(notMetRequirements!=0) "Waiting for a network connection" else "Saving for offline playback")
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100,current?.percentDownloaded?.toInt() ?: 0,current==null || current.percentDownloaded<0).build()
    }
}
