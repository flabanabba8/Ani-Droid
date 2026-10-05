package dev.anidroid

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

// Provider behavior adapted from ani-cli (GPL-3.0+). Luffy runs through the catalog server.
// Pinned source revisions and license texts are bundled in assets/licenses.
data class Title(val provider: String, val id: String, val name: String, val poster: String = "", val info: String = "", val series: Boolean = true, val indexedAt: Long = 0, val genres: List<String> = emptyList())
data class Episode(val id: String, val season: Int, val number: String, val name: String = "") {
    val label get() = if (season == 0) "Watch movie" else "${if (season > 1) "S$season · " else ""}Episode $number${if (name.isBlank()) "" else " · $name"}"
}
data class Audio(val id: String, val label: String)
data class Details(val title: Title, val description: String, val episodes: List<Episode>, val audio: List<Audio> = emptyList())
data class GenreFeed(val path: String, val label: String)
data class BrowsePage(val titles: List<Title>, val hasNext: Boolean, val totalPages: Int = 0, val genreFeeds: List<GenreFeed> = emptyList())
data class Caption(val name: String, val url: String)
data class Stream(val label: String, val url: String, val headers: Map<String, String>, val captions: List<Caption> = emptyList(), val height: Int = 0, val sources: List<String> = emptyList())
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()
fun webUrl(url: String): String {
    require(url.toHttpUrl().scheme in listOf("https", "http")) { "Unsupported media URL" }
    return url
}

