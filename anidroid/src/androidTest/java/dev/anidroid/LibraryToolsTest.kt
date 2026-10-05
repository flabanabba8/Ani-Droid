package dev.anidroid

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.common.StreamKey
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LibraryToolsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun backupRoundTripRejectsCredentialsWithoutPartialChanges() {
        val prefs=compose.activity.getSharedPreferences("backup-test",0)
        prefs.edit().clear().putString("token","not-for-export").putString("favorites",JSONArray(listOf(Title("ani","test-1","Test",poster="https://example.invalid/signed?token=private").toJson())).toString()).putString("watchlist","[]").putString("history","[]").putString("showPreferences",JSONObject().put("ani:test-1",JSONObject().put("audio","dub").put("height",720)).toString()).putString("watched","[]").putInt("subtitleSize",24).apply()
        val text=LibraryBackup.export(prefs)
        assertFalse(text.contains("not-for-export"));assertFalse(text.contains("signed?"));assertFalse(text.contains("https://"))
        prefs.edit().putString("favorites","[]").apply();LibraryBackup.import(prefs,text)
        assertEquals(1,JSONArray(prefs.getString("favorites","[]")).length())
        val malformed=JSONObject(text).put("token","forbidden").toString()
        try {LibraryBackup.import(prefs,malformed);fail("Credentials accepted")} catch(_: IllegalArgumentException) {}
        assertEquals(1,JSONArray(prefs.getString("favorites","[]")).length())
        prefs.edit().clear().apply()
    }
    @Test fun batchDownloadSelectionQueuesDistinctEpisodes() {
        val server=OfflineTest.MediaServer()
        lateinit var model: LibraryModel
        val title=Title("ani","batch-test","Batch test")
        val eps=listOf(Episode("one",1,"1"),Episode("two",1,"2"))
        try {
            compose.runOnIdle {model=ViewModelProvider(compose.activity)[LibraryModel::class.java];model.details=Details(title,"",eps);model.audio="sub"}
            compose.onNodeWithText("Batch downloads").performClick()
            compose.onNodeWithText("Select season 1").performClick()
            assertTrue(compose.onAllNodes(hasText("2 episodes",substring=true)).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnIdle {assertTrue(model.batchEstimate(eps)>0);model.downloadBatch(eps) { e -> listOf(Stream("Fixture","http://127.0.0.1:${server.socket.localPort}/episode${e.number}/test.m3u8",mapOf("X-Ani-Test" to "offline"),height=180))}}
            compose.waitUntil(30000) {!model.busy}
            val manager=OfflineDownloads.manager(compose.activity)
            compose.waitUntil(30000) {OfflineDownloads.list(manager).count {String(it.request.data).contains("Batch test") && it.state==Download.STATE_COMPLETED}==2}
            val downloads=OfflineDownloads.list(manager).filter {String(it.request.data).contains("Batch test")}
            assertEquals(2,downloads.map {it.request.id}.distinct().size)
            compose.runOnIdle {downloads.forEach {OfflineDownloads.remove(compose.activity,it.request.id)}}
        } finally {server.close()}
    }
    @Test fun refreshedRequestRetainsOfflineTrackKeys() {
        val context=compose.activity;val id="a".repeat(64)
        val old=DownloadRequest.Builder(id,Uri.parse("https://example.invalid/old.m3u8")).setData("{}".toByteArray()).build()
        val fresh=DownloadRequest.Builder(id,Uri.parse("https://example.invalid/new.m3u8")).setMimeType("application/x-mpegURL").setStreamKeys(listOf(StreamKey(0,1,2))).setData("{\"renew\":false}".toByteArray()).build()
        RefreshingDownloader.record(context,fresh)
        val restored=RefreshingDownloader.effective(context,old)
        assertEquals(fresh.uri,restored.uri);assertEquals(fresh.streamKeys,restored.streamKeys)
        assertArrayEquals(fresh.data,restored.data);RefreshingDownloader.forget(context,id)
    }
    @Test fun backupImportValidatesEverythingBeforeChangingData() {
        val prefs=compose.activity.getSharedPreferences("backup-validate",0)
        val title=Title("ani","keep-1","Keep").toJson()
        prefs.edit().clear().putString("favorites",JSONArray(listOf(title)).toString()).putString("watched","[]").apply()
        val good=JSONObject().put("schema",1).put("lists",JSONObject().put("favorites",JSONArray()).put("watchlist",JSONArray()).put("history",JSONArray())).put("preferences",JSONObject().put("autoNext",false)).put("shows",JSONObject()).put("watched",JSONArray())
        val bad=listOf(
            JSONObject(good.toString()).put("shows",JSONObject().put("ani:x",JSONObject().put("source","http://signed.example/x"))),
            JSONObject(good.toString()).put("shows",JSONObject().put("ani:x",JSONObject().put("streamUrl","https://signed"))),
            JSONObject(good.toString()).put("shows",JSONObject().put("ani:x",JSONObject().put("height","720"))),
            JSONObject(good.toString()).put("watched",JSONArray(listOf("ani:x:1:1","not a key"))),
            JSONObject(good.toString()).put("preferences",JSONObject().put("subtitleSize",99)),
            JSONObject(good.toString()).put("preferences",JSONObject().put("catalogToken","secret")),
            JSONObject(good.toString()).put("lists",JSONObject().put("favorites",JSONArray(listOf(Title("other","a","b").toJson()))).put("watchlist",JSONArray()).put("history",JSONArray())),
            JSONObject(good.toString()).put("schema",2))
        bad.forEach { b -> try {LibraryBackup.import(prefs,b.toString());fail("Accepted $b")} catch(_: Exception) {}
            assertEquals(1,JSONArray(prefs.getString("favorites","[]")).length());assertFalse(prefs.contains("autoNext")) }
        try {LibraryBackup.import(prefs,"{not json");fail()} catch(_: Exception) {}
        LibraryBackup.import(prefs,good.toString())
        assertEquals(0,JSONArray(prefs.getString("favorites","[]")).length());assertFalse(prefs.getBoolean("autoNext",true))
        prefs.edit().clear().apply()
    }
    @Test fun watchedMarkersHonorManualChangesAndAutomaticCompletion() {
        lateinit var model: LibraryModel
        val title=Title("ani","watched-test","Watched test");val e=Episode("w1",1,"1")
        val p=Playing(title,e,Stream("x","http://127.0.0.1/x.m3u8",emptyMap()))
        compose.runOnIdle {
            model=ViewModelProvider(compose.activity)[LibraryModel::class.java]
            model.markWatched(title,e,false);model.details=Details(title,"",listOf(e));model.episode=e;model.streams=listOf(p.stream);model.play(p.stream);model.playing=null
            model.savePosition(p,50000,100000);assertFalse(model.isWatched(title,e))
            model.savePosition(p,96000,100000);assertTrue(model.isWatched(title,e))
            model.markWatched(title,e,false);assertFalse(model.isWatched(title,e))
            model.savePosition(p,99000,100000);assertFalse("manual unwatched must survive auto-mark",model.isWatched(title,e))
            model.markWatched(title,e,true);assertTrue(model.isWatched(title,e))
            model.markWatched(title,e,false);model.play(p.stream);model.playing=null;model.savePosition(p,99000,100000);assertTrue("a fresh play may auto-mark again",model.isWatched(title,e));model.markWatched(title,e,true)
            assertTrue(compose.activity.getSharedPreferences("library",0).getString("watched","")!!.contains("watched-test"))
            model.markWatched(title,e,false)
        }
    }
    @Test fun expiredDownloadLinkIsRenewedKeepingIdentity() {
        val server=OfflineTest.MediaServer()
        val title=Title("ani","expired-test","Expired test");val ep=Episode("x1",1,"1")
        val base="http://127.0.0.1:${server.socket.localPort}/test.m3u8"
        val good=Stream("Fixture",base,mapOf("X-Ani-Test" to "offline"),height=180)
        var calls=0
        try {
            val request=kotlinx.coroutines.runBlocking {OfflineDownloads.prepare(compose.activity,Playing(title,ep,good,audio="sub"),720)}
            // Same download, but its saved headers are no longer accepted: the server answers 403 like an expired signed link.
            val data=JSONObject(String(request.data));data.getJSONObject("stream").put("headers",JSONObject().put("X-Ani-Test","expired"));data.put("renew",true)
            val stale=run {DownloadRequest.Builder(request.id,request.uri).setMimeType(request.mimeType).setStreamKeys(request.streamKeys).setData(data.toString().toByteArray()).build()}
            RefreshingDownloader.resolverForTests={calls++;listOf(good)}
            OfflineDownloads.enqueue(compose.activity,stale)
            val manager=OfflineDownloads.manager(compose.activity)
            compose.waitUntil(60000) {manager.downloadIndex.getDownload(request.id)?.state in listOf(Download.STATE_COMPLETED,Download.STATE_FAILED)}
            val done=manager.downloadIndex.getDownload(request.id)!!
            assertEquals(Download.STATE_COMPLETED,done.state);assertEquals(1,calls)
            val saved=OfflineDownloads.saved(compose.activity,done.request)
            assertEquals(ep.id,saved.episode.id);assertEquals("expired-test",saved.title.id);assertEquals("sub",saved.audio)
            OfflineDownloads.remove(compose.activity,request.id)
            compose.waitUntil(30000) {manager.downloadIndex.getDownload(request.id)==null}
        } finally {RefreshingDownloader.resolverForTests=null;server.close()}
    }
}
