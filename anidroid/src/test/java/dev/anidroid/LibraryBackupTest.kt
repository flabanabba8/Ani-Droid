package dev.anidroid

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    /** A memory-only preference editor makes partial writes observable without an emulator. */
    private fun preferences(saved: MutableMap<String, Any>): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        val pending = mutableMapOf<String, Any>()
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when {
                method.name.startsWith("put") -> { pending[args!![0] as String] = args[1]; editor }
                method.name == "apply" -> { saved.putAll(pending); null }
                method.name == "commit" -> { saved.putAll(pending); true }
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            if (method.name == "edit") editor else throw AssertionError("Unexpected preference access: ${method.name}")
        } as SharedPreferences
    }

    private fun backup() = JSONObject().put("schema", 1)
        .put("lists", JSONObject().put("favorites", JSONArray()).put("watchlist", JSONArray()).put("history", JSONArray()))
        .put("preferences", JSONObject()).put("shows", JSONObject()).put("watched", JSONArray())

    @Test fun malformedTypesCannotChangeTheLibrary() {
        val saved = mutableMapOf<String, Any>("favorites" to "keep", "autoNext" to true)
        val prefs = preferences(saved)
        val bad = listOf(
            backup().put("schema", 1.9),
            backup().put("schema", "1"),
            backup().put("preferences", JSONObject().put("subtitleSize", "24")),
            backup().put("preferences", JSONObject().put("subtitleDelayMs", 1.9)),
            backup().put("preferences", JSONObject().put("autoNext", "false")))
        bad.forEach { data ->
            try { LibraryBackup.import(prefs, data.toString()); fail("Coerced invalid backup type") }
            catch (_: IllegalArgumentException) { }
            assertEquals(mapOf("favorites" to "keep", "autoNext" to true), saved)
        }
        LibraryBackup.import(prefs, backup().put("preferences", JSONObject()
            .put("subtitleSize", 24).put("subtitleDelayMs", -500).put("autoNext", false)).toString())
        assertEquals("[]", saved["favorites"])
        assertEquals(24, saved["subtitleSize"])
        assertEquals(-500L, saved["subtitleDelayMs"])
        assertEquals(false, saved["autoNext"])
    }
}
