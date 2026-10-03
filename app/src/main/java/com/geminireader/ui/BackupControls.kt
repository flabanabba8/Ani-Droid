package com.geminireader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import com.geminireader.ReaderApp
import com.geminireader.data.LibraryBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun BackupControls(app: ReaderApp) {
    var restoring by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) app.task {
        withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { LibraryBackup.write(app, it) } }
        app.status = "Library backup saved without credentials or broker configuration"
    } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) app.task {
        app.cancelPreparationAndJoin(); app.playback.stopAndJoin()
        val count = withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openInputStream(uri)).use { LibraryBackup.restore(app, it) } }
        app.refresh(); app.status = "Restored $count books as new copies; existing books and credentials preserved"
    } }
    Column {
        Text("Library backup", style = MaterialTheme.typography.titleMedium)
        Text("Includes books, positions, cast, series voices, pronunciation rules and notes. Excludes credentials, broker configuration, settings and generated audio. Backups contain your book text and are not encrypted.")
        TextButton(enabled = !app.busy && !app.preparing, onClick = { save.launch("PageCast-library.zip") }) { Text("Save backup to files / Drive") }
        TextButton(enabled = !app.busy && !app.preparing, onClick = { restoring = true }) { Text("Restore library backup") }
    }
    if (restoring) AlertDialog(onDismissRequest = { restoring = false }, title = { Text("Restore as new copies?") }, text = { Text("Existing books and credentials are kept. Importing the same backup again creates duplicate books. Choose only a backup you trust.") }, confirmButton = { TextButton(onClick = { restoring = false; restore.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Choose backup") } }, dismissButton = { TextButton(onClick = { restoring = false }) { Text("Cancel") } })
}
