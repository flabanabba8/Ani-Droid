package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geminireader.ReaderApp

@Composable fun KokoroDownloadSettings(app: ReaderApp) {
    val client = app.kokoro
    val installed by client.installed.collectAsState()
    val downloading by client.downloading.collectAsState()
    val status by client.status.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (downloading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { client.cancelDownload() }) { Text("Cancel download") }
        } else {
            if (!installed) Button(onClick = { confirm = true }, enabled = client.downloads.abi.isNotBlank()) { Text("Download Kokoro") }
            TextButton(onClick = { app.deleteKokoro() }, enabled = !app.busy) { Text("Delete Kokoro downloads") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Download Kokoro?") }, text = {
        Text("Download approximately ${client.downloads.downloadBytes()/1_000_000} MB from the public sherpa-onnx GitHub releases. Only the ${client.downloads.abi} runtime is fetched. Model preparation happens on your phone.\n\nThis downloads and runs optional native code outside F-Droid’s checks. Files are checked against hashes built into this app. No download occurs if you cancel.")
    }, confirmButton = { TextButton(onClick = { confirm = false; client.startDownload() }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
