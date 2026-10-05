package dev.anidroid

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.util.Base64

class ProviderTest {
    @Test fun genresHandleProviderStringsArraysAndMissingMetadata() {
        assertEquals(listOf("Action","Sci-Fi"),Providers.parseGenres("Action, Science Fiction"))
        assertEquals(listOf("Comedy","Drama"),Providers.parseGenres(org.json.JSONArray("[\"Comedy\",{\"name\":\"Drama\"},42]")))
        assertEquals(emptyList<String>(),Providers.parseGenres(null))
    }
    @Test fun animeSearchExcludesSidebarAndDecodesTitles() {
        val card = """<div class="flw-item"><img data-src="https://example.com/a.jpg"><div class="film-name"><a href="/watch/test-123" title="A &amp; B">A</a></div></div>"""
        val result = Providers.parseAnimeSearch(card + "<div id='main-sidebar'>$card</div>", "https://example.com")
        assertEquals(1, result.size); assertEquals("test-123", result.single().id); assertEquals("A & B", result.single().name)
    }
    @Test fun playlistsResolveRelativeAbsoluteAndQueryUrls() {
        val result = Providers.parsePlaylist("https://example.com/a/master.m3u8?token=x", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=1920x1080\n1080/index.m3u8?q=1\n#EXT-X-STREAM-INF:RESOLUTION=640x360\nhttps://cdn.example.com/low.m3u8", emptyMap(), emptyList())
        assertEquals(listOf(1080,360), result.map { it.height }); assertEquals("https://example.com/a/1080/index.m3u8?q=1", result[0].url)
        assertEquals("https://cdn.example.com/low.m3u8", result[1].url)
    }
    @Test fun decodingHandlesUnicode() {
        val json = """{"src":"https://example.com/master.m3u8","title":"日本語"}"""
        val key = "otaku-embed-v1".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray().mapIndexed { i,b -> (b.toInt() xor key[i%key.size].toInt()).toByte() }.toByteArray())
        assertEquals("日本語", Providers.decodeAnimeBlob(encoded).getString("title"))
    }
    @Test fun liveSearchEpisodesAndStreamResolution() {
        assumeTrue(System.getenv("ANI_DROID_LIVE") == "1")
        val providers = Providers()
        for (provider in System.getenv("ANI_DROID_LIVE_PROVIDER")?.let { listOf(it) } ?: listOf("ani")) {
            val titles = providers.search(provider, "cyberpunk edgerunners")
            assertTrue("$provider search", titles.isNotEmpty())
            val title = titles.firstOrNull { it.name.contains("Edgerunners", true) } ?: titles.first()
            val details = providers.details(title)
            assertTrue("$provider episodes", details.episodes.isNotEmpty())
            val streams = providers.streams(title, details.episodes.first(), details.audio.first().id)
            assertTrue("$provider streams", streams.isNotEmpty())
            val stream = streams.last()
            val body = providers.fetch(stream.url, stream.headers)
            assertTrue("$provider manifest", body.contains("#EXTM3U") || body.contains("<MPD"))
            println("$provider: search=${titles.size}, episodes=${details.episodes.size}, streams=${streams.size}, manifest verified")
            Thread.sleep(10000)
        }
    }
}
