package dev.anidroid

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import android.os.Bundle
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.ui.CaptionStyleCompat
import android.util.TypedValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF73E0C1), secondary = Color(0xFFAAB8FF), background = Color(0xFF0B101B), surface = Color(0xFF121B2B), surfaceVariant = Color(0xFF202C40), surfaceContainer = Color(0xFF121B2B), secondaryContainer = Color(0xFF25443F), onSecondaryContainer = Color(0xFF9FF5DB))) {
                Surface(Modifier.fillMaxSize()) { AniDroid() }
            }
        }
    }
}
data class Playing(val title: Title, val episode: Episode, val stream: Stream, val offlineId: String? = null, val audio: String = "original", val startPaused: Boolean = false, val source: String = "auto")
class LibraryModel(app: Application) : AndroidViewModel(app) {
    val providers = Providers()
    private val prefs = app.getSharedPreferences("library", 0)
    val subtitleSizes=listOf(16,20,24,28,32,36)
    val subtitleColors=listOf("White" to 0xFFFFFFFF.toInt(),"Yellow" to 0xFFFFEB3B.toInt(),"Mint" to 0xFF73E0C1.toInt(),"Cyan" to 0xFF80DEFF.toInt())
    var subtitleSize by mutableStateOf(prefs.getInt("subtitleSize",24).takeIf { it in subtitleSizes } ?: 24)
        private set
    var subtitleColor by mutableStateOf(prefs.getInt("subtitleColor",0xFFFFFFFF.toInt()).takeIf { value -> subtitleColors.any { it.second==value } } ?: 0xFFFFFFFF.toInt())
        private set
    fun nextSubtitleSize() { subtitleSize=subtitleSizes[(subtitleSizes.indexOf(subtitleSize)+1)%subtitleSizes.size];prefs.edit().putInt("subtitleSize",subtitleSize).apply() }
    fun nextSubtitleColor() { subtitleColor=subtitleColors[(subtitleColors.indexOfFirst { it.second==subtitleColor }+1)%subtitleColors.size].second;prefs.edit().putInt("subtitleColor",subtitleColor).apply() }
    var provider by mutableStateOf(prefs.getString("provider", "ani")!!.let { if(it=="movie") "luffy" else it })
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Title>>(emptyList())
    var details by mutableStateOf<Details?>(null)
    var episode by mutableStateOf<Episode?>(null)
    var audio by mutableStateOf("")
    var streams by mutableStateOf<List<Stream>>(emptyList())
    var playing by mutableStateOf<Playing?>(null)
    var busy by mutableStateOf(false)
    var loadingStage by mutableStateOf("Loading…")
    var error by mutableStateOf<String?>(null)
    var searched by mutableStateOf(false)
    private val connectionFile = java.io.File(app.filesDir, "catalog-connection.json")
    var catalogConnection by mutableStateOf(runCatching { CatalogConnection.fromJson(JSONObject(connectionFile.readText())) }.getOrNull())
    private var catalogClient = catalogConnection?.let { runCatching { CatalogClient(it) }.getOrNull() }
    var tab by mutableStateOf(if(catalogClient == null) "Search" else "Catalog")
    var catalogQuery by mutableStateOf("")
    var catalogProvider by mutableStateOf("all")
    var catalogKind by mutableStateOf("all")
    var catalogSort by mutableStateOf("name")
    var catalogGenre by mutableStateOf("")
    var genreCoverage by mutableStateOf(JSONObject())
    var catalogGenres by mutableStateOf<List<Pair<String,Int>>>(emptyList())
    var catalogItems by mutableStateOf<List<Title>>(emptyList())
    var catalogStatus by mutableStateOf(JSONObject())
    var catalogPage by mutableIntStateOf(1)
    var catalogTotal by mutableIntStateOf(0)
    var catalogMore by mutableStateOf(false)
    var showServerSettings by mutableStateOf(false)
    var detailNotice by mutableStateOf<String?>(null)
    var page by mutableIntStateOf(1)
    var more by mutableStateOf(false)
    var favorites by mutableStateOf(readTitles("favorites"))
    var history by mutableStateOf(readTitles("history"))
    var watchlist by mutableStateOf(readTitles("watchlist"))
    var recentPlays by mutableStateOf(runCatching { JSONArray(prefs.getString("recentPlays","[]")).objects().map { playingFromJson(it,it.optString("offlineId").takeIf(String::isNotBlank)) } }.getOrDefault(emptyList()))
    val continueWatching get() = recentPlays.filter { position(it)>0 && !isWatched(it.title,it.episode) }
    var showPreferences by mutableStateOf(runCatching { JSONObject(prefs.getString("showPreferences","{}")) }.getOrDefault(JSONObject()))
    var watched by mutableStateOf(runCatching { JSONArray(prefs.getString("watched","[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet()))
    var sourceMode by mutableStateOf("auto")
    /** A saved source that is down falls back to Auto for this request without erasing the saved choice. */
    private suspend fun luffyStreams(t: Title,e: Episode,source: String): List<Stream> {
        val client=requireCatalog()
        if(source=="auto") return client.streams(t,e,source)
        val found=try {client.streams(t,e,source)} catch(x: CancellationException) {throw x} catch(x: Exception) {emptyList()}
        if(found.isNotEmpty()) return found
        detailNotice="$source had no stream; used Auto instead."
        return client.streams(t,e,"auto")
    }
    private fun showKey(t: Title)="${t.provider}:${t.id}"
    fun showPreference(t: Title)=showPreferences.optJSONObject(showKey(t)) ?: JSONObject()
    fun rememberShow(t: Title,field: String,value: Any) { val copy=JSONObject(showPreferences.toString());val entry=copy.optJSONObject(showKey(t)) ?: JSONObject();entry.put(field,value);copy.put(showKey(t),entry);showPreferences=copy;prefs.edit().putString("showPreferences",copy.toString()).apply() }
    fun setSource(value: String) { require(value in listOf("auto","cinejoy","vixsrc","lookmovie"));sourceMode=value;details?.title?.let {rememberShow(it,"source",value)} }
    private fun watchedKey(t: Title,e: Episode)="${showKey(t)}:${e.season}:${e.number}"
    private val manuallyUnwatched=mutableSetOf<String>()
    fun isWatched(t: Title,e: Episode)=watchedKey(t,e) in watched
    fun markWatched(t: Title,e: Episode,value: Boolean,automatic: Boolean = false) {val key=watchedKey(t,e);if(automatic && key in manuallyUnwatched)return;if(!automatic){if(value)manuallyUnwatched.remove(key) else manuallyUnwatched.add(key)};watched=if(value) watched+watchedKey(t,e) else watched-watchedKey(t,e);prefs.edit().putString("watched",JSONArray(watched.toList()).toString()).apply();if(value)prefs.edit().putLong("position:${watchedKey(t,e)}",0).apply()}
    fun exportBackup()=LibraryBackup.export(prefs)
    fun importBackup(text: String) {LibraryBackup.import(prefs,text);favorites=readTitles("favorites");watchlist=readTitles("watchlist");history=readTitles("history");showPreferences=JSONObject(prefs.getString("showPreferences","{}"));watched=JSONArray(prefs.getString("watched","[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() };subtitleSize=prefs.getInt("subtitleSize",24);subtitleColor=prefs.getInt("subtitleColor",-1);manuallyUnwatched.clear();subtitleDelayMs=prefs.getLong("subtitleDelayMs",0);subtitleOffsetUs.set(subtitleDelayMs*1000);autoNext=prefs.getBoolean("autoNext",true);downloadHeight=prefs.getInt("downloadHeight",720)}
    fun batchEstimate(episodes: List<Episode>): Long = episodes.sumOf { (if(it.season==0) 120L else if(details?.title?.provider=="ani") 24L else 45L)*60*(when(downloadHeight){360->700000L;480->1200000L;720->2500000L;else->5000000L})/8 }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun downloadBatch(selected: List<Episode>,resolver: (suspend (Episode)->List<Stream>)? = null) {
        val d=details ?: return;val track=audio;val source=sourceMode;val quality=downloadHeight
        require(selected.size in 1..10000)
        runTask {
            var saved=0;val failures=mutableListOf<String>()
            selected.forEachIndexed { index,ep ->
                loadingStage="Preparing download ${index+1}/${selected.size} · ${ep.label}"
                try {
                    val found=withContext(Dispatchers.IO) {if(resolver!=null)resolver(ep) else if(d.title.provider=="luffy") luffyStreams(d.title,ep,source) else providers.streams(d.title,ep,track)}
                    val stream=chooseStream(found,quality) ?: error("No stream available")
                    val request=OfflineDownloads.prepare(getApplication(),Playing(d.title,ep,stream,audio=track,source=source),quality)
                    if(OfflineDownloads.manager(getApplication()).downloadIndex.getDownload(request.id)?.state!=androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) OfflineDownloads.enqueue(getApplication(),request)
                    saved++
                } catch(e: CancellationException) {throw e} catch(e: Exception) {failures+=ep.label}
            }
            details=null;episode=null;streams=emptyList();tab="Downloads"
            if(failures.isNotEmpty())error="$saved prepared; ${failures.size} unavailable: ${failures.take(5).joinToString()}. Retry those episodes from the title."
        }
    }
    var downloadHeight by mutableIntStateOf(prefs.getInt("downloadHeight",720).takeIf { it in listOf(360,480,720,1080) } ?: 720)
    fun nextDownloadQuality() { val sizes=listOf(360,480,720,1080);downloadHeight=sizes[(sizes.indexOf(downloadHeight)+1)%sizes.size];prefs.edit().putInt("downloadHeight",downloadHeight).apply() }
    var autoNext by mutableStateOf(prefs.getBoolean("autoNext",true))
    fun toggleAutoNext() { autoNext=!autoNext;prefs.edit().putBoolean("autoNext",autoNext).apply() }
    var subtitleDelayMs by mutableLongStateOf(prefs.getLong("subtitleDelayMs",0).coerceIn(-10000,10000))
        private set
    val subtitleOffsetUs=java.util.concurrent.atomic.AtomicLong(subtitleDelayMs*1000)
    fun adjustSubtitleDelay(delta: Long) { subtitleDelayMs=(subtitleDelayMs+delta).coerceIn(-10000,10000);subtitleOffsetUs.set(subtitleDelayMs*1000);prefs.edit().putLong("subtitleDelayMs",subtitleDelayMs).apply() }
    private var retryTask: (suspend () -> Unit)? = null
    fun retry() { retryTask?.let { runTask(it) } }
    fun toggleWatchlist(t: Title) { watchlist=if(watchlist.any { it.id==t.id && it.provider==t.provider }) watchlist.filterNot { it.id==t.id && it.provider==t.provider } else listOf(t)+watchlist;writeTitles("watchlist",watchlist) }
    fun resumeSaved(saved: Playing) {
        if(saved.offlineId!=null && OfflineDownloads.request(getApplication(),saved.offlineId)!=null) { playOffline(saved);return }
        runTask {
            val d=withContext(Dispatchers.IO) { if(saved.title.provider=="luffy") detailsFromJson(requireCatalog().details(saved.title)) else providers.details(saved.title) }
            val ep=d.episodes.firstOrNull { it.season==saved.episode.season && it.number==saved.episode.number } ?: error("Saved episode is no longer listed.")
            details=d;episode=ep;audio=saved.audio;sourceMode=showPreference(saved.title).optString("source","auto");loadingStage="Finding a stream…"
            streams=withContext(Dispatchers.IO) { if(saved.title.provider=="luffy") luffyStreams(saved.title,ep,sourceMode) else providers.streams(saved.title,ep,audio) }
            play(chooseStream(streams,saved.stream.height) ?: error("No stream available."),rememberQuality=false)
        }
    }
    fun nextEpisode(p: Playing): Episode? {
        val episodes=details?.takeIf { it.title.id==p.title.id && it.title.provider==p.title.provider }?.episodes ?: return null
        val index=episodes.indexOfFirst { it.season==p.episode.season && it.number==p.episode.number }
        return if(index>=0) episodes.getOrNull(index+1) else null
    }
    fun playNext(p: Playing) { nextEpisode(p)?.let { resumeSaved(p.copy(episode=it,offlineId=null)) } }
    fun refreshStream(p: Playing) { resumeSaved(p.copy(offlineId=null)) }
    fun switchPlaybackSource(p: Playing,source: String,position: Long,running: Boolean) {
        require(p.title.provider=="luffy");setSource(source);savePosition(p,position,0)
        runTask {loadingStage="Finding a stream from $source…";val found=withContext(Dispatchers.IO) {requireCatalog().streams(p.title,p.episode,source)};streams=found;val stream=chooseStream(found,p.stream.height) ?: error("No stream available");playing=p.copy(stream=stream,source=source,startPaused=!running)}
    }
    fun switchPlaybackStream(p: Playing,stream: Stream,position: Long,running: Boolean) {savePosition(p,position,0);rememberShow(p.title,"height",stream.height);playing=p.copy(stream=stream,startPaused=!running)}
    private var job: Job? = null
    private fun runTask(task: suspend () -> Unit) {
        retryTask=task
        job?.cancel()
        job = viewModelScope.launch {
            busy = true; error = null;loadingStage="Loading…"
            try { task() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = when(e) {
                    is java.io.IOException -> "Connection failed. Check your network and try again."
                    is org.json.JSONException -> "The provider returned an unexpected response. Try again later."
                    else -> e.message ?: "Unable to load. Please retry."
                }
            } finally { if (isActive) busy = false }
        }
    }
    fun selectTab(value: String) {
        job?.cancel(); busy = false; error = null; tab = value
        if(value == "Catalog" && catalogClient != null && catalogItems.isEmpty()) browseCatalog()
    }
    fun browseCatalog(next: Boolean = false) {
        val client = catalogClient ?: return
        val q=catalogQuery.trim(); val p=catalogProvider; val kind=catalogKind; val sort=catalogSort; val genre=catalogGenre; val page=if(next) catalogPage+1 else 1
        if(!next) { catalogItems=emptyList();catalogTotal=0;catalogMore=false }
        runTask {
            val result=withContext(Dispatchers.IO) { client.catalog(q,p,kind,page,sort,genre) }
            applyCatalog(result,next)
        }
    }
    private fun applyCatalog(result: JSONObject,append: Boolean) {
        val titles=result.array("items").objects().map(::titleFromJson)
        catalogItems=(if(append) catalogItems+titles else titles).distinctBy { "${it.provider}:${it.id}" }
        catalogPage=result.getInt("page");catalogTotal=result.getInt("total");catalogMore=result.getBoolean("hasMore");catalogStatus=result.getJSONObject("status");genreCoverage=result.optJSONObject("coverage") ?: JSONObject();catalogGenres=result.array("genres").objects().map { it.getString("name") to it.getInt("count") }
    }
    fun connectCatalog(connection: CatalogConnection) {
        runTask {
            val client=CatalogClient(connection)
            val result=withContext(Dispatchers.IO) { client.catalog("","all","all",1,"name") }
            withContext(Dispatchers.IO) {
                val temporary=java.io.File(connectionFile.parentFile,"catalog-connection.tmp")
                temporary.writeText(connection.toJson().toString())
                check(temporary.renameTo(connectionFile)) { "Could not save server settings." }
            }
            catalogClient=client;catalogConnection=connection;catalogQuery="";catalogProvider="all";catalogKind="all";catalogSort="name";catalogGenre=""
            applyCatalog(result,false);tab="Catalog";showServerSettings=false
        }
    }
    fun disconnectCatalog() {
        job?.cancel();busy=false;connectionFile.delete();catalogClient=null;catalogConnection=null;catalogItems=emptyList();catalogStatus=JSONObject();showServerSettings=false
    }
    fun search(next: Boolean = false) {
        val q = query.trim(); if(q.isEmpty()) return
        val p = provider; val n = if(next) page+1 else 1
        if (!next) { results = emptyList(); searched = false }
        runTask {
            val found = withContext(Dispatchers.IO) {
                if(p=="luffy") requireCatalog().search(q,n).array("items").objects().map(::titleFromJson) else providers.search(p,q,n)
            }
            results = (if(next) results + found else found).distinctBy { it.id }
            page = n; more = found.isNotEmpty(); searched = true
        }
    }
    fun switchProvider(value: String) {
        job?.cancel(); busy = false; error = null; provider = value; prefs.edit().putString("provider", value).apply()
        results = emptyList(); searched = false; more = false
    }
    fun open(title: Title) {
        details = null; episode = null; streams = emptyList(); detailNotice = null
        runTask {
            val client=catalogClient
            val fromCatalog=tab == "Catalog"
            val result = withContext(Dispatchers.IO) {
                if((fromCatalog || title.provider=="luffy") && client != null) {
                    val payload=client.details(title)
                    detailsFromJson(payload) to if(payload.optBoolean("stale")) "Saved episode list · provider refresh unavailable. Playback will check for a fresh stream." else null
                } else providers.details(title) to null
            }
            details=result.first; detailNotice=result.second
            val preferred=showPreference(details!!.title)
            audio=preferred.optString("audio").takeIf { value -> details!!.audio.any {it.id==value} } ?: details!!.audio.firstOrNull()?.id.orEmpty()
            sourceMode=preferred.optString("source","auto")
        }
    }
    private fun requireCatalog() = checkNotNull(catalogClient) { "Connect the catalog server to use Luffy. Open Server settings." }
    fun selectAudio(value: Audio) {
        details?.title?.let {rememberShow(it,"audio",value.id)}
        audio = value.id; streams = emptyList(); episode = null
    }
    fun resolve(ep: Episode) {
        val title = details!!.title; val track = audio
        episode = ep; streams = emptyList()
        runTask { loadingStage="Finding a stream…"; detailNotice=null; streams = withContext(Dispatchers.IO) { if(title.provider=="luffy") luffyStreams(title,ep,sourceMode) else providers.streams(title,ep,track) } }
    }
    fun back() {
        job?.cancel(); busy = false; error = null
        when { playing != null -> playing = null; episode != null -> { episode = null; streams = emptyList() }; else -> details = null }
    }
    fun play(stream: Stream,rememberQuality: Boolean = true) {
        val title = details!!.title
        manuallyUnwatched.remove(watchedKey(title,episode!!))
        if(rememberQuality)rememberShow(title,"height",stream.height)
        val adaptive=streams.firstOrNull {it.height==0}
        val media=if(stream.height>0 && adaptive!=null)adaptive.copy(height=stream.height,label=stream.label) else stream
        playing = Playing(title, episode!!, media, audio=audio,source=sourceMode)
        history = (listOf(title) + history.filter { it.id != title.id || it.provider != title.provider }).take(100)
        writeTitles("history", history)
        prefs.edit().putString("last:${title.provider}:${title.id}", "${episode!!.season}|${episode!!.number}").apply()
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun download(stream: Stream) {
        val media=streams.firstOrNull {it.height==0}?.takeIf {stream.height>0}?.copy(height=stream.height,label=stream.label) ?: stream
        val saved=Playing(details!!.title,episode!!,media,audio=audio,source=sourceMode)
        runTask {
            val request=OfflineDownloads.prepare(getApplication(),saved,downloadHeight)
            OfflineDownloads.enqueue(getApplication(),request)
            details=null;episode=null;streams=emptyList();tab="Downloads"
        }
    }
    fun playOffline(saved: Playing) { playing=saved }
    fun last(title: Title) = prefs.getString("last:${title.provider}:${title.id}", null)
    fun position(p: Playing) = prefs.getLong("position:${p.title.provider}:${p.title.id}:${p.episode.season}:${p.episode.number}", 0)
    fun savePosition(p: Playing, position: Long, duration: Long) {
        if(duration>0 && position>=duration*0.95) markWatched(p.title,p.episode,true,automatic=true)
        recentPlays=(listOf(p)+recentPlays.filterNot { it.title.id==p.title.id && it.title.provider==p.title.provider }).take(30)
        prefs.edit().putString("recentPlays",JSONArray(recentPlays.map { it.toJson().put("offlineId",it.offlineId ?: "") }).toString()).apply()
        prefs.edit().putLong("position:${p.title.provider}:${p.title.id}:${p.episode.season}:${p.episode.number}", if(duration > 0 && position > duration*0.95) 0 else position.coerceAtLeast(0)).apply()
    }
    fun favorite(title: Title) {
        favorites = if (isFavorite(title)) favorites.filterNot { it.id == title.id && it.provider == title.provider } else listOf(title) + favorites
        writeTitles("favorites", favorites)
    }
    fun isFavorite(t: Title) = favorites.any { it.id == t.id && it.provider == t.provider }
    private fun readTitles(key: String): List<Title> {
        if(!prefs.contains("retired_moviebox_$key")) prefs.edit().putString("retired_moviebox_$key",prefs.getString(key,"[]")).apply()
        return runCatching { JSONArray(prefs.getString(key, "[]")).objects().map { Title(it.getString("provider"),it.getString("id"),it.getString("name"),it.optString("poster"),it.optString("info"),it.optBoolean("series", true)) } }.getOrDefault(emptyList()).filter { it.provider in listOf("ani","luffy") }
    }
    private fun writeTitles(key: String, values: List<Title>) {
        prefs.edit().putString(key, JSONArray(values.map { JSONObject().put("provider",it.provider).put("id",it.id).put("name",it.name).put("poster",it.poster).put("info",it.info).put("series",it.series) }).toString()).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AniDroid(model: LibraryModel = viewModel()) {
    val playing = model.playing
    BackHandler(model.details != null || playing != null || model.busy) { model.back() }
    if (playing != null) { PlaybackScreen(playing, model); return }
    var about by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf(false) }
    val backupContext=LocalContext.current
    val backupScope=rememberCoroutineScope()
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val exportBackup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) backupScope.launch {backupMessage=runCatching {val text=model.exportBackup();withContext(Dispatchers.IO) {backupContext.contentResolver.openOutputStream(uri)?.use {it.write(text.toByteArray())} ?: error("Cannot write file")};"Backup saved."}.getOrElse {"Could not export backup."}}
    }
    val importBackup=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) backupScope.launch {backupMessage=runCatching {val text=withContext(Dispatchers.IO) {backupContext.contentResolver.openInputStream(uri)?.use {input->val source=input.source().buffer();check(!source.request(2L*1024*1024+1));String(source.readByteArray())} ?: error("Cannot read file")};model.importBackup(text);"Lists and preferences restored."}.getOrElse {"Backup was not restored: invalid or unsupported file."}}
    }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if(model.tab == "Catalog") model.browseCatalog() }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("Ani-Droid", fontWeight = FontWeight.Bold); Text("YOUR NEXT EPISODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
            navigationIcon = { if(model.details != null) TextButton(onClick = model::back) { Text("‹ Back") } },
            actions = { TextButton(onClick={backup=true;backupMessage=null}) {Text("Backup")}; TextButton(onClick = { model.showServerSettings = true }) { Text("Server") }; TextButton(onClick = { about = true }) { Text("About") } })
    }, bottomBar = {
        if(model.details == null) NavigationBar { listOf("Catalog", "Search", "Watchlist", "Favorites", "History", "Downloads").forEachIndexed { i, label ->
            NavigationBarItem(selected = model.tab == label, onClick = { model.selectTab(label) }, icon = { Text(listOf("▦", "⌕", "☷", "♡", "↺", "↓")[i], style = MaterialTheme.typography.headlineSmall) }, label = { Text(label) })
        } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            if(model.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(model.loadingStage, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium) }
            model.error?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp)) { Text(message); TextButton(enabled=!model.busy,onClick=model::retry) {Text("Retry")}; TextButton(onClick = { model.error = null }) { Text("Dismiss") } }
                }
            }
            val details = model.details
            if(details != null) { DetailScreen(details, model); return@Column }
            if(model.tab == "Downloads") { DownloadsScreen(model); return@Column }
            if(model.tab in listOf("Catalog","Search","History") && model.continueWatching.isNotEmpty()) {
                Text("Continue Watching",style=MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(model.continueWatching,key={ "${it.title.provider}:${it.title.id}" }) { saved -> OutlinedButton(enabled=!model.busy,onClick={model.resumeSaved(saved)}) {Text("${saved.title.name} · ${saved.episode.label}",maxLines=1)} } }
            }
            if(model.tab == "Catalog") CatalogControls(model)
            if(model.tab == "Search") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(model.provider == "ani", { model.switchProvider("ani") }, label = { Text("ani-cli · Anime") })
                    FilterChip(model.provider == "luffy", { model.switchProvider("luffy") }, label = { Text("Luffy · Film & TV") })
                }
                OutlinedTextField(model.query, { model.query = it; model.more = false }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if(model.provider == "ani") "Search anime" else "Search movies, series & anime") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); model.search() }),
                    trailingIcon = { TextButton(enabled = model.query.isNotBlank() && !model.busy, onClick = { keyboard?.hide(); model.search() }) { Text("Go") } })
                Spacer(Modifier.height(16.dp))
            } else if(model.tab != "Catalog") Text(model.tab, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 12.dp))
            val titles = when(model.tab) { "Catalog" -> model.catalogItems; "Watchlist" -> model.watchlist; "Favorites" -> model.favorites; "History" -> model.history; else -> model.results }
            if(model.tab == "Catalog" && titles.isEmpty() && !model.busy) {
                Text(if(model.catalogConnection == null) "Connect your catalog server to browse its library." else "No matching listings yet. The server keeps indexing in the background.", Modifier.padding(vertical=24.dp))
                if(model.catalogConnection == null) Button(onClick={ model.showServerSettings=true }) { Text("Connect server") }
            }
            if(model.tab != "Catalog" && titles.isEmpty() && !model.busy) {
                Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if(model.tab == "Search") "Find something worth watching." else "Your ${model.tab.lowercase()} will appear here.", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(if(model.searched && model.tab == "Search") "No matches. Try another title or source." else if(model.tab == "Search") "Search a title to explore episodes and streams." else "${if(model.tab == "Favorites") "Save a title with the favorite button." else "Start watching to save your place."}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(titles, key = { "${it.provider}:${it.id}" }) { title ->
                    Card(onClick = { model.open(title) }, enabled = !model.busy, shape = RoundedCornerShape(18.dp)) {
                        Poster(title, model)
                        Column(Modifier.padding(12.dp)) {
                            if(model.tab == "Catalog") Text(if(title.provider == "ani") "ANI-CLI" else "LUFFY",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                            Text(title.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(title.info.ifBlank { if(title.provider == "ani") "Anime" else "Luffy" }, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if(model.tab == "Catalog" && model.catalogMore) item { OutlinedButton(enabled=!model.busy,onClick={model.browseCatalog(true)}) { Text("Load more") } }
                if(model.tab == "Search" && model.more && titles.isNotEmpty()) item { OutlinedButton(enabled = !model.busy, onClick = { model.search(true) }) { Text("Load more") } }
            }
        }
    }
    if(backup) AlertDialog(onDismissRequest={backup=false},title={Text("Local library backup")},text={Column {
        Text("Export watchlists, favorites, watched flags and preferences. Import replaces those lists and settings. Server credentials and videos are excluded.")
        TextButton(onClick={exportBackup.launch("Ani-Droid-library.json")}) {Text("Export file")}
        TextButton(onClick={importBackup.launch(arrayOf("application/json","text/plain","application/octet-stream"))}) {Text("Import file")}
        backupMessage?.let {Text(it)}
    }},confirmButton={TextButton(onClick={backup=false}) {Text("Done")}})
    if(model.showServerSettings) ServerDialog(model)
    if(about) {
        val context = LocalContext.current
        val notice = remember { context.assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { about = false }, title = { Text("Ani-Droid 0.7") }, text = { LazyColumn { item { Text("Independent Android player using provider logic from ani-cli and Luffy. ani-cli works directly on your phone. Luffy uses your catalog server to find streams; video plays on your device. Downloads stay on this device.\n\n$notice") }; items(listOf("ani-cli-GPL-3.0.txt", "luffy-GPL-3.0.txt")) { name -> var show by remember { mutableStateOf(false) }; TextButton(onClick = { show = !show }) { Text(name) }; if(show) Text(remember { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }) } } }, confirmButton = { TextButton(onClick = { about = false }) { Text("Done") } })
    }
}
/** In-memory poster cache: scrolling back through a list re-uses decoded bitmaps instead of downloading them again. */
private object PosterCache {
    val bitmaps = object : android.util.LruCache<String, android.graphics.Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceIn(4 shl 20, 48 shl 20)) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }
}
@Composable private fun Poster(title: Title, model: LibraryModel) {
    var bitmap by remember(title.poster) { mutableStateOf(PosterCache.bitmaps.get(title.poster)) }
    LaunchedEffect(title.poster) {
        if(bitmap == null && title.poster.startsWith("https://")) bitmap = withContext(Dispatchers.IO) { runCatching {
            model.providers.http.newCall(okhttp3.Request.Builder().url(title.poster).build()).execute().use { r ->
                if(!r.isSuccessful) null else r.body.byteStream().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            }
        }.getOrNull() }?.also { PosterCache.bitmaps.put(title.poster, it) }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if(bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(title.name.take(1), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
    }
}
@Composable private fun DetailScreen(details: Details, model: LibraryModel) {
    var selectedSeason by remember(details.title.id) { mutableIntStateOf(details.episodes.firstOrNull()?.season ?: 1) }
    var seasonMenu by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var batchOpen by remember { mutableStateOf(false) }
    var batchSelection by remember(details.title.id) { mutableStateOf<Set<String>>(emptySet()) }
    var sourceMenu by remember { mutableStateOf(false) }
    var expanded by remember(details.title.id) { mutableStateOf(false) }
    val ep = model.episode
    if(batchOpen) {
        val selected=details.episodes.filter {it.id in batchSelection}
        AlertDialog(onDismissRequest={batchOpen=false},title={Text("Batch downloads")},text={Column(Modifier.heightIn(max=360.dp)) {
            Text("${selected.size} episodes · estimated ${model.batchEstimate(selected)/1048576} MB at up to ${model.downloadHeight}p. Actual sizes vary.")
            TextButton(onClick={batchSelection=details.episodes.filter {it.season==selectedSeason}.map {it.id}.toSet()}) {Text("Select season $selectedSeason")}
            TextButton(onClick={batchSelection=emptySet()}) {Text("Clear selection")}
            TextButton(onClick=model::nextDownloadQuality) {Text("Quality: up to ${model.downloadHeight}p · Change")}
            LazyColumn(Modifier.heightIn(max=180.dp)) {items(details.episodes.filter {it.season==selectedSeason},key={it.id}) { e -> Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(e.id in batchSelection,{checked->batchSelection=if(checked)batchSelection+e.id else batchSelection-e.id});Text(e.label)} }}
        }},confirmButton={TextButton(enabled=selected.size in 1..10000 && !model.busy,onClick={batchOpen=false;model.downloadBatch(selected)}) {Text("Queue downloads")}},dismissButton={TextButton(onClick={batchOpen=false}) {Text("Cancel")}})
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        model.detailNotice?.let { notice -> item { Text(notice,color=MaterialTheme.colorScheme.secondary) } }
        if(details.title.indexedAt > 0) item { Text("Listed in catalog · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(details.title.indexedAt)),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text(details.title.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(details.title.info, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { model.toggleWatchlist(details.title) }) {Text(if(model.watchlist.any { it.id==details.title.id && it.provider==details.title.provider }) "✓ Watchlist" else "+ Watchlist")}
            TextButton(onClick = { model.favorite(details.title) }) { Text(if(model.isFavorite(details.title)) "♥ Saved" else "♡ Favorite") }
        } }
        if(details.title.genres.isNotEmpty()) item { Text(details.title.genres.joinToString(" · "),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary) }
        if(details.description.isNotBlank() && ep == null) item { Text(details.description, maxLines = if(expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); if(details.description.length > 250) TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "Show less" else "Read synopsis") } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(enabled = !model.busy, onClick = { audioMenu = true }) { Text(details.audio.firstOrNull { it.id == model.audio }?.label ?: "Audio") }
                    DropdownMenu(audioMenu, { audioMenu = false }) { details.audio.forEach { track -> DropdownMenuItem(text = { Text(track.label) }, onClick = { audioMenu = false; model.selectAudio(track) }) } }
                }
                if(details.title.provider=="luffy") Box {
                    OutlinedButton(onClick={sourceMenu=true}) {Text("Source: ${model.sourceMode}")}
                    DropdownMenu(sourceMenu,{sourceMenu=false}) {listOf("auto","cinejoy","vixsrc","lookmovie").forEach { source -> DropdownMenuItem(text={Text(source)},onClick={sourceMenu=false;model.setSource(source);if(ep!=null)model.resolve(ep)})}}
                }
                if(ep == null && details.title.series) Box {
                    OutlinedButton(onClick = { seasonMenu = true }) { Text("Season $selectedSeason ▾") }
                    DropdownMenu(seasonMenu, { seasonMenu = false }) { details.episodes.map { it.season }.distinct().forEach { se -> DropdownMenuItem(text = { Text("Season $se") }, onClick = { selectedSeason = se; seasonMenu = false }) } }
                }
            }
        }
        if(ep == null) {
            val last = model.last(details.title)
            val resume = details.episodes.firstOrNull { "${it.season}|${it.number}" == last }
            if(resume != null) item { Button(enabled = !model.busy, onClick = { model.resolve(resume) }, modifier = Modifier.fillMaxWidth()) { Text("Continue · ${resume.label}") } }
            item { Text(if(details.title.series) "Episodes" else "Ready to watch", style = MaterialTheme.typography.titleLarge);OutlinedButton(onClick={batchSelection=emptySet();batchOpen=true}) {Text("Batch downloads")}}

            items(details.episodes.filter { it.season == selectedSeason }, key = { "${it.season}:${it.number}" }) { episode ->
                OutlinedCard(onClick = { model.resolve(episode) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Row(verticalAlignment=Alignment.CenterVertically) {Text(episode.label, Modifier.weight(1f).padding(18.dp));TextButton(onClick={model.markWatched(details.title,episode,!model.isWatched(details.title,episode))}) {Text(if(model.isWatched(details.title,episode)) "✓ Watched" else "Mark watched")}} }
            }
        } else {
            item { Text(ep.label, style = MaterialTheme.typography.titleLarge) }
            item { Text("Choose video quality", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val preferred=chooseStream(model.streams,model.showPreference(details.title).optInt("height",0))
            items(model.streams) { stream ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stream.label+if(stream===preferred && model.streams.size>1) " · Preferred" else "",fontWeight=FontWeight.Bold)
                        Text("${stream.captions.size} external subtitle tracks",style=MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Button(enabled=!model.busy,onClick={model.play(stream)}) {Text("Play ▶")}
                            OutlinedButton(enabled=!model.busy,onClick={model.download(stream)}) {Text("Download")}
                        }
                    }
                }
            }
            if(model.error != null) item { OutlinedButton(enabled = !model.busy, onClick = { model.resolve(ep) }) { Text("Retry streams") } }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun PlaybackScreen(playing: Playing, model: LibraryModel) {
    val context = LocalContext.current
    var subtitleOptions by remember { mutableStateOf(false) }
    var failure by remember(playing) { mutableStateOf<String?>(null) }
    var nextCountdown by remember(playing) { mutableStateOf<Int?>(null) }
    var fullscreen by remember(playing) { mutableStateOf(true) }
    var fillScreen by remember(playing) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val activity=context as? ComponentActivity
    val originalOrientation=remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    DisposableEffect(fullscreen) {
        val window=activity?.window
        val controller=window?.let { WindowCompat.getInsetsController(it,it.decorView) }
        if(fullscreen) {
            activity?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else { activity?.requestedOrientation=originalOrientation;controller?.show(WindowInsetsCompat.Type.systemBars()) }
        onDispose { activity?.requestedOrientation=originalOrientation;controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    var captionsLoaded by remember(playing) {mutableStateOf(playing.stream.captions.isEmpty())}
    val player = remember(playing) {
        val showPrefs=model.showPreference(playing.title)
        val factory = OfflineDownloads.playbackSource(context,playing.stream,playing.offlineId!=null)
        val offlineRequest=playing.offlineId?.let { OfflineDownloads.request(context,it) }
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(factory).setSubtitleParserFactory(SubtitleTiming(model.subtitleOffsetUs) {android.os.Handler(android.os.Looper.getMainLooper()).post {captionsLoaded=true}})).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE, showPrefs.optInt("height",playing.stream.height).takeIf {it>0} ?: Int.MAX_VALUE).setPreferredTextLanguage(showPrefs.optString("subtitleLanguage","en")).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,showPrefs.optBoolean("subtitleOff")).setPreferredAudioLanguage(showPrefs.optString("audioLanguage").takeIf {it.isNotBlank()} ?: if(playing.title.provider=="luffy") "en" else null).build()
            val wantedSubtitle=showPrefs.optString("subtitleLabel")
            val subtitleAvailable=playing.stream.captions.any {it.name==wantedSubtitle}
            val item = (offlineRequest?.toMediaItem()?.buildUpon() ?: MediaItem.Builder().setUri(playing.stream.url)).setSubtitleConfigurations(playing.stream.captions.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url)).setLabel(sub.name).setLanguage(if(sub.name.contains("English",true)) "en" else "und").setMimeType(if(sub.url.substringBefore('?').endsWith(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT).setSelectionFlags(if(if(subtitleAvailable)sub.name==wantedSubtitle else sub.name.contains("English",true)) C.SELECTION_FLAG_DEFAULT else 0).build()
            }).build()
            setMediaItem(item); seekTo(model.position(playing)); prepare(); playWhenReady = !playing.startPaused
        }
    }
    val appliedSubtitleDelay=remember(player) { longArrayOf(model.subtitleDelayMs) }
    LaunchedEffect(player,model.subtitleDelayMs) {
        if(appliedSubtitleDelay[0]!=model.subtitleDelayMs) {
            appliedSubtitleDelay[0]=model.subtitleDelayMs
            captionsLoaded=playing.stream.captions.isEmpty()
            player.currentMediaItem?.let { item -> val position=player.currentPosition;val running=player.playWhenReady;player.setMediaItem(item,position);player.prepare();player.playWhenReady=running }
        }
    }
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var initialTrackPreferences by remember(player) {mutableStateOf(false)}
    var buffering by remember(player) { mutableStateOf(true) }
    var qualityCap by remember(playing.title.id) { mutableIntStateOf(model.showPreference(playing.title).optInt("height",playing.stream.height)) }
    DisposableEffect(player) {
        val activity = context as? ComponentActivity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failure="Playback failed (${error.errorCodeName}). Source: ${playing.stream.label}. Retry with a fresh stream or choose another source." }
            override fun onTracksChanged(value: Tracks) {
                tracks=value
                if(!initialTrackPreferences && value.groups.any {it.type==C.TRACK_TYPE_TEXT}) {
                    initialTrackPreferences=true
                    val pref=model.showPreference(playing.title);val label=pref.optString("subtitleLabel")
                    if(label.isNotBlank() && !pref.optBoolean("subtitleOff")) {
                        value.groups.filter {it.type==C.TRACK_TYPE_TEXT}.forEach {group -> val index=(0 until group.length).firstOrNull {group.isTrackSupported(it) && group.getTrackFormat(it).label==label};if(index!=null && !group.isTrackSelected(index))player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).addOverride(TrackSelectionOverride(group.mediaTrackGroup,index)).build()}
                    }
                }
            }
            override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                model.rememberShow(playing.title,"subtitleOff",parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
                parameters.overrides.values.forEach {choice -> val type=choice.mediaTrackGroup.type;val index=choice.trackIndices.firstOrNull();if(index!=null && type in listOf(C.TRACK_TYPE_TEXT,C.TRACK_TYPE_AUDIO)) {val format=choice.mediaTrackGroup.getFormat(index);model.rememberShow(playing.title,if(type==C.TRACK_TYPE_TEXT)"subtitleLanguage" else "audioLanguage",format.language ?: "und");if(type==C.TRACK_TYPE_TEXT)model.rememberShow(playing.title,"subtitleLabel",format.label ?: "");if(type==C.TRACK_TYPE_AUDIO)model.rememberShow(playing.title,"audioLabel",format.label ?: "Track ${index+1}")}}
            }
            override fun onPlaybackStateChanged(value: Int) { buffering=value==Player.STATE_BUFFERING || value==Player.STATE_IDLE;if(value==Player.STATE_ENDED && model.autoNext && playing.offlineId==null && model.nextEpisode(playing)!=null) nextCountdown=8 }
        }
        player.addListener(listener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if(event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { model.savePosition(playing, player.currentPosition, player.duration); player.pause() }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            model.savePosition(playing, player.currentPosition, player.duration)
            activity?.lifecycle?.removeObserver(observer); player.removeListener(listener); player.release()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(nextCountdown) { val remaining=nextCountdown;if(remaining!=null) { if(remaining>0) {delay(1000);nextCountdown=remaining-1} else {nextCountdown=null;model.playNext(playing)} } }
    LaunchedEffect(player) { while(isActive) { delay(5000); model.savePosition(playing, player.currentPosition, player.duration) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply {
            this.player = player
            setShowSubtitleButton(true);setShowNextButton(false);setShowPreviousButton(false)
            setFullscreenButtonClickListener { fullscreen=it }
            setFullscreenButtonState(fullscreen)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> controlsVisible=visibility==android.view.View.VISIBLE })
        } }, update={ view ->
            view.subtitleView?.apply {
                setApplyEmbeddedStyles(false)
                setApplyEmbeddedFontSizes(false)
                setFixedTextSize(TypedValue.COMPLEX_UNIT_SP,model.subtitleSize.toFloat())
                setStyle(CaptionStyleCompat(model.subtitleColor,0xB3000000.toInt(),android.graphics.Color.TRANSPARENT,CaptionStyleCompat.EDGE_TYPE_OUTLINE,android.graphics.Color.BLACK,null))
            }
            view.setFullscreenButtonState(fullscreen)
            view.resizeMode=if(fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        }, modifier=Modifier.fillMaxSize())
        if(controlsVisible || failure!=null || model.error!=null) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).background(Color.Black.copy(alpha=0.55f)),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=model::back) {Text("‹ Back")}
            Text(playing.title.name,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            if(model.nextEpisode(playing)!=null && playing.offlineId==null) TextButton(onClick={model.playNext(playing)}) {Text("Next")}
            TextButton(onClick={subtitleOptions=true}) {Text("Subtitles")}
            TextButton(onClick={fillScreen=!fillScreen}) {Text(if(fillScreen) "Fit" else "Fill screen")}
        }
        if(subtitleOptions) AlertDialog(
            onDismissRequest={subtitleOptions=false},title={Text("Subtitle options")},
            text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())) {
                Text("Saved for streaming and downloaded videos.")
                TextButton(onClick=model::nextSubtitleSize) {Text("Size: ${model.subtitleSize} sp · Change")}
                TextButton(onClick=model::nextSubtitleColor) {Text("Color: ${model.subtitleColors.first { it.second==model.subtitleColor }.first} · Change")}
                Row {TextButton(onClick={model.adjustSubtitleDelay(-500)}) {Text("Earlier −0.5s")};TextButton(onClick={model.adjustSubtitleDelay(500)}) {Text("Later +0.5s")}}
                Text("Subtitle timing: ${model.subtitleDelayMs/1000.0}s")
                TextButton(onClick={model.adjustSubtitleDelay(-model.subtitleDelayMs)}) {Text("Reset timing")}
                TextButton(onClick=model::toggleAutoNext) {Text("Auto next episode: ${if(model.autoNext) "On" else "Off"}")}
                TextButton(onClick={model.rememberShow(playing.title,"subtitleOff",true);player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()}) {Text("Subtitles off")}
                tracks.groups.filter { it.type==C.TRACK_TYPE_TEXT || it.type==C.TRACK_TYPE_AUDIO }.forEach { group ->
                    (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
                        val format=group.getTrackFormat(index)
                        TextButton(onClick={
                            val language=format.language ?: "und"
                            model.rememberShow(playing.title,if(group.type==C.TRACK_TYPE_AUDIO) "audioLanguage" else "subtitleLanguage",language)
                            if(group.type==C.TRACK_TYPE_AUDIO)model.rememberShow(playing.title,"audioLabel",format.label ?: "Track ${index+1}")
                            if(group.type==C.TRACK_TYPE_TEXT){model.rememberShow(playing.title,"subtitleOff",false);model.rememberShow(playing.title,"subtitleLabel",format.label ?: "")}
                            player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(group.type,false).clearOverridesOfType(group.type).addOverride(TrackSelectionOverride(group.mediaTrackGroup,index)).build()}) {
                            Text("${if(group.type==C.TRACK_TYPE_AUDIO) "Audio" else "Subtitle"}: ${format.label ?: format.language ?: "Track ${index+1}"}${if(group.isTrackSelected(index)) " ✓" else ""}")
                        }
                    }
                }
                Text("Video quality · ${if(qualityCap==0) "Auto" else "up to ${qualityCap}p"}")
                val videoHeights=tracks.groups.filter {it.type==C.TRACK_TYPE_VIDEO}.flatMap { g -> (0 until g.length).map {g.getTrackFormat(it).height} }.filter {it>0}.distinct()
                val choices=(listOf(0)+videoHeights+model.streams.map {it.height}).distinct().sorted()
                choices.forEach { height -> TextButton(enabled=playing.offlineId==null || height in videoHeights,onClick={
                    qualityCap=height;model.rememberShow(playing.title,"height",height)
                    val alternative=model.streams.firstOrNull {it.height==height}
                    if(!tracks.groups.any {it.type==C.TRACK_TYPE_VIDEO && it.length>1} && alternative!=null && alternative.url!=playing.stream.url) {model.switchPlaybackStream(playing,alternative,player.currentPosition,player.playWhenReady);subtitleOptions=false}
                    else player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE,if(height==0)Int.MAX_VALUE else height).setForceHighestSupportedBitrate(height>0).build()
                }) {Text(if(height==0) "Auto quality" else "${height}p")}}
                if(playing.title.provider=="luffy" && playing.offlineId==null) {
                    Text("Stream source")
                    listOf("auto","cinejoy","vixsrc","lookmovie").forEach { source -> TextButton(enabled=!model.busy,onClick={model.switchPlaybackSource(playing,source,player.currentPosition,player.playWhenReady);subtitleOptions=false}) {Text("Use $source")} }
                }
                TextButton(onClick={model.markWatched(playing.title,playing.episode,!model.isWatched(playing.title,playing.episode))}) {Text(if(model.isWatched(playing.title,playing.episode)) "Mark unwatched" else "Mark watched")}
                Text("Your next episode",color=Color(model.subtitleColor),fontSize=model.subtitleSize.sp,modifier=Modifier.background(Color.Black).padding(12.dp))
            }},confirmButton={TextButton(onClick={subtitleOptions=false}) {Text("Done")}}
        )
        if(model.busy || buffering || (!captionsLoaded && !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))) Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.65f)).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            CircularProgressIndicator();Text(if(model.busy)model.loadingStage else if(buffering) "Buffering video…" else "Loading subtitles…")
        }
        nextCountdown?.let { seconds -> Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.85f)).padding(24.dp)) {
            Text("Next episode in $seconds seconds")
            Row {TextButton(onClick={nextCountdown=null}) {Text("Cancel")};TextButton(onClick={nextCountdown=null;model.playNext(playing)}) {Text("Play now")}}
        } }
        (failure ?: model.error)?.let { message ->
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=0.8f)).padding(20.dp)) {
                Text(message,color=MaterialTheme.colorScheme.error)
                Text(playing.stream.sources.joinToString(" · ").ifBlank { playing.stream.label })
                TextButton(enabled=!model.busy,onClick={model.savePosition(playing,player.currentPosition,player.duration);failure=null;model.refreshStream(playing)}) {Text("Retry fresh stream")}
                TextButton(onClick=model::back) {Text("Choose another stream")}
            }
        }
    }
}
