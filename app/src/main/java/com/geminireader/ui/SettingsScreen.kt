package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.geminireader.ReaderApp
import com.geminireader.analysis.VoiceDirector
import com.geminireader.analysis.VoiceCatalog
import com.geminireader.analysis.AnalysisModels
import com.geminireader.analysis.KokoroVoices
import com.geminireader.analysis.GroqVoices
import com.geminireader.analysis.GroqAnalysis
import com.geminireader.analysis.ElevenVoices
import com.geminireader.analysis.FishVoices
import com.geminireader.analysis.SpeechifyVoices
import com.geminireader.analysis.InworldVoices
import com.geminireader.analysis.DeepgramVoices
import com.geminireader.analysis.CartesiaVoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable private fun Field(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None, singleLine = true)
}
@Composable fun Choice(label: String, value: String, choices: List<String>, display: (String) -> String = { it }, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${display(value)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 380.dp)) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(display(choice)) }, onClick = { change(choice); open = false }) }
        }
    }
}
@Composable private fun Amount(label: String, value: Int, range: ClosedFloatingPointRange<Float>, change: (Int) -> Unit) {
    Column { Text("$label: $value"); Slider(value.toFloat(), { change(it.toInt()) }, valueRange = range) }
}

@Composable fun SettingsScreen(app: ReaderApp) {
    var draft by remember { mutableStateOf(app.settings) }
    var showModels by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { app.saveSettings(draft) }, enabled = !app.busy) { Text("Save") }
        }
        LazyColumn(Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { BackupControls(app) }
            item { Choice("Appearance", draft.theme, listOf("dark", "light", "system")) { draft = draft.copy(theme = it); app.setTheme(it) } }
            item { Text("Appearance changes apply immediately and are saved automatically.", style = MaterialTheme.typography.bodySmall) }
            item { SpendingCard(app) }
            item { Choice("Speech engine", draft.engine, listOf("vertex", "cloud", "gemini", "kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android"), { when (it) {
                "vertex" -> "Google Vertex AI · OAuth / automatic renewal"
                "gemini" -> "Google Gemini API · API key"
                "cloud" -> "Google Cloud Text-to-Speech"
                "groq" -> "Groq · Orpheus"
                "elevenlabs" -> "ElevenLabs"
                "android" -> "Android · installed speech engines"
                "fish" -> "Fish Audio · S2.1 Pro Free"
                "speechify" -> "Speechify · Simba 3.2"
                "inworld" -> "Inworld · TTS-2"
                "deepgram" -> "Deepgram · Flux"
                "cartesia" -> "Cartesia · Sonic"
                else -> "Kokoro · on this phone"
            } }) { draft = draft.copy(engine = it, characterMode = if (it in listOf("android")) "narrator" else if (it in listOf("kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android") && draft.characterMode == "performance") "narrator" else draft.characterMode) } }
            if (draft.engine == "android") item { AndroidVoiceSettings(app, draft) { draft = it } }
            if (draft.engine == "kokoro") {
                item { Text("Kokoro · on this phone", style = MaterialTheme.typography.titleMedium) }
                item { Text("Download Kokoro once to generate speech entirely on this phone. Narrator mode then works offline. Distinct voices and passage rewrites use the Google text provider below.", style = MaterialTheme.typography.bodySmall) }
                item { KokoroDownloadSettings(app) }
                item { Choice("Kokoro narrator voice", draft.kokoroVoice, KokoroVoices.names, KokoroVoices::label) { draft = draft.copy(kokoroVoice = it) } }
                item { Choice("Text analysis provider", draft.kokoroAnalysisEngine, listOf("vertex", "gemini")) { draft = draft.copy(kokoroAnalysisEngine = it) } }
            }
            if (draft.engine == "fish") {
                item { Field("Fish API key", draft.fishApiKey, true) { draft = draft.copy(fishApiKey = it.trim()) } }
                item { FishVoicePicker("Narrator voice", draft.fishVoice) { draft = draft.copy(fishVoice = it) } }
                item { Choice("Text analysis provider", draft.fishAnalysisEngine, listOf("groq", "vertex", "gemini")) { draft = draft.copy(fishAnalysisEngine = it) } }
                item { Text("Uses the free S2.1 Pro model only. Free access currently runs through November 30, 2026, subject to fair use. Fish may use requests to improve its models.", style = MaterialTheme.typography.bodySmall) }
            }
            if (draft.engine == "speechify") {
                item { Field("Speechify API key", draft.speechifyApiKey, true) { draft = draft.copy(speechifyApiKey = it.trim()) } }
                item { SpeechifyVoicePicker("Narrator voice", draft.speechifyVoice) { draft = draft.copy(speechifyVoice = it) } }
                item { Choice("Text analysis provider", draft.speechifyAnalysisEngine, listOf("groq", "vertex", "gemini")) { draft = draft.copy(speechifyAnalysisEngine = it) } }
                item { Text("Speech uses your Speechify account. Preview uses generation credits. Spending estimates use $10 per million characters as a Starter reference rate, before included usage or discounts.", style = MaterialTheme.typography.bodySmall) }
            }
            if (draft.engine == "inworld") {
                item { Field("Inworld API key", draft.inworldApiKey, true) { draft = draft.copy(inworldApiKey = it.trim()) } }
                item { InworldVoicePicker("Narrator voice", draft.inworldVoice) { draft = draft.copy(inworldVoice = it) } }
                item { Choice("Text analysis provider", draft.inworldAnalysisEngine, listOf("groq", "vertex", "gemini")) { draft = draft.copy(inworldAnalysisEngine = it) } }
                item { Text("Speech uses your Inworld account. Preview uses generation credits. Spending estimates use $25 per million characters before account credits or discounts.", style = MaterialTheme.typography.bodySmall) }
            }
            if (draft.engine == "deepgram") {
                item { Field("Deepgram API key", draft.deepgramApiKey, true) { draft = draft.copy(deepgramApiKey = it.trim()) } }
                item { DeepgramVoicePicker("Narrator voice", draft.deepgramVoice) { draft = draft.copy(deepgramVoice = it) } }
                item { Choice("Text analysis provider", draft.deepgramAnalysisEngine, listOf("groq", "vertex", "gemini")) { draft = draft.copy(deepgramAnalysisEngine = it) } }
                item { Text("Speech uses your Deepgram account. Preview uses generation credits. Spending estimates use $45 per million characters before account credits or discounts.", style = MaterialTheme.typography.bodySmall) }
            }
            if (draft.engine == "cartesia") {
                item { Field("Cartesia API key", draft.cartesiaApiKey, true) { draft = draft.copy(cartesiaApiKey = it.trim()) } }
                item { Choice("Cartesia model", draft.cartesiaModel, CartesiaVoices.models) { draft = draft.copy(cartesiaModel = it) } }
                item { CartesiaVoicePicker("Narrator voice", draft.cartesiaVoice) { draft = draft.copy(cartesiaVoice = it) } }
                item { Choice("Text analysis provider", draft.cartesiaAnalysisEngine, listOf("groq", "vertex", "gemini")) { draft = draft.copy(cartesiaAnalysisEngine = it) } }
                item { Text("Speech uses your Cartesia account. Preview uses generation credits. Prices vary by plan; Cartesia requests are recorded as unpriced in Spending.", style = MaterialTheme.typography.bodySmall) }
            }
            if (draft.engine == "elevenlabs") {
                item { Field("ElevenLabs API key", draft.elevenApiKey, true) { draft = draft.copy(elevenApiKey = it.trim()) } }
                item { Choice("ElevenLabs model", draft.elevenModel, ElevenVoices.models) { draft = draft.copy(elevenModel = it) } }
                item { ElevenVoicePicker("Narrator voice", draft.elevenVoice) { draft = draft.copy(elevenVoice = it) } }
                item { Choice("Text analysis provider", draft.elevenAnalysisEngine, listOf("groq", "vertex", "gemini")) { draft = draft.copy(elevenAnalysisEngine = it) } }
                item { Text("Speech uses your ElevenLabs account. Preview uses generation credits. Prices vary by plan; ElevenLabs requests are recorded as unpriced in Spending.", style = MaterialTheme.typography.bodySmall) }
            }
            if (draft.engine == "groq") {
                item { Text("Groq · cloud speech", style = MaterialTheme.typography.titleMedium) }
                item { Field("Groq API key", draft.groqApiKey, true) { draft = draft.copy(groqApiKey = it.trim()) } }
                item { Text("Model: Orpheus English", style = MaterialTheme.typography.bodySmall) }
                item { Choice("Groq narrator voice", draft.groqVoice, GroqVoices.namesFor(draft.groqModel), GroqVoices::label) { draft = draft.copy(groqVoice = it) } }
                item { Choice("Text analysis provider", draft.groqAnalysisEngine, listOf("groq", "vertex", "gemini")) { draft = draft.copy(groqAnalysisEngine = it) } }
                item { Text("$22 / million characters. Passages are split into requests of at most 200 characters. Narrator mode requires only the Groq key.", style = MaterialTheme.typography.bodySmall) }
            }
            if (draft.textEngine == "vertex") {
                item { Text(if (draft.engine in listOf("kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android")) "Vertex AI · text analysis and rewrites" else "Vertex AI · speech and analysis use this project", style = MaterialTheme.typography.titleMedium) }
                item { Field("Google Cloud project ID", draft.vertexProject) { draft = draft.copy(vertexProject = it.trim()) } }
                item { Field("Vertex region", draft.vertexLocation) { draft = draft.copy(vertexLocation = it.trim()) } }
                item { Text(if (draft.vertexBrokerUrl.isBlank()) "Authentication: manual token" else "Authentication: automatic renewal via your broker", style = MaterialTheme.typography.titleSmall) }
                item { Field("Token broker HTTPS URL (blank = manual)", draft.vertexBrokerUrl) { draft = draft.copy(vertexBrokerUrl = it.trim()) } }
                item { Field("Broker certificate SHA-256 fingerprint", draft.vertexBrokerPin) { draft = draft.copy(vertexBrokerPin = it.trim()) } }
                item { Field("Broker pairing secret", draft.vertexBrokerSecret, true) { draft = draft.copy(vertexBrokerSecret = it.trim()) } }
                item { Field("Manual Vertex access token (unused with broker)", draft.vertexToken, true) { draft = draft.copy(vertexToken = it.trim()) } }
                item { Text("Automatic renewal requires your broker computer on local Wi-Fi. Google refresh credentials remain there. Broker setup is documented in the project wiki; no public internet port is required.", style = MaterialTheme.typography.bodySmall) }
            } else if (draft.textEngine != "groq") {
            if (draft.engine !in listOf("kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android")) item { Field("Cloud API key", draft.apiKey, true) { draft = draft.copy(apiKey = it.trim()) } }
            item { Field("Gemini / analysis key (blank = Cloud key)", draft.geminiKey, true) { draft = draft.copy(geminiKey = it.trim()) } }
            }
            item { Text(if (draft.engine in listOf("android")) "Offline narration needs no network or API key. Optional passage rewrites use Groq." else if (draft.engine == "kokoro") "Kokoro speech stays on this phone and has no API charge. Google text analysis is used only for character analysis and rewrites." else if (draft.engine == "fish") "Speech text is sent to Fish. Character analysis and rewrites use the selected text provider." else if (draft.engine == "speechify") "Speech text is sent to Speechify. Character analysis and rewrites use the selected text provider." else if (draft.engine == "inworld") "Speech text is sent to Inworld. Character analysis and rewrites use the selected text provider." else if (draft.engine == "deepgram") "Speech text is sent to Deepgram. Character analysis and rewrites use the selected text provider." else if (draft.engine == "cartesia") "Speech text is sent to Cartesia. Character analysis and rewrites use the selected text provider." else if (draft.engine == "elevenlabs") "Speech text is sent to ElevenLabs. Character analysis and rewrites use the selected text provider." else if (draft.engine == "groq") "Speech text is sent to Groq. Character analysis and rewrites use the selected text provider. Credentials stay in app-private storage." else "Book text is sent to your selected Google API when you play or analyze. Credentials stay in this app's private storage. The offline mock needs no real key.", style = MaterialTheme.typography.bodySmall) }
            item { Button(onClick = { app.saveSettings(draft, test = true) }, enabled = !app.busy && !app.playback.loading) { Text("Save & test speech / preview narrator") } }
            if (draft.engine !in listOf("kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android")) item { Choice("TTS model", draft.model, com.geminireader.analysis.TtsModels.choices(draft.engine, draft.model, if (app.modelsEngine == draft.engine) app.models else emptyList())) { draft = draft.copy(model = it) } }
            if (draft.engine !in listOf("kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android")) item { Field("Language", draft.language) { draft = draft.copy(language = it.trim()) } }
            if (draft.engine == "kokoro") {
                item { Text("Includes 28 English voices. Kokoro supports fixed voices, not free-form performance instructions.", style = MaterialTheme.typography.bodySmall) }
            } else if (draft.engine !in listOf("groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android")) {
            item { Choice("Narrator voice", draft.narratorVoice, VoiceCatalog.names, VoiceCatalog::label) { draft = draft.copy(narratorVoice = it, narratorGender = VoiceCatalog.find(it)!!.gender) } }
            item { Text("Voice traits are Google's published defaults, not fixed pitch ranges. Character prompts adapt the actual selected voice: higher/lighter for a male voice portraying a woman, lower/fuller for a female voice portraying a man. Explicit performance instructions override these defaults.", style = MaterialTheme.typography.bodySmall) }
            item { OutlinedTextField(draft.narratorPrompt, { draft = draft.copy(narratorPrompt = it.take(600)) }, label = { Text("Narrator instructions") }, modifier = Modifier.fillMaxWidth()) }
            }
            item { Choice("Character mode", draft.characterMode, if (draft.engine in listOf("android")) listOf("narrator") else if (draft.engine in listOf("kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android")) listOf("distinct", "narrator") else listOf("performance", "distinct", "narrator")) { draft = draft.copy(characterMode = it) } }
            item { Text(if (draft.engine in listOf("android")) "Narrator: one voice, no character analysis or API key required." else if (draft.engine in listOf("kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android")) "Distinct: separate character voices using the selected text analysis provider. Narrator: one voice, no character analysis." else "Performance: the narrator acts unlocked roles; locked character voices override it. Distinct: separate character voices. Narrator: skip character analysis.", style = MaterialTheme.typography.bodySmall) }
            if (draft.textEngine == "groq") {
                if (draft.engine != "groq") item { Field(if (draft.engine in listOf("android")) "Optional Groq key for passage rewrites" else "Groq analysis API key", draft.groqApiKey, true) { draft = draft.copy(groqApiKey = it.trim()) } }
                item { Choice("Groq analysis model", draft.groqTextModel, GroqAnalysis.models) { draft = draft.copy(groqTextModel = it) } }
                item { Text(if (draft.engine in listOf("android")) "Optional: only requested passage rewrites use this model. Offline narration needs no API key." else "GPT-OSS 20B favors speed and cost; 120B is available for comparison. Analysis identifies speakers and selects character voices. Narrator mode skips analysis. Rewrites use this model too.", style = MaterialTheme.typography.bodySmall) }
            } else {
            item { Choice("Analysis model", draft.analysisModel, AnalysisModels.choices(draft.analysisModel, app.models)) { draft = draft.copy(analysisModel = it) } }
            item { Text("Flash is the default; Flash-Lite favors speed, Pro may take longer. Fetch models adds catalog choices; availability depends on your project and region.", style = MaterialTheme.typography.bodySmall) }
            item { Row { TextButton(onClick = { app.fetchModels(draft); showModels = true }, enabled = !app.busy) { Text("Fetch models") }; if (app.models.isNotEmpty()) TextButton(onClick = { showModels = true }) { Text("Choose model") } } }
            }
            item { Amount("Audio buffer target (seconds)", draft.bufferSeconds, 15f..600f) { draft = draft.copy(bufferSeconds = it) } }
            item { Text("Uses actual audio duration at your playback speed. Two speech requests can run concurrently; up to two segments may exceed the target. More buffering generates and bills more audio in advance.", style = MaterialTheme.typography.bodySmall) }
            item { Amount("Pause within paragraph (ms)", draft.withinPauseMs, 0f..2000f) { draft = draft.copy(withinPauseMs = it) } }
            item { Amount("Pause between paragraphs (ms)", draft.paragraphPauseMs, 0f..5000f) { draft = draft.copy(paragraphPauseMs = it) } }
            item { Amount("Reader font size", draft.fontSize, 14f..32f) { draft = draft.copy(fontSize = it) } }
            item { Amount("Audio cache (MB)", draft.cacheMb, 32f..2048f) { draft = draft.copy(cacheMb = it) } }
            item { TextButton(onClick = { app.task { app.playback.stop(); withContext(Dispatchers.IO) { app.playback.cache.clear() }; app.status = "Audio cache cleared" } }) { Text("Clear audio cache") } }
            item { Text("Advanced", style = MaterialTheme.typography.titleLarge) }
            item { Field("Vertex base URL (blank = regional Google URL)", draft.vertexUrl) { draft = draft.copy(vertexUrl = it.trim()) } }
            item { Field("Cloud base URL", draft.cloudUrl) { draft = draft.copy(cloudUrl = it.trim()) } }
            item { Field("Gemini base URL", draft.geminiUrl) { draft = draft.copy(geminiUrl = it.trim()) } }
            item { Text("Credentials are sent to these URLs. Debug builds allow HTTP only for 10.0.2.2, localhost and 127.0.0.1.", style = MaterialTheme.typography.bodySmall) }
            item { Field("Cloud OAuth token (overrides API key)", draft.oauthToken, true) { draft = draft.copy(oauthToken = it.trim()) } }
            item { Field("Cloud billing project ID", draft.project) { draft = draft.copy(project = it.trim()) } }
            item { Text("If Cloud rejects an API key, use a short-lived OAuth token and project ID. See README for permissions and setup. Analysis still uses the Gemini API key.", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (showModels && app.models.isNotEmpty()) AlertDialog(onDismissRequest = { showModels = false }, title = { Text("Available models") }, text = {
        LazyColumn { items(app.models) { model -> TextButton(onClick = { draft = if (model.contains("tts")) draft.copy(model = model) else draft.copy(analysisModel = model); showModels = false }) { Text(model) } } }
    }, confirmButton = { TextButton(onClick = { showModels = false }) { Text("Close") } })
}

@Composable private fun SpendingCard(app: ReaderApp) {
    val history by app.spending.state.collectAsState()
    val error by app.spending.error.collectAsState()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    fun money(value: Double) = String.format(java.util.Locale.US, "$%.4f USD", value)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Spending estimate", style = MaterialTheme.typography.titleLarge)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            Text("Total recorded: ${money(history.rows.sumOf { it.usd })}", style = MaterialTheme.typography.titleMedium)
            val month = java.time.LocalDate.now().toString().take(7)
            Text("This month: ${money(history.rows.filter { it.month == month }.sumOf { it.usd })}")
            history.rows.groupBy { it.provider to it.model }.forEach { (key, rows) ->
                Text("${key.first} · ${key.second}: ${money(rows.sumOf { it.usd })}", style = MaterialTheme.typography.bodySmall)
            }
            if (history.rows.isNotEmpty()) {
                Text("By book", style = MaterialTheme.typography.titleMedium)
                history.rows.groupBy { it.bookId }.values.sortedByDescending { rows -> rows.sumOf { it.usd } }.forEach { rows ->
                    Text("${rows.last().bookTitle}: ${money(rows.sumOf { it.usd })}", style = MaterialTheme.typography.bodySmall)
                    val missing = rows.sumOf { it.unpriced }
                    if (missing > 0) Text("$missing unpriced responses", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Tracking since ${history.since} on this device. Earlier spending is unavailable. Includes speech, previews and analysis; cached playback adds no cost.", style = MaterialTheme.typography.bodySmall)
            val unpriced = history.rows.sumOf { it.unpriced }
            if (unpriced > 0) Text("$unpriced responses could not be priced and are excluded from the total.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text("Paid-rate estimate before credits, free tiers and taxes. Rates checked September 15, 2026. Missing speech usage is estimated from text length and audio duration. Requests without a received response and custom endpoints are excluded.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://console.cloud.google.com/billing") }) { Text("Open Google Cloud billing") }
        }
    }
}
