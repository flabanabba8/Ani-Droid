package dev.anidroid.server

import dev.anidroid.*
import org.json.JSONObject
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Calls the pinned Luffy engine without a shell, player, or interactive terminal. */
class Luffy(private val executable: String = Path.of(System.getProperty("user.home"),".local","bin","ani-luffy-bridge").toString()) {
    // Four processes in all, but indexing and genre repair may hold only two, so playback and search
    // never wait behind the background workers.
    private val slots=Semaphore(4)
    private val background=Semaphore(2)
    fun call(request: JSONObject,low: Boolean = false): JSONObject {
        if(low) check(background.tryAcquire(60,TimeUnit.SECONDS)) { "Luffy background work is busy." }
        try {
            return callForeground(request)
        } finally { if(low) background.release() }
    }
    private fun callForeground(request: JSONObject): JSONObject {
        check(slots.tryAcquire(5,TimeUnit.SECONDS)) { "Luffy is busy; try again shortly." }
        try {
            val process=ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                process.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                val result=CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(4*1024*1024+1) } }
                check(process.waitFor(95,TimeUnit.SECONDS)) { "Luffy timed out; please retry." }
                val bytes=result.get(2,TimeUnit.SECONDS)
                check(bytes.size<=4*1024*1024) { "Luffy response was too large." }
                val response=JSONObject(String(bytes,Charsets.UTF_8))
                check(!response.has("error") && process.exitValue()==0) { response.optString("error","Luffy request failed.") }
                return response
            } finally { if(process.isAlive) process.destroyForcibly() }
        } finally { slots.release() }
    }
    fun search(query: String,page: Int) = call(JSONObject().put("action","search").put("query",query).put("page",page))
    fun browse(page: Int,kind: String): BrowsePage {
        val result=call(JSONObject().put("action","browse").put("kind",kind).put("page",page),low=true)
        return BrowsePage(result.array("items").objects().map(::titleFromJson),result.getBoolean("hasMore"),result.getInt("totalPages"))
    }
    fun details(title: Title) = detailsFromJson(call(JSONObject().put("action","details").put("id",title.id)))
    fun genres(title: Title) = titleFromJson(call(JSONObject().put("action","genres").put("id",title.id),low=true))
    // Cinejoy is disabled on purpose: its resolver is down and runs remote WASM. "auto" asks the
    // bridge for VixSrc, then LookMovie, so the bridge's Cinejoy-first path is never taken.
    fun streams(title: Title,season: Int,episode: Int,requested: String = "auto"): JSONObject {
        val source=if(requested=="cinejoy") "auto" else requested // saved choices from before it was disabled
        val order=if(source=="auto") listOf("vixsrc","lookmovie") else listOf(source)
        var failure: IllegalStateException? = null
        for(candidate in order) try { return streamsFrom(title,season,episode,candidate) } catch(e: IllegalStateException) { failure=e }
        throw failure ?: IllegalStateException("Selected sources could not resolve this title.")
    }
    private fun streamsFrom(title: Title,season: Int,episode: Int,source: String) = call(JSONObject().put("action","streams").put("id",title.id).put("title",title.name)
        .put("source",source).put("year",Regex("\\b\\d{4}\\b").find(title.info)?.value.orEmpty()).put("season",season).put("episode",episode))
}
