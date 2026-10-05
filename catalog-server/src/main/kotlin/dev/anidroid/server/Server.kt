package dev.anidroid.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import dev.anidroid.*
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class CatalogApi(private val store: Store, private val token: String, private val providers: Providers = Providers(), private val luffy: Luffy = Luffy(), private val relay: MediaRelay? = null) {
    fun handle(exchange: HttpExchange) {
        exchange.use { e ->
            try {
                if(!MessageDigest.isEqual(e.requestHeaders.getFirst("Authorization").orEmpty().toByteArray(), "Bearer $token".toByteArray())) { reply(e,401,JSONObject().put("error","Catalog access key required."));return }
                if(e.requestMethod != "GET") { reply(e,405,JSONObject().put("error","GET required."));return }
                require(e.requestURI.toString().length <= 2048)
                val params = e.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                    URLDecoder.decode(it.substringBefore('='),"UTF-8") to URLDecoder.decode(it.substringAfter('=',""),"UTF-8")
                }
                val result = when(e.requestURI.path) {
                    "/v1/status" -> store.status()
                    "/v1/catalog" -> store.catalog(params["q"].orEmpty(),params["provider"] ?: "all",params["kind"] ?: "all",params["page"]?.toInt() ?: 1,params["sort"] ?: "name",params["genre"].orEmpty())
                    "/v1/search" -> {
                        val provider=params["provider"].orEmpty();require(provider in listOf("ani","luffy"))
                        val query=params["q"].orEmpty();val page=params["page"]?.toInt() ?: 1
                        require(query.isNotBlank() && query.length<=200 && page in 1..500)
                        val result=if(provider=="luffy") luffy.search(query,page) else {
                            val found=providers.search("ani",query,page)
                            JSONObject().put("items",org.json.JSONArray(found.map {it.toJson()})).put("hasMore",found.isNotEmpty())
                        }
                        store.upsert(result.array("items").objects().map(::titleFromJson))
                        result
                    }
                    "/v1/streams" -> {
                        val provider=params["provider"].orEmpty();require(provider in listOf("ani","luffy"))
                        val id=params["id"].orEmpty();require(id.matches(Regex("[A-Za-z0-9_-]{1,200}")))
                        val title=store.find(provider,id) ?: throw IllegalArgumentException("Unknown title")
                        val season=params["season"]?.toInt() ?: 0
                        val episode=params["episode"].orEmpty();require(season in 0..1000 && episode.matches(Regex("[0-9]{1,5}(\\.[0-9]+)?")))
                        val source=params["source"] ?: "auto";require(source in listOf("auto","cinejoy","vixsrc","lookmovie"))
                        val result=if(provider=="luffy") luffy.streams(title,season,episode.toInt(),source) else {
                            val details=store.cachedDetails(provider,id)?.first ?: providers.details(title).also {store.saveDetails(it,System.currentTimeMillis())}
                            val ep=details.episodes.firstOrNull {it.season==season && it.number==episode} ?: throw IllegalArgumentException("Unknown episode")
                            val streams=providers.streams(title,ep,if(params["audio"]=="dub") "dub" else "sub")
                            JSONObject().put("streams",org.json.JSONArray(streams.map {it.toJson()}))
                        }
                        if(params["tv"]=="true") {
                            val media=checkNotNull(relay) {"TV media relay is not configured."}
                            result.array("streams").objects().forEach {stream ->
                                val height=params["height"]?.toInt() ?: 0;require(height in 0..4320)
                                val session=media.open(streamFromJson(stream),height)
                                stream.put("relayPath",session.getString("relayPath")).put("relayPort",media.port).put("captions",session.getJSONArray("captions")).put("heights",session.getJSONArray("heights"))
                            }
                        }
                        result
                    }
                    "/v1/details" -> {
                        val p=params["provider"].orEmpty();val id=params["id"].orEmpty()
                        require(p in listOf("ani","luffy") && id.matches(Regex("[A-Za-z0-9_-]{1,200}")))
                        val title = store.find(p,id)
                        if(title == null) { reply(e,404,JSONObject().put("error","Title is not in this catalog."));return }
                        val cached = store.cachedDetails(p,id)
                        val now=System.currentTimeMillis()
                        if(cached != null && now-cached.second < 86400000) cached.first.copy(title=title).toJson().put("cachedAt",cached.second).put("stale",false)
                        else try {
                            val details = if(p=="luffy") luffy.details(title) else providers.details(title)
                            store.saveDetails(details,now)
                            details.toJson().put("cachedAt",now).put("stale",false)
                        } catch(e: Exception) {
                            if(cached == null) throw e
                            cached.first.copy(title=title).toJson().put("cachedAt",cached.second).put("stale",true)
                        }
                    }
                    else -> { reply(e,404,JSONObject().put("error","Not found."));return }
                }
                reply(e,200,result)
            } catch(e: IllegalArgumentException) { reply(e = exchange,400,JSONObject().put("error","Invalid catalog request.")) }
            catch(e: IllegalStateException) { reply(e = exchange,503,JSONObject().put("error",e.message?.takeIf { !it.contains("http") }?.take(200) ?: "Provider unavailable; retry later.")) }
            catch(e: Exception) { reply(e = exchange,503,JSONObject().put("error","Provider details are temporarily unavailable. Try Live search or retry later.")) }
        }
    }
    private fun reply(e: HttpExchange,code: Int,json: JSONObject) {
        val body=json.toString().toByteArray(Charsets.UTF_8)
        e.responseHeaders.set("Content-Type","application/json; charset=utf-8")
        e.responseHeaders.set("Cache-Control","no-store")
        e.responseHeaders.set("X-Content-Type-Options","nosniff")
        e.sendResponseHeaders(code,body.size.toLong());e.responseBody.write(body)
    }
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: catalog-server /path/to/private/config.json" }
    val file=Path.of(args.single()).toAbsolutePath();val config=JSONObject(Files.readString(file));val state=file.parent
    val password=config.getString("keystorePassword").toCharArray()
    val keys=KeyStore.getInstance("PKCS12").apply { Files.newInputStream(state.resolve("server.p12")).use { load(it,password) } }
    val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys,password) }
    val tls=SSLContext.getInstance("TLS").apply { init(km.keyManagers,null,null) }
    val store=Store(state.resolve("catalog.sqlite"))
    val server=HttpsServer.create(InetSocketAddress(config.optString("bind","127.0.0.1"),config.optInt("port",8765)),32)
    server.httpsConfigurator=HttpsConfigurator(tls)
    val executor=Executors.newFixedThreadPool(4)
    server.executor=executor
    val luffy=Luffy(config.optString("luffyBridge",Path.of(System.getProperty("user.home"),".local","bin","ani-luffy-bridge").toString()))
    val relay=config.optInt("tvRelayPort",0).takeIf {it>0}?.let {MediaRelay(config.optString("bind","127.0.0.1"),it)}
    val api=CatalogApi(store,config.getString("token"),luffy=luffy,relay=relay)
    server.createContext("/",api::handle)
    val indexer=Executors.newSingleThreadScheduledExecutor()
    val provider=Providers()
    indexer.scheduleWithFixedDelay({
        val now=System.currentTimeMillis();val feed=store.due(now)
        if(feed != null) {
            try {
                val result=if(feed.getString("provider")=="luffy") luffy.browse(feed.getInt("page"),feed.getString("feed")) else provider.browse("ani",feed.getInt("page"),feed.getString("feed"))
                store.recordPage(feed,result,System.currentTimeMillis())
                println("Indexed ${feed.getString("name")} page ${feed.getInt("page")}: ${result.titles.size} listings")
            } catch(e: Exception) {
                store.fail(feed.getString("name"),now)
                System.err.println("Index postponed for ${feed.getString("name")}: ${e.javaClass.simpleName}")
            }
        }
    },0,config.optLong("requestIntervalSeconds",10).coerceAtLeast(10),TimeUnit.SECONDS)
    // Dedicated bounded workers repair metadata coverage instead of giving it one in
    // three browse slots. Each request reads one title, without loading its episodes.
    val metadata=Executors.newScheduledThreadPool(4)
    for(source in listOf("ani","luffy")) repeat(2) { worker ->
        val client=Providers()
        var backoffUntil=0L
        metadata.scheduleWithFixedDelay({
            val now=System.currentTimeMillis()
            if(now>=backoffUntil) {
                val title=store.nextEnrichment(now,source)
                if(title!=null) try { store.upsert(listOf(if(source=="luffy") luffy.genres(title) else client.genreMetadata(title)),System.currentTimeMillis()) }
                catch(e: Exception) {
                    // Retain existing tags and retry transient failures after fifteen minutes.
                    store.markEnrichment(title,now-86400000+900000)
                    backoffUntil=now+30000
                    System.err.println("Genre metadata retry scheduled for $source: ${e.javaClass.simpleName}")
                }
            }
        },worker*500L,config.optLong("metadataIntervalMillis",1000).coerceAtLeast(1000),TimeUnit.MILLISECONDS)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        indexer.shutdownNow();metadata.shutdownNow();relay?.close();server.stop(1);executor.shutdownNow()
        indexer.awaitTermination(40,TimeUnit.SECONDS);metadata.awaitTermination(40,TimeUnit.SECONDS);executor.awaitTermination(40,TimeUnit.SECONDS);store.close()
    })
    server.start()
    println("Ani-Droid catalog is running with HTTPS. Database and checkpoints are persistent.")
}
