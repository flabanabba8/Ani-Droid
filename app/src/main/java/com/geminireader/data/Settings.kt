package com.geminireader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable data class Settings(
    val apiKey: String = "", val geminiKey: String = "", val oauthToken: String = "", val project: String = "",
    val engine: String = "vertex", val model: String = "gemini-3.1-flash-tts-preview",
    val androidTtsEngine: String = "", val androidTtsVoice: String = "", val androidTtsOfflineOnly: Boolean = true,
    val fishApiKey: String = "", val fishVoice: String = "933563129e564b19a115bedd57b7406a", val fishAnalysisEngine: String = "groq",
    val speechifyApiKey: String = "", val speechifyVoice: String = "geffen_32", val speechifyAnalysisEngine: String = "groq",
    val inworldApiKey: String = "", val inworldVoice: String = "Sarah", val inworldAnalysisEngine: String = "groq",
    val deepgramApiKey: String = "", val deepgramVoice: String = "flux-hannah-en", val deepgramAnalysisEngine: String = "groq",
    val cartesiaApiKey: String = "", val cartesiaVoice: String = "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4",
    val cartesiaModel: String = "sonic-3.6", val cartesiaAnalysisEngine: String = "groq",
    val elevenApiKey: String = "", val elevenVoice: String = "JBFqnCBsd6RMkjVDRZzb",
    val elevenModel: String = "eleven_flash_v2_5", val elevenAnalysisEngine: String = "groq",
    val groqApiKey: String = "", val groqVoice: String = "troy",
    val groqModel: String = "canopylabs/orpheus-v1-english",
    val groqAnalysisEngine: String = "groq",
    val groqTextModel: String = "openai/gpt-oss-20b",
    val kokoroVoice: String = "af_heart",
    val kokoroAnalysisEngine: String = "vertex",
    val vertexProject: String = "", val vertexLocation: String = "us-central1", val vertexToken: String = "", val vertexUrl: String = "",
    val vertexBrokerUrl: String = "", val vertexBrokerPin: String = "", val vertexBrokerSecret: String = "",
    val analysisModel: String = "gemini-3.1-flash-lite", val language: String = "en-US",
    val narratorVoice: String = "Charon", val narratorGender: String = "male",
    val narratorPrompt: String = "Read with warmth, clarity and natural pacing.",
    val characterMode: String = "distinct", val prefetch: Int = 3,
    val bufferSeconds: Int = 120,
    val skipFailedSpeech: Boolean = false,
    val withinPauseMs: Int = 80, val paragraphPauseMs: Int = 350,
    val fontSize: Int = 20, val theme: String = "dark", val cacheMb: Int = 256,
    val cloudUrl: String = "https://texttospeech.googleapis.com", val geminiUrl: String = "https://generativelanguage.googleapis.com"
) {
    companion object {
        val engines = setOf("vertex", "cloud", "gemini", "kokoro", "groq", "elevenlabs", "cartesia", "deepgram", "inworld", "speechify", "fish", "android")
    }
    /** Keep books and credentials usable when upgrading from a removed speech engine. */
    fun supportedEngine(): Settings = if (engine in engines) this else copy(engine = "kokoro", characterMode = "narrator")
    val textEngine: String get() = when (engine) { "android" -> "groq"; "fish" -> fishAnalysisEngine; "speechify" -> speechifyAnalysisEngine; "inworld" -> inworldAnalysisEngine; "deepgram" -> deepgramAnalysisEngine; "cartesia" -> cartesiaAnalysisEngine; "elevenlabs" -> elevenAnalysisEngine; "kokoro" -> kokoroAnalysisEngine; "groq" -> groqAnalysisEngine; else -> engine }
    val speechVoice: String get() = when (engine) { "android" -> androidTtsVoice; "fish" -> fishVoice; "speechify" -> speechifyVoice; "inworld" -> inworldVoice; "deepgram" -> deepgramVoice; "cartesia" -> cartesiaVoice; "elevenlabs" -> elevenVoice; "kokoro" -> kokoroVoice; "groq" -> groqVoice; else -> narratorVoice }
}
private val Context.readerSettings by preferencesDataStore(name = "settings")
class SettingsStore(context: Context) {
    private val store = context.readerSettings
    private val key = stringPreferencesKey("settings_json")
    val flow = store.data.map { prefs -> runCatching { json.decodeFromString<Settings>(prefs[key] ?: "{}") }.getOrDefault(Settings()).supportedEngine() }
    suspend fun save(value: Settings) { store.edit { it[key] = json.encodeToString(value) } }
}
