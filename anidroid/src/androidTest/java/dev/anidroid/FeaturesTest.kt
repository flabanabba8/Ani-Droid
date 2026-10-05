package dev.anidroid

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FeaturesTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun view(v: View): PlayerView? {
        if(v is PlayerView)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)view(v.getChildAt(i))?.let {return it}
        return null
    }
    @Test fun localHistoryWatchlistAndCancelableNextEpisode() {
        val server=OfflineTest.MediaServer()
        lateinit var model: LibraryModel
        val title=Title("ani","feature-test","Feature test")
        val first=Episode("one",1,"1");val second=Episode("two",1,"2")
        val saved=Playing(title,first,Stream("Fixture","http://127.0.0.1:${server.socket.localPort}/test.m3u8",mapOf("X-Ani-Test" to "offline"),listOf(Caption("English","http://127.0.0.1:${server.socket.localPort}/test.vtt"))))
        try {
            compose.runOnIdle {
                model=ViewModelProvider(compose.activity)[LibraryModel::class.java]
                if(!model.watchlist.any {it.id==title.id})model.toggleWatchlist(title)
                assertTrue(model.watchlist.any {it.id==title.id})
                model.markWatched(title,first,false)
                model.savePosition(saved,2000,10000)
                assertTrue(model.continueWatching.any {it.title.id==title.id})
                assertTrue(compose.activity.getSharedPreferences("library",0).getString("watchlist","")!!.contains(title.id))
                if(!model.autoNext)model.toggleAutoNext()
                model.details=Details(title,"",listOf(first,second));model.episode=first;model.streams=listOf(saved.stream);model.play(saved.stream)
                assertEquals(second,model.nextEpisode(saved))
                model.adjustSubtitleDelay(500-model.subtitleDelayMs)
                assertEquals(500000,model.subtitleOffsetUs.get())
            }
            compose.waitUntil(30000) {compose.onAllNodesWithText("Cancel",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Cancel",useUnmergedTree=true).performClick()
            compose.runOnIdle {assertEquals(first,model.playing!!.episode);model.back()}
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(0,model.position(saved)) // Completed episodes leave Continue Watching.
                model.toggleWatchlist(title)
                assertFalse(model.watchlist.any {it.id==title.id})
                model.adjustSubtitleDelay(-model.subtitleDelayMs)
            }
        } finally {server.close()}
    }
    @Test fun queuePreferencesAndPauseControls() {
        lateinit var model: LibraryModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[LibraryModel::class.java];model.selectTab("Downloads")}
        val context=compose.activity
        val wasWifi=OfflineDownloads.wifiOnly(context)
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitUntil(10000) {OfflineDownloads.wifiOnly(context)!=wasWifi}
        compose.runOnIdle {assertEquals(!wasWifi,OfflineDownloads.manager(context).requirements.isUnmeteredNetworkRequired)}
        val quality=model.downloadHeight
        compose.onNodeWithText("Download quality: up to ${quality}p · Change").performClick()
        compose.runOnIdle {assertNotEquals(quality,model.downloadHeight);assertEquals(model.downloadHeight,context.getSharedPreferences("library",0).getInt("downloadHeight",0))}
        compose.onNodeWithText("Pause queue").performClick()
        compose.waitUntil(10000) {OfflineDownloads.manager(context).downloadsPaused}
        assertTrue(context.getSharedPreferences("library",0).getBoolean("queuePaused",false))
        compose.onNodeWithText("Resume queue").performClick()
        compose.waitUntil(10000) {!OfflineDownloads.manager(context).downloadsPaused}
        assertFalse(context.getSharedPreferences("library",0).getBoolean("queuePaused",true))
        compose.runOnIdle {OfflineDownloads.setWifiOnly(context,wasWifi)}
    }

    @Test fun subtitleOffsetChangesCueTimingWithoutMovingVideo() {
        val server=OfflineTest.MediaServer()
        lateinit var model: LibraryModel
        lateinit var player: Player
        try {
            compose.runOnIdle {
                model=ViewModelProvider(compose.activity)[LibraryModel::class.java]
                model.adjustSubtitleDelay(500-model.subtitleDelayMs)
                model.playing=Playing(Title("ani","timing-test","Timing test"),Episode("one",1,"1"),Stream("Fixture","http://127.0.0.1:${server.socket.localPort}/test.m3u8",mapOf("X-Ani-Test" to "offline"),listOf(Caption("English","http://127.0.0.1:${server.socket.localPort}/test.vtt"))))
            }
            compose.waitUntil(30000) {var ready=false;compose.runOnIdle {player=view(compose.activity.window.decorView)!!.player!!;ready=player.playbackState==Player.STATE_READY};ready}
            compose.runOnIdle {player.pause();player.seekTo(1250)}
            compose.waitUntil(10000) {var settled=false;compose.runOnIdle {settled=player.currentPosition==1250L && player.playbackState==Player.STATE_READY};settled}
            Thread.sleep(700)
            compose.runOnIdle {assertTrue("Caption should be delayed past 1.25s",player.currentCues.cues.isEmpty());player.seekTo(2000);player.play()}
            compose.runOnIdle {println("TIMING: requests=${server.requests.get()} pos=${player.currentPosition} groups=${player.currentTracks.groups.map { g -> (0 until g.length).map { i -> "${g.type}:${g.isTrackSelected(i)}:${g.getTrackFormat(i).sampleMimeType}:${g.getTrackFormat(i).language}" } }} error=${player.playerError?.errorCodeName}")}
            compose.waitUntil(10000) {var shown=false;compose.runOnIdle {shown=player.currentCues.cues.any {it.text.toString().contains("Timing fixture")}};shown}
            compose.runOnIdle {assertTrue(player.currentPosition>=2000);player.pause();model.adjustSubtitleDelay(-1000)}
            compose.waitForIdle()
            compose.waitUntil(10000) {var ready=false;compose.runOnIdle {ready=player.playbackState==Player.STATE_READY};ready}
            compose.runOnIdle {player.seekTo(750);player.play()}
            compose.waitUntil(10000) {var shown=false;compose.runOnIdle {shown=player.currentPosition>=750L && player.currentCues.cues.any {it.text.toString().contains("Timing fixture")}};shown}
            compose.runOnIdle {model.back();model.adjustSubtitleDelay(-model.subtitleDelayMs)}
        } finally {server.close()}
    }

}
