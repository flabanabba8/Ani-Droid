package dev.anidroid

import android.content.Context
import android.net.Uri
import androidx.media3.common.StreamKey
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Renew old queued links at transfer time; keep the exact refreshed request for offline playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RefreshingDownloader(private val context: Context,private val original: DownloadRequest,private val create: (DownloadRequest)->Downloader): Downloader {
    @Volatile private var active: Downloader?=null
    @Volatile private var cancelled=false
    @Volatile private var renewal: Job?=null
    override fun download(listener: Downloader.ProgressListener?) {
        try {
            var request=effective(context,original)
            val data=JSONObject(String(request.data))
            val now=System.currentTimeMillis()
            val expires=request.uri.getQueryParameter("expires")?.toLongOrNull()?.let {if(it<1000000000000L)it*1000 else it}
            var refreshed=false
            if(data.optBoolean("renew") && (now-data.optLong("resolvedAt")>5*60*1000 || (expires!=null && expires<now+30000))) {request=refresh(request,data);refreshed=true}
            while(true) {
                if(cancelled)throw InterruptedException()
                active=create(request)
                try {active!!.download(listener);return}
                catch(e: HttpDataSource.InvalidResponseCodeException) {
                    // A signed link rejected mid-transfer: renew it once, keeping the same download id and cached parts that still match.
                    val renewable=JSONObject(String(request.data)).optBoolean("renew")
                    if(refreshed || !renewable || e.responseCode !in listOf(401,403,404,410)) throw e
                    request=refresh(request,JSONObject(String(request.data)));refreshed=true
                }
            }
        } catch(e: CancellationException) {throw InterruptedException()} catch(e: IOException) {throw e} catch(e: InterruptedException) {throw e} catch(e: Exception) {throw IOException("Could not refresh this queued episode. Open the title and retry.",e)}
        finally {renewal=null}
    }
    private fun refresh(request: DownloadRequest,data: JSONObject): DownloadRequest {
        val saved=playingFromJson(data)
        val fresh=runBlocking(Dispatchers.IO) {
            renewal=coroutineContext[Job]
            val streams=resolverForTests?.invoke(saved) ?: if(saved.title.provider=="luffy") {
                val connection=JSONObject(File(context.filesDir,"catalog-connection.json").readText())
                CatalogClient(CatalogConnection.fromJson(connection)).streams(saved.title,saved.episode,saved.source)
            } else Providers().streams(saved.title,saved.episode,saved.audio)
            val stream=chooseStream(streams,saved.stream.height) ?: throw IOException("No fresh stream available")
            // Same episode, quality limit and track languages as the original download.
            val tracks=OfflineDownloads.Tracks(data.optString("textLanguage").takeIf {it.isNotBlank()},data.optString("audioLanguage").takeIf {it.isNotBlank()})
            withContext(Dispatchers.Main) {OfflineDownloads.prepare(context,saved.copy(stream=stream),saved.stream.height.takeIf {it>0} ?: 720,original.id,tracks)}
        }
        if(cancelled)throw InterruptedException()
        // Changed signed URLs need fresh cache keys; discard the obsolete partial rendition.
        if(request.uri!=fresh.uri)runCatching {create(request).remove()}
        record(context,fresh)
        return fresh
    }
    override fun cancel() {cancelled=true;renewal?.cancel();active?.cancel()}
    override fun remove() {create(effective(context,original)).remove();forget(context,original.id)}
    companion object {
        /** Lets instrumentation tests supply fixture streams instead of live providers. */
        @Volatile var resolverForTests: ((Playing)->List<Stream>)?=null
        private fun file(context: Context,id: String): File {
            require(id.matches(Regex("[0-9a-f]{64}")))
            return File(File(context.filesDir,"download-requests").apply {mkdirs()},"$id.json")
        }
        fun effective(context: Context,request: DownloadRequest): DownloadRequest = runCatching {
            val f=file(context,request.id);if(!f.exists())return request
            val j=JSONObject(f.readText())
            DownloadRequest.Builder(request.id,Uri.parse(j.getString("uri"))).setMimeType(j.optString("mime").takeIf {it.isNotBlank()})
                .setData(j.getJSONObject("data").toString().toByteArray()).setStreamKeys(j.array("keys").objects().map {StreamKey(it.getInt("period"),it.getInt("group"),it.getInt("stream"))}).build()
        }.getOrDefault(request)
        fun record(context: Context,request: DownloadRequest) {
            val j=JSONObject().put("uri",request.uri.toString()).put("mime",request.mimeType).put("data",JSONObject(String(request.data)))
                .put("keys",JSONArray(request.streamKeys.map {JSONObject().put("period",it.periodIndex).put("group",it.groupIndex).put("stream",it.streamIndex)}))
            val target=file(context,request.id);val temporary=File(target.parentFile,target.name+".tmp");temporary.writeText(j.toString());check(temporary.renameTo(target))
        }
        fun forget(context: Context,id: String) {runCatching {file(context,id).delete()}}
    }
}
