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
    val vertexProject: String = "", val vertexLocation: String = "us-central1", val vertexToken: String = "", val vertexUrl: String = "",
    val vertexBrokerUrl: String = "", val vertexBrokerPin: String = "", val vertexBrokerSecret: String = "",
    val analysisModel: String = "gemini-2.5-flash", val language: String = "en-US",
    val narratorVoice: String = "Charon", val narratorGender: String = "male",
    val narratorPrompt: String = "Read with warmth, clarity and natural pacing.",
    val characterMode: String = "performance", val prefetch: Int = 3,
    val bufferSeconds: Int = 120,
    val withinPauseMs: Int = 80, val paragraphPauseMs: Int = 350,
    val fontSize: Int = 20, val theme: String = "dark", val cacheMb: Int = 256,
    val cloudUrl: String = "https://texttospeech.googleapis.com", val geminiUrl: String = "https://generativelanguage.googleapis.com"
)
private val Context.readerSettings by preferencesDataStore(name = "settings")
class SettingsStore(context: Context) {
    private val store = context.readerSettings
    private val key = stringPreferencesKey("settings_json")
    val flow = store.data.map { prefs -> runCatching { json.decodeFromString<Settings>(prefs[key] ?: "{}") }.getOrDefault(Settings()) }
    suspend fun save(value: Settings) { store.edit { it[key] = json.encodeToString(value) } }
}
