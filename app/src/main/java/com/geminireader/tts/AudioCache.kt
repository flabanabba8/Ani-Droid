package com.geminireader.tts

import com.geminireader.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class AudioCache(private val root: File) {
    val pinned = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    init { root.mkdirs() }
    companion object {
        fun key(speech: Speech, s: Settings): String {
            val fields = listOf("pcm-v1", s.engine, if (s.engine == "cloud") s.cloudUrl else s.geminiUrl, s.model, speech.voice, s.language, speech.prompt, speech.text, speech.pauseMs.toString())
            return MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    suspend fun get(speech: Speech, settings: Settings, engine: TtsEngine): File = withContext(Dispatchers.IO) {
        val file = File(root, "${key(speech, settings)}.wav")
        pinned.add(file.name)
        if (!file.exists()) {
            val bytes = Wav.encode(Wav.trimAndPad(engine.synthesize(speech, settings), speech.pauseMs))
            val temp = File(root, "${UUID.randomUUID()}.tmp")
            try { temp.writeBytes(bytes); check(temp.renameTo(file)) { "Could not cache audio" } } finally { temp.delete() }
        }
        file.setLastModified(System.currentTimeMillis())
        trim(settings.cacheMb.toLong() * 1024 * 1024)
        file
    }
    @Synchronized fun trim(limit: Long) {
        val files = root.listFiles().orEmpty().filter { it.extension == "wav" }.sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (file in files) if (size > limit && !pinned.contains(file.name)) { val bytes = file.length(); if (file.delete()) size -= bytes }
    }
    @Synchronized fun clear() { root.listFiles().orEmpty().filter { !pinned.contains(it.name) }.forEach { it.delete() } }
}
