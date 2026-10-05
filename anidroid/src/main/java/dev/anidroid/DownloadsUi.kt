package dev.anidroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun DownloadsScreen(model: LibraryModel) {
    val context=LocalContext.current
    var wifiOnly by remember { mutableStateOf(OfflineDownloads.wifiOnly(context)) }
    val manager=remember { OfflineDownloads.manager(context) }
    val downloads by produceState<List<Download>>(emptyList()) {
        while(isActive) { value=withContext(Dispatchers.IO) { OfflineDownloads.list(manager) };delay(1000) }
    }
    Column {
        Text("Downloads",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(vertical=12.dp))
        Text("Queued videos download one at a time and stay on this device.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=model::nextDownloadQuality) {Text("Download quality: up to ${model.downloadHeight}p · Change")}
        Row {Checkbox(wifiOnly,{wifiOnly=it;OfflineDownloads.setWifiOnly(context,it)});Text("Wi-Fi / unmetered only")}
        Row {TextButton(onClick={OfflineDownloads.pauseAll(context)}) {Text("Pause queue")};TextButton(onClick={OfflineDownloads.resumeAll(context)}) {Text("Resume queue")}}
        Text("${downloads.count { it.state==Download.STATE_QUEUED }} queued · ${downloads.sumOf { it.bytesDownloaded }.div(1048576)} MB saved",style=MaterialTheme.typography.bodySmall)
        if(downloads.isEmpty()) Text("Choose an episode and tap Download beside a stream.",Modifier.padding(vertical=32.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=16.dp)) {
            items(downloads,key={it.request.id}) { download ->
                val saved=remember(download.request,download.state,download.bytesDownloaded) { runCatching { OfflineDownloads.saved(context,download.request) }.getOrNull() }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(saved?.title?.name ?: "Saved video",style=MaterialTheme.typography.titleMedium)
                        Text("${saved?.episode?.label.orEmpty()} · ${saved?.stream?.label.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                        val label=when(download.state) {
                            Download.STATE_COMPLETED -> "Ready offline"
                            Download.STATE_DOWNLOADING -> "Downloading"
                            Download.STATE_STOPPED -> "Paused"
                            Download.STATE_FAILED -> "Download failed. Check storage/network, or open the title for a fresh stream."
                            Download.STATE_REMOVING -> "Removing"
                            else -> "Queued / waiting for network"
                        }
                        Text("$label · ${"%.1f".format(download.bytesDownloaded/1048576.0)} MB")
                        if(download.state==Download.STATE_DOWNLOADING) {
                            if(download.percentDownloaded>=0) LinearProgressIndicator(progress={download.percentDownloaded/100f},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(download.state==Download.STATE_COMPLETED && saved!=null) Button(onClick={model.playOffline(saved)}){Text("Play offline")}
                            else if(download.state==Download.STATE_STOPPED) TextButton(onClick={OfflineDownloads.resume(context,download.request.id)}){Text("Resume")}
                            else if(download.state==Download.STATE_FAILED && saved!=null) TextButton(onClick={model.open(saved.title)}){Text("Retry with fresh stream")}
                            else if(download.state!=Download.STATE_REMOVING) TextButton(onClick={OfflineDownloads.pause(context,download.request.id)}){Text("Pause")}
                            TextButton(enabled=download.state!=Download.STATE_REMOVING,onClick={OfflineDownloads.remove(context,download.request.id)}) {Text(if(download.state==Download.STATE_COMPLETED) "Delete" else "Cancel")}
                        }
                    }
                }
            }
        }
    }
}