class Providers {
    val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    private val animeBase = "https://hianime.at"
    private val browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    fun fetch(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder().url(webUrl(url)).header("User-Agent", browserUa).apply { headers.forEach { (k,v) -> header(k,v) } }.build()
        return http.newCall(request).execute().use {
            check(it.isSuccessful) { "Provider returned HTTP ${it.code}. Try again later." }
            it.body.string().also { body -> check(!body.contains("<title>Just a moment")) { "Provider is blocked by Cloudflare. Try Luffy or retry later." } }
        }
    }
    fun search(provider: String, query: String, page: Int = 1): List<Title> {
        require(query.isNotBlank())
        if (provider == "ani") {
            val url = "$animeBase/search".toHttpUrl().newBuilder().addQueryParameter("keyword", query).addQueryParameter("page", "$page").build()
            return parseAnimeSearch(fetch(url.toString()), animeBase)
        }
        error("This provider uses the catalog server.")
    }
    // Public directory/feed pagination, used by the server's paced indexer.
    fun browse(provider: String, page: Int, feed: String = "2"): BrowsePage {
        require(page in 1..10000)
        if (provider == "ani") {
            require(feed == "az" || feed.matches(Regex("genres/[a-z0-9-]+")))
            val html = fetch("$animeBase/${if(feed == "az") "az-list" else feed}?page=$page")
            val doc = Jsoup.parse(html, animeBase)
            val pages = doc.select(".pagination a[href]").mapNotNull {
                it.absUrl("href").toHttpUrl().queryParameter("page")?.toIntOrNull()
            }
            val genreFeeds = doc.select("a[href*=/genres/]").mapNotNull {
                val path=it.absUrl("href").toHttpUrl().encodedPath.trim('/')
                if(!path.matches(Regex("genres/[a-z0-9-]+"))) null else GenreFeed(path,it.attr("title").ifBlank { it.text() }.trim())
            }.filter { it.label.isNotEmpty() }.distinctBy { it.path }
            val genre=genreFeeds.firstOrNull { it.path == feed }?.label
            val titles = parseAnimeSearch(html, animeBase).map { if(genre == null) it else it.copy(genres=parseGenres(genre)) }
            check(titles.isNotEmpty() || feed != "az") { "The anime directory returned no titles; keeping the previous catalog." }
            val lastPage = maxOf(page, pages.maxOrNull() ?: page)
            return BrowsePage(titles, titles.isNotEmpty() && page < lastPage, lastPage, if(feed == "az") genreFeeds else emptyList())
        }
        error("This provider uses the catalog server.")
    }
    fun genreMetadata(title: Title): Title {
        if(title.provider == "ani") {
            val doc=Jsoup.parse(fetch("$animeBase/${title.id}"))
            check(doc.selectFirst(".anisc-info") != null) { "Title metadata is unavailable" }
            return title.copy(genres=doc.select(".anisc-info a[href*=/genres/]").flatMap { parseGenres(it.attr("title").ifBlank { it.text() }) }.distinct())
        }
        error("This provider uses the catalog server.")
    }
    fun details(title: Title): Details {
        if (title.provider == "ani") {
            val payload = JSONObject(fetch("$animeBase/api/theme/episode/list/${title.id.substringAfterLast('-')}"))
            val eps = Jsoup.parse(payload.optString("html")).select(".ep-item").map { Episode(it.attr("data-id"), 1, it.attr("data-number"), it.attr("title")) }.filter { it.id.isNotBlank() }
            check(eps.isNotEmpty()) { "No episodes available for this title." }
            val genres = if(title.genres.isNotEmpty()) title.genres else runCatching {
                val doc=Jsoup.parse(fetch("$animeBase/${title.id}"))
                doc.select(".anisc-info a[href*=/genres/]").map { it.text() }.flatMap { parseGenres(it) }.distinct()
            }.getOrDefault(emptyList())
            return Details(title.copy(genres=genres), "Choose an episode, then select sub or dub and video quality.", eps, listOf(Audio("sub", "Subtitled"), Audio("dub", "Dubbed")))
        }
        error("This provider uses the catalog server.")
    }
    fun streams(title: Title, episode: Episode, audio: String): List<Stream> {
        require(title.provider == "ani") { "This provider uses the catalog server." }
        return animeStreams(episode, audio)
    }
    private fun animeStreams(ep: Episode, audio: String): List<Stream> {
        val servers = JSONObject(fetch("$animeBase/api/theme/episode/servers?episodeId=${ep.id}"))
        val item = Jsoup.parse(servers.optString("html")).select(".server-item").firstOrNull { it.attr("data-type") == audio && it.attr("data-server-name") == "ZokoAnime" }
        check(item != null) { "This episode has no ${if (audio == "dub") "dubbed" else "subtitled"} stream." }
        val embed = String(Base64.getDecoder().decode(item.attr("data-hash")))
        val blob = Regex("window\\.__P=\"([^\"]+)\"").find(fetch(embed))?.groupValues?.get(1)
        check(blob != null) { "The anime provider changed its player. An app update may be needed." }
        val config = decodeAnimeBlob(blob)
        val master = config.getString("src")
        val referer = URI(embed).let { "${it.scheme}://${it.authority}/" }
        val headers = mapOf("Referer" to referer, "User-Agent" to browserUa)
        val captions = config.array("subtitles").objects().mapNotNull { s -> s.optString("src").takeIf { it.startsWith("https://") }?.let { Caption(s.optString("label", "English"), it) } }
        return listOf(Stream("Auto",master,headers,captions)) + parsePlaylist(master, fetch(master, headers), headers, captions)
    }
    companion object {
        fun parseGenres(value: Any?): List<String> {
            val raw=when(value) {
                is JSONArray -> (0 until value.length()).mapNotNull { when(val v=value.opt(it)) { is String -> v; is JSONObject -> v.optString("name").ifBlank { null }; else -> null } }
                is String -> listOf(value)
                else -> emptyList()
            }
            return raw.flatMap { it.split(',', '|') }.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 80 }
                .map { if(it.equals("Science Fiction",true) || it.equals("Sci-fi",true)) "Sci-Fi" else it }.distinct()
        }
        fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        fun parseAnimeSearch(html: String, base: String): List<Title> {
            val doc = Jsoup.parse(html, base); doc.select("#main-sidebar").remove()
            return doc.select(".flw-item").mapNotNull { el ->
                val a = el.selectFirst(".film-name a") ?: return@mapNotNull null
                val id = a.attr("href").substringBefore('?').trimEnd('/').substringAfterLast('/')
                val img = el.selectFirst("img")
                Title("ani", id, a.attr("title").ifBlank { a.text() }, img?.attr("data-src")?.ifBlank { img.attr("src") }.orEmpty(), el.select(".film-detail .fd-infor").text(), !el.select(".fdi-item").any { it.text().equals("Movie", true) })
            }.distinctBy { it.id }
        }
        fun decodeAnimeBlob(blob: String): JSONObject {
            val key = "otaku-embed-v1".toByteArray()
            return JSONObject(String(Base64.getDecoder().decode(blob).mapIndexed { i, b -> (b.toInt() xor key[i % key.size].toInt()).toByte() }.toByteArray(), Charsets.UTF_8))
        }
        fun parsePlaylist(master: String, text: String, headers: Map<String,String>, captions: List<Caption>): List<Stream> {
            val lines = text.lines().map { it.trim() }
            return lines.mapIndexedNotNull { i, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:")) null else {
                    val next = lines.drop(i+1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return@mapIndexedNotNull null
                    val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    Stream(if (height > 0) "${height}p" else "Auto", URI(master).resolve(next).toString(), headers, captions, height)
                }
            }.sortedByDescending { it.height }.ifEmpty { listOf(Stream("Auto", master, headers, captions)) }
        }
    }
}
