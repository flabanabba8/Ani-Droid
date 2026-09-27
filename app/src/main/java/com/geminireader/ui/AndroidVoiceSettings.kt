package com.geminireader.ui

import android.content.Intent
import android.speech.tts.Voice
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.geminireader.ReaderApp
import com.geminireader.data.Settings
import kotlinx.coroutines.CancellationException

@Composable fun AndroidVoiceSettings(app: ReaderApp, settings: Settings, change: (Settings) -> Unit) {
    val latest by rememberUpdatedState(settings)
    var refresh by remember { mutableIntStateOf(0) }
    val engines = remember(refresh) { app.androidTts.engines() }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var status by remember { mutableStateOf("Loading installed voices…") }
    LaunchedEffect(settings.androidTtsEngine, settings.androidTtsOfflineOnly, refresh) {
        status = "Loading installed voices…"; voices = emptyList()
        try {
            val catalog = app.androidTts.catalog(settings.androidTtsEngine)
            voices = catalog.voices.filter { !settings.androidTtsOfflineOnly || !it.isNetworkConnectionRequired }
            val chosen = settings.androidTtsVoice.takeIf { name -> voices.any { it.name == name } } ?: voices.firstOrNull()?.name.orEmpty()
            change(latest.copy(androidTtsEngine = catalog.engine, androidTtsVoice = chosen))
            status = if (voices.isEmpty()) "No installed English voices match. Install voice data in Android settings, then refresh." else "${voices.size} English voices available."
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { status = "Android speech engine did not respond. Check its voice data, then refresh." }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status = e.message ?: "Could not load Android speech voices." }
    }
    Column {
        Text("Uses a speech engine installed on this phone. PageCast adds no speech API charge.")
        Choice("Installed engine", settings.androidTtsEngine, engines.map { it.id }, { id -> engines.firstOrNull { it.id == id }?.label ?: "Select engine" }) {
            change(settings.copy(androidTtsEngine = it, androidTtsVoice = ""))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Offline voices only")
            Switch(settings.androidTtsOfflineOnly, { change(settings.copy(androidTtsOfflineOnly = it)) })
        }
        if (voices.isNotEmpty()) Choice("Android narrator voice", settings.androidTtsVoice, voices.map { it.name }, { name ->
            voices.firstOrNull { it.name == name }?.let { "${it.locale.toLanguageTag()} · ${it.name} · ${if (it.isNetworkConnectionRequired) "network" else "offline"}" } ?: name
        }) { change(settings.copy(androidTtsVoice = it)) }
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Network voices may send text to the installed engine's provider. Voice downloads and engine behavior are managed by Android.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { refresh++ }) { Text("Refresh voices") }
            TextButton(onClick = { app.task {
                val intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { app.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
                    app.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } }) { Text("Android voice settings") }
        }
    }
}
