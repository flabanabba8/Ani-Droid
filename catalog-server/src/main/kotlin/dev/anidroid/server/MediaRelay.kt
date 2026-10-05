package dev.anidroid.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.anidroid.Stream
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Optional private-LAN media relay for TVs that cannot attach provider headers. */
class MediaRelay(bind: String, val port: Int) : AutoCloseable {
    private class Session(val stream: Stream,val height: Int) {
        val resources=ConcurrentHashMap<String,String>()
        @Volatile var expires=System.currentTimeMillis()+20*60*1000
    }
    private val sessions=ConcurrentHashMap<String,Session>()
    private val random=SecureRandom()
    private val pool=Executors.newFixedThreadPool(8)
    private val http=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).followRedirects(false)
        .dns { host -> Dns.SYSTEM.lookup(host).filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || (it.address.size==16 && (it.address[0].toInt() and 0xfe)==0xfc) }.also { check(it.isNotEmpty()) { "Private media address refused" } } }.build()
    private val server=HttpServer.create(InetSocketAddress(bind,port),32).apply { executor=pool;createContext("/media/",::serve);start() }
    private fun token() = ByteArray(18).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun open(stream: Stream,height: Int = 0): org.json.JSONObject {
        require(height in 0..4320)
        val upstream=URI(stream.url);require(upstream.scheme=="https" && upstream.host!=null && upstream.userInfo==null)
        sessions.entries.removeIf { it.value.expires<System.currentTimeMillis() }
        if(sessions.size>=32) sessions.entries.minByOrNull { it.value.expires }?.let { sessions.remove(it.key) }
        val request=Request.Builder().url(stream.url).apply {stream.headers.forEach { (k,v)->header(k,v)} }.build()
        val heights=if(java.net.URI(stream.url).path.endsWith(".mp4")) listOfNotNull(stream.height.takeIf {it>0}) else http.newBuilder().followRedirects(true).followSslRedirects(false).callTimeout(8,TimeUnit.SECONDS).build().newCall(request).execute().use {response ->
            check(response.isSuccessful) {"Media playlist is unavailable"}
            val bytes=response.body.byteStream().use {it.readNBytes(4*1024*1024+1)};check(bytes.size<=4*1024*1024)
            Regex("RESOLUTION=[0-9]+x([0-9]+)").findAll(String(bytes)).map {it.groupValues[1].toInt()}.distinct().sorted().toList()
        }
        check(height==0 || heights.isEmpty() || heights.any {it<=height}) {"Requested quality is unavailable. Choose Auto or a higher resolution."}
        val id=token();val session=Session(stream,height);sessions[id]=session
        return org.json.JSONObject().put("relayPath",register(id,session,stream.url)).put("relayPort",port)
            .put("heights",org.json.JSONArray(heights)).put("captions",org.json.JSONArray(stream.captions.map { org.json.JSONObject().put("name",it.name).put("relayPath",register(id,session,it.url)) }))
    }
    private fun register(id: String,session: Session,url: String): String {
        val uri=URI(url);require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null)
        check(session.resources.size<20000) { "Playlist is too large" }
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        session.resources[key]=url
        return "/media/$id/$key"
    }
    private fun serve(e: HttpExchange) {
        e.use {
            try {
                if(e.requestMethod !in listOf("GET","HEAD")) { e.sendResponseHeaders(405,-1);return }
                val parts=e.requestURI.path.split('/')
                val session=parts.getOrNull(2)?.let(sessions::get)
                if(session==null || session.expires<System.currentTimeMillis()) {e.sendResponseHeaders(404,-1);return}
                val id=parts[2];var url=session.resources[parts.getOrNull(3)] ?: run {e.sendResponseHeaders(404,-1);return}
                session.expires=System.currentTimeMillis()+20*60*1000
                for(redirect in 0..4) {
                    val request=Request.Builder().url(url).apply {
                        session.stream.headers.filterKeys { it.lowercase() in listOf("referer","origin","user-agent","cookie") }.forEach { (k,v)->header(k,v) }
                        e.requestHeaders.getFirst("Range")?.takeIf { it.matches(Regex("bytes=[0-9]+-[0-9]*")) }?.let { header("Range",it) }
                    }.build()
                    http.newCall(request).execute().use { response ->
                        if(response.code in 300..399) {url=URI(url).resolve(response.header("Location") ?: error("Missing redirect")).toString();require(URI(url).scheme=="https");return@use}
                        e.responseHeaders.set("Access-Control-Allow-Origin","*")
                        e.responseHeaders.set("Cache-Control","private, no-store")
                        val type=response.header("Content-Type").orEmpty()
                        val playlist=type.contains("mpegurl",true) || URI(url).path.endsWith(".m3u8")
                        if(playlist && response.isSuccessful) {
                            val bytes=response.body.byteStream().use { it.readNBytes(4*1024*1024+1) };check(bytes.size<=4*1024*1024)
                            val rewritten=rewritePlaylist(limitQuality(String(bytes),session.height),url) { child -> register(id,session,child) }.toByteArray()
                            e.responseHeaders.set("Content-Type","application/vnd.apple.mpegurl")
                            e.sendResponseHeaders(200,if(e.requestMethod=="HEAD") -1 else rewritten.size.toLong())
                            if(e.requestMethod!="HEAD") e.responseBody.write(rewritten)
                        } else if(URI(url).path.endsWith(".srt",true) && response.isSuccessful) {
                            val bytes=response.body.byteStream().use {it.readNBytes(4*1024*1024+1)};check(bytes.size<=4*1024*1024)
                            val converted=srtToVtt(String(bytes,Charsets.UTF_8)).toByteArray()
                            e.responseHeaders.set("Content-Type","text/vtt; charset=utf-8")
                            e.sendResponseHeaders(200,if(e.requestMethod=="HEAD") -1 else converted.size.toLong())
                            if(e.requestMethod!="HEAD")e.responseBody.write(converted)
                        } else {
                            if(type.isNotBlank())e.responseHeaders.set("Content-Type",type)
                            for(header in listOf("Content-Range","Accept-Ranges")) response.header(header)?.let {e.responseHeaders.set(header,it)}
                            val length=response.body.contentLength()
                            e.sendResponseHeaders(response.code,if(e.requestMethod=="HEAD") -1 else if(length>0) length else 0)
                            if(e.requestMethod!="HEAD") response.body.byteStream().use { input -> input.copyTo(e.responseBody,64*1024) }
                        }
                        return
                    }
                }
                e.sendResponseHeaders(502,-1)
            } catch(_: Exception) {runCatching { e.sendResponseHeaders(502,-1) }}
        }
    }
    override fun close() {server.stop(1);pool.shutdownNow();sessions.clear()}
    companion object {
        fun limitQuality(text: String,height: Int): String {
            if(height==0)return text
            val lines=text.lines();val remove=mutableSetOf<Int>()
            lines.forEachIndexed { i,line -> if(line.startsWith("#EXT-X-STREAM-INF:")) {
                val h=Regex("RESOLUTION=[0-9]+x([0-9]+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                if(h!=null && h>height) {remove+=i;var n=i+1;while(n<lines.size && (lines[n].isBlank() || lines[n].startsWith('#')))n++;if(n<lines.size)remove+=n}
            } }
            return lines.filterIndexed {i,_->i !in remove}.joinToString("\n")
        }
        fun srtToVtt(text: String) = "WEBVTT\n\n" + Regex("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})").replace(text.trimStart('\uFEFF').replace("\r\n","\n"),"$1.$2")
        fun rewritePlaylist(text: String,base: String,map: (String)->String): String = text.lineSequence().joinToString("\n") { line ->
            if(line.startsWith('#')) Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                val raw=match.groupValues[1]
                if(raw.startsWith("data:")) match.value else "URI=\"${map(URI(base).resolve(raw).toString())}\""
            } else if(line.isBlank()) line else map(URI(base).resolve(line.trim()).toString())
        }
    }
}
