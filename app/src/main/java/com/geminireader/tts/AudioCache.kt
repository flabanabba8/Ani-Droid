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
            val endpoint = when (s.engine) { "groq" -> GroqTtsClient.ENDPOINT; "kokoro" -> KokoroModelFiles.VERSION; "vertex" -> "${s.vertexUrl}|${s.vertexProject}|${s.vertexLocation}"; "cloud" -> s.cloudUrl; else -> s.geminiUrl }
            val fields = if (s.engine == "android") listOf("android-tts-wav-v1", s.androidTtsEngine, s.androidTtsVoice, s.androidTtsOfflineOnly.toString(), speech.text, speech.pauseMs.toString()) else if (s.engine == "fish") listOf("fish-wav-v1", FishTtsClient.BASE, FishTtsClient.MODEL, speech.voice.ifBlank { s.fishVoice }, speech.text, speech.pauseMs.toString()) else if (s.engine == "speechify") listOf("speechify-wav-v1", SpeechifyTtsClient.BASE, SpeechifyTtsClient.MODEL, speech.voice.ifBlank { s.speechifyVoice }, speech.text, speech.pauseMs.toString()) else if (s.engine == "inworld") listOf("inworld-wav-v1", InworldTtsClient.BASE, InworldTtsClient.MODEL, speech.voice.ifBlank { s.inworldVoice }, speech.text, speech.pauseMs.toString()) else if (s.engine == "deepgram") listOf("deepgram-wav-v2", DeepgramTtsClient.BASE, speech.voice.ifBlank { s.deepgramVoice }, speech.text, speech.pauseMs.toString()) else if (s.engine == "cartesia") listOf("cartesia-wav-v1", CartesiaTtsClient.BASE, CartesiaTtsClient.VERSION, s.cartesiaModel, speech.voice.ifBlank { s.cartesiaVoice }, speech.text, speech.pauseMs.toString()) else if (s.engine == "elevenlabs") listOf("eleven-pcm-v1", ElevenTtsClient.BASE, s.elevenModel, speech.voice.ifBlank { s.elevenVoice }, speech.text, speech.pauseMs.toString()) else if (s.engine == "groq") listOf("groq-wav-v1", endpoint, s.groqModel, speech.voice.takeIf { it in com.geminireader.analysis.GroqVoices.namesFor(s.groqModel) } ?: s.groqVoice, speech.text, speech.pauseMs.toString()) else if (s.engine == "kokoro") listOf("kokoro-device-wav-v1", endpoint, "kokoro", speech.voice.takeIf { it in com.geminireader.analysis.KokoroVoices.names } ?: s.kokoroVoice, speech.text, speech.pauseMs.toString()) else listOf("pcm-v1", s.engine, endpoint, s.model, speech.voice, s.language, speech.prompt, speech.text, speech.pauseMs.toString())
            return MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    fun contains(speech: Speech, settings: Settings): Boolean = File(root, "${key(speech, settings)}.wav").isFile
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
