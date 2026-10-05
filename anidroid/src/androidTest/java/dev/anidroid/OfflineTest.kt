package dev.anidroid

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    internal class MediaServer: AutoCloseable {
        val socket=ServerSocket(0,20,InetAddress.getByName("127.0.0.1"))
        val requests=AtomicInteger()
        val executor=Executors.newCachedThreadPool()
        init {
            executor.execute {
                while(!socket.isClosed) {
                    val connection=try { socket.accept() } catch(e: Exception) { break }
                    executor.execute {
                        connection.use { s ->
                            runCatching {
                                s.soTimeout=10000
                                val reader=s.getInputStream().bufferedReader()
                                val first=reader.readLine() ?: return@runCatching
                                var allowed=false
                                while(true) { val line=reader.readLine() ?: break;if(line.isEmpty())break;if(line.equals("X-Ani-Test: offline",true))allowed=true }
                                val path=first.split(' ').getOrNull(1).orEmpty().substringBefore('?').substringAfterLast('/')
                                require(path.matches(Regex("(test\\.m3u8|test\\.vtt|clip-[0-9]+\\.ts)")))
                                val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("offline/$path").use { it.readBytes() }
                                val out=s.getOutputStream()
                                if(!allowed) {out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());return@runCatching}
                                requests.incrementAndGet()
                                val type=if(path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else if(path.endsWith("vtt")) "text/vtt" else "video/mp2t"
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                for(chunk in bytes.asList().chunked(2048)) {out.write(chunk.toByteArray());out.flush();if(path.endsWith("ts"))Thread.sleep(10)}
                            }
                        }
                    }
                }
            }
        }
        override fun close() {socket.close();executor.shutdownNow()}
    }
    private fun playerView(v: View): PlayerView? {
        if(v is PlayerView)return v
        if(v is ViewGroup) for(i in 0 until v.childCount) playerView(v.getChildAt(i))?.let {return it}
        return null
    }
    @Test fun bothProvidersDownloadPersistAndPlayWithServerOffline() {
        lateinit var model: LibraryModel
        compose.runOnIdle { model=ViewModelProvider(compose.activity)[LibraryModel::class.java] }
        for(provider in listOf("ani","luffy")) {
            val server=MediaServer()
            try {
                val title=Title(provider,"offline-test","Offline test $provider")
                val ep=Episode("episode-1",1,"1")
                val stream=Stream("180p test","http://127.0.0.1:${server.socket.localPort}/test.m3u8",mapOf("X-Ani-Test" to "offline"),height=180)
                compose.runOnIdle { model.back();model.details=Details(title,"",listOf(ep));model.episode=ep;model.streams=listOf(stream);model.audio="sub" }
                compose.onNodeWithText("Download",useUnmergedTree=true).performClick()
                compose.waitUntil(30000) { !model.busy }
                compose.runOnIdle { assertNull("Download preparation: ${model.error}",model.error) }
                val manager=OfflineDownloads.manager(compose.activity)
                var downloaded: Download?=null
                compose.waitUntil(60000) {
                    downloaded=OfflineDownloads.list(manager).firstOrNull { String(it.request.data).contains("Offline test $provider") }
                    downloaded?.state in listOf(Download.STATE_COMPLETED,Download.STATE_FAILED)
                }
                assertEquals(Download.STATE_COMPLETED,downloaded!!.state)
                assertTrue(downloaded!!.bytesDownloaded>0);assertTrue(server.requests.get()>=4)
                val restored=manager.downloadIndex.getDownload(downloaded!!.request.id)!!
                assertEquals(Download.STATE_COMPLETED,restored.state)
                server.close()
                compose.runOnIdle { model.playOffline(playingFromJson(org.json.JSONObject(String(restored.request.data)),restored.request.id)) }
                var error: String?=null
                compose.waitUntil(30000) {
                    var ready=false
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val player=playerView(compose.activity.window.decorView)?.player as? ExoPlayer
                        error=player?.playerError?.errorCodeName
                        ready=(player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0)>5 && (player?.currentPosition ?: 0)>1000
                    }
                    ready || error!=null
                }
                assertNull("Offline decode failed",error)
                compose.runOnIdle {
                    val view=playerView(compose.activity.window.decorView)!!
                    assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,compose.activity.resources.configuration.orientation)
                    assertTrue("Video view should fill the window width",view.width>=compose.activity.window.decorView.width*0.95)
                    assertTrue("Video view should fill the window height",view.height>=compose.activity.window.decorView.height*0.95)
                    val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    java.io.File(compose.activity.cacheDir,"fullscreen-$provider.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                }
                compose.runOnIdle { model.back();OfflineDownloads.remove(compose.activity,restored.request.id) }
                compose.waitUntil(30000) { manager.downloadIndex.getDownload(restored.request.id)==null }
            } finally { server.close() }
        }
    }
}
