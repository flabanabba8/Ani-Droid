package dev.anidroid.server

import com.sun.net.httpserver.HttpServer
import dev.anidroid.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class CatalogTest {
    @Test fun durableCatalogFilteringPaginationAndLiteralSearch() {
        val path=Files.createTempDirectory("catalog-test").resolve("catalog.sqlite")
        Store(path).use { store ->
            store.upsert((1..85).map { Title("ani","anime-$it","Title ${it.toString().padStart(3,'0')}",series=it%2==0) },100)
            store.upsert(listOf(Title("luffy","1","100% Real_Story")),200)
            assertEquals(40,store.catalog("","all","all",1,"name").getJSONArray("items").length())
            assertEquals(6,store.catalog("","all","all",3,"name").getJSONArray("items").length())
            assertFalse(store.catalog("","all","all",3,"name").getBoolean("hasMore"))
            assertEquals(1,store.catalog("%","all","all",1,"name").getInt("total"))
            assertEquals(1,store.catalog("_","all","all",1,"name").getInt("total"))
            assertEquals(0,store.catalog("' OR 1=1 --","all","all",1,"name").getInt("total"))
            assertEquals(42,store.catalog("","ani","series",1,"name").getInt("total"))
            store.upsert(listOf(Title("luffy","1","Renamed")),300)
            assertEquals(86,store.catalog("","all","all",1,"recent").getInt("total"))
        }
        Store(path).use { store -> assertEquals("Renamed",store.find("luffy","1")!!.name) }
    }
    @Test fun genresMergeAcrossFeedsAndFilterWithoutHidingUnknownTitles() {
        Store(Files.createTempDirectory("catalog-genres").resolve("db")).use { store ->
            store.upsert(listOf(Title("ani","1","One",genres=listOf("Action")),Title("luffy","2","Unknown")))
            store.upsert(listOf(Title("ani","1","One",genres=listOf("Comedy"))))
            val matches=store.catalog("","all","all",1,"name","Action")
            assertEquals(1,matches.getInt("total"));assertEquals(2,matches.getJSONArray("genres").length())
            assertEquals(listOf("Action","Comedy"),store.find("ani","1")!!.genres)
            assertEquals(2,store.catalog("","all","all",1,"name").getInt("total"))
            val movieOnly=store.catalog("","luffy","all",1,"name","Action")
            assertEquals(0,movieOnly.getInt("total"))
            assertEquals(0,movieOnly.getJSONArray("genres").length())
            assertEquals(1,movieOnly.getJSONObject("coverage").getInt("untagged"))
            assertEquals(1,store.catalog("","all","all",1,"name","__untagged__").getInt("total"))
            assertEquals(1,store.catalog("One","all","all",1,"name").getJSONObject("coverage").getInt("tagged"))
            val untagged=store.nextEnrichment(86400001)!!
            assertEquals("2",untagged.id)
            store.markEnrichment(untagged,86400001)
            assertNull(store.nextEnrichment(86400002))
        }
    }
    @Test fun checkpointsAdvanceOnlyAfterSuccessAndRetainOldListings() {
        val path=Files.createTempDirectory("catalog-checkpoint").resolve("catalog.sqlite")
        Store(path).use { store ->
            val feed=store.due(100)!!
            store.recordPage(feed,BrowsePage(listOf(Title("ani","a","A")),true,341),100)
            val saved=store.status().getJSONArray("feeds").objects().first { it.getString("name")==feed.getString("name") }
            assertEquals(1,saved.getInt("page"));assertEquals(341,saved.getInt("totalPages"))
            store.fail(feed.getString("name"),200)
            assertNotNull(store.find("ani","a"))
            assertTrue(store.status().getJSONArray("feeds").objects().first { it.getString("name")==feed.getString("name") }.getString("error").isNotEmpty())
        }
        Store(path).use { store ->
            val feed=store.due(1000000)!!
            // Three untouched feeds still precede the successful one. No reset on restart.
            assertEquals(1,feed.getInt("page"))
            assertEquals(1,store.catalog("","all","all",1,"name").getInt("total"))
        }
    }
    @Test fun missingGenresNeverBlockFullAnimeDirectory() {
        Store(Files.createTempDirectory("catalog-full-anime").resolve("db")).use { store ->
            store.upsert(listOf(Title("ani","untagged","No genres supplied")))
            val feed=store.due(100)!!
            assertEquals("Anime A–Z",feed.getString("name"))
            val first=listOf(Title("ani","page-one","First"))
            store.recordPage(feed,BrowsePage(first,true,3),100)
            feed.put("page",2).put("last_fingerprint",Providers.md5("page-one".toByteArray()))
            assertThrows(IllegalStateException::class.java) { store.recordPage(feed,BrowsePage(first,true,3),200) }
            fun directory()=store.status().getJSONArray("feeds").objects().first { it.getString("name")=="Anime A–Z" }
            assertEquals(1,directory().getInt("page"))
            assertEquals(0,directory().getLong("completedAt"))
            store.recordPage(feed,BrowsePage(listOf(Title("ani","page-two","Second")),true,3),300)
            feed.put("page",3).put("last_fingerprint",Providers.md5("page-two".toByteArray()))
            store.recordPage(feed,BrowsePage(listOf(Title("ani","page-three","Third")),false,3),400)
            assertEquals(3,directory().getInt("page"))
            assertEquals(400,directory().getLong("completedAt"))
            // The full directory can finish even when the source leaves a title untagged.
            assertEquals(4,store.catalog("","ani","all",1,"name","__untagged__").getInt("total"))
        }
    }
    @Test fun repeatedDiscoveryPagesStopWithoutClaimingGlobalCompleteness() {
        Store(Files.createTempDirectory("catalog-repeat").resolve("db")).use { store ->
            val feed=JSONObject().put("name","Luffy · Movies (English)").put("provider","luffy").put("feed","movie").put("page",1).put("last_fingerprint","").put("cycle_start",0)
            val items=listOf(Title("luffy","1","Movie"))
            store.recordPage(feed,BrowsePage(items,true),100)
            feed.put("page",2).put("last_fingerprint",Providers.md5("1".toByteArray()))
            store.recordPage(feed,BrowsePage(items,true),200)
            val status=store.status().getJSONArray("feeds").objects().first { it.getString("name")=="Luffy · Movies (English)" }
            assertEquals(200,status.getLong("completedAt"));assertTrue(status.getLong("nextRun")>200)
            assertEquals(1,store.catalog("","luffy","all",1,"name").getInt("total"))
        }
    }
    @Test fun tvRelayRewritesAudioKeysAndRelativeSegments() {
        val playlist="#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio/list.m3u8?x=1\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key\"\nvideo/720.m3u8\n"
        val urls=mutableListOf<String>()
        val rewritten=MediaRelay.rewritePlaylist(playlist,"https://cdn.example.com/show/master.m3u8") {url -> urls+=url;"/media/session/${urls.size}"}
        assertEquals(listOf("https://cdn.example.com/show/audio/list.m3u8?x=1","https://cdn.example.com/key","https://cdn.example.com/show/video/720.m3u8"),urls)
        assertTrue(rewritten.contains("URI=\"/media/session/1\""))
        assertTrue(rewritten.contains("URI=\"/media/session/2\""))
        assertTrue(rewritten.contains("\n/media/session/3\n"))
        assertEquals("WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nHello",MediaRelay.srtToVtt("1\r\n00:00:01,000 --> 00:00:02,500\r\nHello"))
    }
    @Test fun httpRequiresAuthenticationValidatesInputAndServesCachedEpisodes() {
        Store(Files.createTempDirectory("catalog-http").resolve("db")).use { store ->
            val title=Title("ani","test-1","Test")
            store.upsert(listOf(title));store.saveDetails(Details(title,"Synopsis",listOf(Episode("1",1,"1"))),System.currentTimeMillis())
            val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
            server.createContext("/",CatalogApi(store,"test-access-key-only")::handle);server.start()
            try {
                fun get(path: String,auth: Boolean=true): HttpResponse<String> {
                    val req=HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}$path"))
                    if(auth) req.header("Authorization","Bearer test-access-key-only")
                    return HttpClient.newHttpClient().send(req.build(),HttpResponse.BodyHandlers.ofString())
                }
                assertEquals(401,get("/v1/catalog",false).statusCode())
                assertEquals(400,get("/v1/catalog?page=-1").statusCode())
                assertEquals(400,get("/v1/catalog?sort=invalid").statusCode())
                assertEquals(200,get("/v1/catalog").statusCode())
                val details=get("/v1/details?provider=ani&id=test-1")
                assertEquals(200,details.statusCode());assertEquals(1,JSONObject(details.body()).getJSONArray("episodes").length())
                assertEquals(404,get("/v1/details?provider=ani&id=missing").statusCode())
            } finally { server.stop(0) }
        }
    }
    @org.junit.Test fun qualityLimitKeepsAudioAndSubtitles() {
        val master="#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio.m3u8\"\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",URI=\"en.m3u8\"\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080,AUDIO=\"a\"\n1080.m3u8\n#EXT-X-STREAM-INF:RESOLUTION=1280x720,AUDIO=\"a\"\n720.m3u8\n"
        val limited=MediaRelay.limitQuality(master,720)
        org.junit.Assert.assertFalse(limited.contains("1080.m3u8"))
        org.junit.Assert.assertTrue(limited.contains("720.m3u8"))
        org.junit.Assert.assertTrue(limited.contains("audio.m3u8"))
        org.junit.Assert.assertTrue(limited.contains("en.m3u8"))
        org.junit.Assert.assertEquals(master,MediaRelay.limitQuality(master,0))
    }

}
