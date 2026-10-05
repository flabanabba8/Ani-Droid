package dev.anidroid

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Manual portable data only: no server connection, signed links, headers, or media. */
object LibraryBackup {
    private val lists=setOf("favorites","watchlist","history")
    private val numbers=mapOf("subtitleSize" to 16..72,"subtitleColor" to Int.MIN_VALUE..Int.MAX_VALUE,"subtitleDelayMs" to -10000..10000,"downloadHeight" to 360..1080)
    private val booleans=setOf("autoNext")
    /** Device-operational flags from older backups are accepted but ignored; they must not change a live download queue. */
    private val ignored=setOf("wifiOnly","queuePaused")
    private val idPattern=Regex("[A-Za-z0-9_-]{1,200}")
    private fun valid(t: Title)=t.provider in listOf("ani","luffy") && t.id.matches(idPattern) && t.name.length in 1..500 && t.info.length<=500
    private fun clean(t: Title)=t.copy(poster="",indexedAt=0,genres=emptyList()).toJson()
    private fun titles(array: JSONArray): JSONArray {
        require(array.length()<=1000) { "Too many titles in backup." }
        require((0 until array.length()).all {array.optJSONObject(it)!=null}) {"Invalid title entry."}
        return JSONArray(array.objects().map { j ->
            val t=titleFromJson(j);require(valid(t)) {"Invalid title entry."};clean(t)
        })
    }
    fun export(prefs: SharedPreferences): String {
        val output=JSONObject().put("schema",1)
        output.put("lists",JSONObject().apply { lists.forEach { key -> put(key,JSONArray(JSONArray(prefs.getString(key,"[]")).objects().map(::titleFromJson).filter(::valid).take(1000).map(::clean))) } })
        output.put("preferences",JSONObject().apply {
            numbers.keys.forEach { if(prefs.contains(it)) put(it,if(it=="subtitleDelayMs") prefs.getLong(it,0) else prefs.getInt(it,0)) }
            booleans.forEach { if(prefs.contains(it))put(it,prefs.getBoolean(it,false)) }
        })
        output.put("shows",JSONObject(prefs.getString("showPreferences","{}")))
        output.put("watched",JSONArray(prefs.getString("watched","[]")))
        return output.toString(2)
    }
    fun import(prefs: SharedPreferences,text: String) {
        require(text.toByteArray().size<=2*1024*1024) { "Backup is too large." }
        val input=JSONObject(text)
        require(input.getInt("schema")==1 && input.keys().asSequence().all { it in setOf("schema","lists","preferences","shows","watched") }) { "Unsupported backup format." }
        val values=input.getJSONObject("preferences")
        require(values.keys().asSequence().all { it in numbers || it in booleans || it in ignored }) { "Unsupported preference." }
        val savedLists=input.getJSONObject("lists")
        require(savedLists.keys().asSequence().all { it in lists })
        val cleaned=lists.associateWith { titles(savedLists.getJSONArray(it)) }
        val shows=input.getJSONObject("shows");require(shows.length()<=1000)
        shows.keys().asSequence().forEach { key ->
            require(key.matches(Regex("(ani|luffy):[A-Za-z0-9_-]{1,200}")))
            val v=shows.optJSONObject(key) ?: throw IllegalArgumentException("Invalid show preference.")
            listOf("audio","source","audioLanguage","audioLabel","subtitleLanguage","subtitleLabel").forEach { f -> require(!v.has(f) || v.get(f) is String) }
            require(!v.has("subtitleOff") || v.get("subtitleOff") is Boolean);require(!v.has("height") || v.get("height") is Int)
            require(v.keys().asSequence().all { it in setOf("audio","source","height","audioLanguage","audioLabel","subtitleLanguage","subtitleLabel","subtitleOff") })
            require(v.optString("source","auto") in listOf("auto","cinejoy","vixsrc","lookmovie"))
            require(v.optInt("height",0) in 0..4320)
            listOf("audio","audioLanguage","audioLabel","subtitleLanguage","subtitleLabel").forEach { require(v.optString(it).length<=100) }
        }
        val watched=input.getJSONArray("watched");require(watched.length()<=10000)
        (0 until watched.length()).forEach { require(watched.get(it) is String && watched.getString(it).matches(Regex("(ani|luffy):[A-Za-z0-9_-]{1,200}:[0-9]{1,4}:[0-9.]{1,10}"))) }
        numbers.forEach { (key,range) -> if(values.has(key))require(values.getLong(key) in range.first.toLong()..range.last.toLong()) }
        if(values.has("subtitleSize")) require(values.getInt("subtitleSize") in listOf(16,20,24,28,32,36))
        if(values.has("subtitleColor")) require(values.getInt("subtitleColor") in listOf(-1,0xFFFFEB3B.toInt(),0xFF73E0C1.toInt(),0xFF80DEFF.toInt()))
        if(values.has("downloadHeight")) require(values.getInt("downloadHeight") in listOf(360,480,720,1080))
        booleans.forEach { if(values.has(it))values.getBoolean(it) }
        // Validate everything before making one atomic preference change.
        prefs.edit().apply {
            cleaned.forEach { (key,value)->putString(key,value.toString()) }
            putString("showPreferences",shows.toString());putString("watched",watched.toString())
            numbers.forEach { (key,_) -> if(values.has(key)) {if(key=="subtitleDelayMs")putLong(key,values.getLong(key)) else putInt(key,values.getInt(key))} }
            booleans.forEach { if(values.has(it))putBoolean(it,values.getBoolean(it)) }
        }.apply()
    }
}
