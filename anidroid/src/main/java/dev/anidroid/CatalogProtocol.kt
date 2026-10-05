package dev.anidroid

import org.json.JSONArray
import org.json.JSONObject

fun Title.toJson(): JSONObject = JSONObject().put("provider", provider).put("id", id).put("name", name)
    .put("poster", poster).put("info", info).put("series", series).put("indexedAt", indexedAt).put("genres", JSONArray(genres))
fun titleFromJson(json: JSONObject) = Title(json.getString("provider"), json.getString("id"), json.getString("name"),
    json.optString("poster"), json.optString("info"), json.optBoolean("series", true), json.optLong("indexedAt"), Providers.parseGenres(json.opt("genres")))
fun Details.toJson(): JSONObject = JSONObject().put("title", title.toJson()).put("description", description)
    .put("episodes", JSONArray(episodes.map { JSONObject().put("id", it.id).put("season", it.season).put("number", it.number).put("name", it.name) }))
    .put("audio", JSONArray(audio.map { JSONObject().put("id", it.id).put("label", it.label) }))
fun detailsFromJson(json: JSONObject) = Details(titleFromJson(json.getJSONObject("title")), json.optString("description"),
    json.array("episodes").objects().map { Episode(it.getString("id"), it.getInt("season"), it.getString("number"), it.optString("name")) },
    json.array("audio").objects().map { Audio(it.getString("id"), it.getString("label")) })

fun Stream.toJson(): JSONObject = JSONObject().put("label",label).put("url",url).put("headers",JSONObject(headers))
    .put("height",height).put("sources",JSONArray(sources)).put("captions",JSONArray(captions.map { JSONObject().put("name",it.name).put("url",it.url) }))
fun streamFromJson(json: JSONObject): Stream {
    val headers=json.optJSONObject("headers") ?: JSONObject()
    return Stream(json.getString("label"),json.getString("url"),headers.keys().asSequence().associateWith { headers.getString(it) },
        json.array("captions").objects().map { Caption(it.getString("name"),it.getString("url")) },json.optInt("height"),json.array("sources").let { a -> (0 until a.length()).map { a.getString(it) } })
}
