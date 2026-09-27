package com.geminireader.tts

import com.geminireader.data.atomicWrite
import com.geminireader.data.json
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@Serializable data class KokoroAsset(val path: String, val size: Long, val sha256: String)
@Serializable data class KokoroManifest(val version: String, val files: List<KokoroAsset>)

/** Publish only a complete, verified model. Interrupted first-use extraction is safely retried. */
object KokoroModelFiles {
    const val VERSION = "kokoro-1.0-selective8-en-sherpa-1.13.8-v3"
    fun resolve(root: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && path.split('/').none { it == ".." }) { "Invalid model asset path" }
        return File(root, path).also { require(it.canonicalPath.startsWith(root.canonicalPath + File.separator)) }
    }

    suspend fun prepare(root: File, manifest: KokoroManifest, open: (String) -> InputStream): File = prepare(root, manifest, VERSION, open)

    suspend fun prepare(root: File, manifest: KokoroManifest, expectedVersion: String, open: (String) -> InputStream): File {
        require(manifest.version == expectedVersion && manifest.files.isNotEmpty()) { "Unsupported Kokoro model bundle" }
        require(manifest.files.map { it.path }.distinct().size == manifest.files.size)
        val target = File(root, expectedVersion)
        for (entry in manifest.files) {
            resolve(target, entry.path)
            require(entry.size >= 0 && entry.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid model asset manifest" }
        }
        val marker = json.encodeToString(manifest)
        if (File(target, ".ready").takeIf { it.isFile }?.readText() == marker &&
            manifest.files.all { resolve(target, it.path).let { file -> file.isFile && file.length() == it.size } }) return target
        root.mkdirs()
        val staging = File(root, "$expectedVersion.partial")
        check(!staging.exists() || staging.deleteRecursively()) { "Could not reset partial Kokoro model" }
        check(staging.mkdirs()) { "Could not create Kokoro model folder" }
        val context = currentCoroutineContext()
        try {
            for (entry in manifest.files) {
                context.ensureActive()
                val file = resolve(staging, entry.path)
                file.parentFile!!.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                open(entry.path).use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= entry.size) { "Kokoro asset exceeds its declared size" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                require(size == entry.size && digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256) { "Kokoro model verification failed; download it again" }
            }
            context.ensureActive()
            atomicWrite(File(staging, ".ready"), marker)
            check(!target.exists() || target.deleteRecursively()) { "Could not replace Kokoro model" }
            check(staging.renameTo(target)) { "Could not install Kokoro model" }
            return target
        } finally { staging.deleteRecursively() }
    }
}
