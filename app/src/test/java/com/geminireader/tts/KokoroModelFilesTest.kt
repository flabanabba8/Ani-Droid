package com.geminireader.tts

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest

class KokoroModelFilesTest {
    private val data = "test model bytes".toByteArray()
    private fun manifest(path: String = "model.onnx") = KokoroManifest(KokoroModelFiles.VERSION, listOf(KokoroAsset(path, data.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })))

    @Test fun verifiesCopiesAndReusesInstalledModelThenRepairsTruncation() = runBlocking {
        val root = Files.createTempDirectory("model-install").toFile()
        try {
            var opened = 0
            val open = { _: String -> opened++; ByteArrayInputStream(data) }
            val folder = KokoroModelFiles.prepare(root, manifest(), open)
            assertArrayEquals(data, folder.resolve("model.onnx").readBytes())
            assertEquals(folder, KokoroModelFiles.prepare(root, manifest(), open))
            assertEquals(1, opened)
            folder.resolve("model.onnx").writeBytes(byteArrayOf())
            KokoroModelFiles.prepare(root, manifest(), open)
            assertEquals(2, opened)
            assertArrayEquals(data, folder.resolve("model.onnx").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun badHashAndCancellationNeverPublishPartialModel() = runBlocking {
        val root = Files.createTempDirectory("model-errors").toFile()
        try {
            try {
                KokoroModelFiles.prepare(root, manifest()) { ByteArrayInputStream(ByteArray(data.size)) }
                fail("Expected verification failure")
            } catch (_: IllegalArgumentException) { }
            assertTrue(root.listFiles().orEmpty().isEmpty())
            val job = launch {
                KokoroModelFiles.prepare(root, manifest()) {
                    cancel("Canceled extraction")
                    ByteArrayInputStream(data)
                }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(root.listFiles().orEmpty().isEmpty())
            KokoroModelFiles.prepare(root, manifest()) { ByteArrayInputStream(data) }
            assertTrue(root.resolve(KokoroModelFiles.VERSION).resolve(".ready").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsEscapingAssetPaths() = runBlocking {
        val root = Files.createTempDirectory("model-paths").toFile()
        try {
            for (path in listOf("../outside", "/absolute", "a/../../outside")) {
                try { KokoroModelFiles.prepare(root, manifest(path)) { ByteArrayInputStream(data) }; fail("Accepted unsafe path") }
                catch (_: IllegalArgumentException) { }
            }
        } finally { root.deleteRecursively() }
    }
}
