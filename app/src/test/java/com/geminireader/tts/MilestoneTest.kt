package com.geminireader.tts

import com.geminireader.data.*
import com.geminireader.playback.BufferPolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class MilestoneTest {
    @Test fun durationBufferTracksSpeedAndCurrentOffset() {
        assertEquals(110_000L, BufferPolicy.remainingMs(listOf(60_000, 60_000, 60_000), 1, 10_000, 1f))
        assertEquals(55_000L, BufferPolicy.remainingMs(listOf(60_000, 60_000, 60_000), 1, 10_000, 2f))
        assertEquals(0L, BufferPolicy.remainingMs(emptyList(), 0, 0, 1f))
        assertEquals("2m 5s ready", BufferPolicy.label(125_000))
    }
    @Test fun offsetOnlyRestoresAgainstSameAudio() {
        assertEquals(5000L, BufferPolicy.resumeOffset("a.wav", "a.wav", 5000, 6000))
        assertEquals(5999L, BufferPolicy.resumeOffset("a.wav", "a.wav", 8000, 6000))
        assertEquals(0L, BufferPolicy.resumeOffset("a.wav", "b.wav", 5000, 6000))
        assertEquals(0L, BufferPolicy.resumeOffset("", "a.wav", 5000, 6000))
    }
    @Test fun scrollBookmarkCannotOverwriteAudioBookmark() {
        val dir = Files.createTempDirectory("reader-bookmark-test").toFile()
        try {
            val repo = BookRepository(dir)
            val saved = Position(1, 12, 44, 9123, "audio.wav", 1.5f)
            repo.position("book", saved)
            assertEquals(saved, repo.readingPosition("book"))
            repo.readingPosition("book", Position(1, 30))
            assertEquals(saved, BookRepository(dir).position("book"))
            assertEquals(Position(1, 30), BookRepository(dir).readingPosition("book"))
            dir.resolve("book/reading-position.json").setLastModified(1000)
            assertEquals(saved, repo.readingPosition("book"))
            dir.resolve("book/reading-position.json").writeText("corrupt")
            assertEquals(saved, repo.readingPosition("book"))
            assertEquals("", json.decodeFromString<Position>("""{"chapter":0,"paragraph":1}""").audioKey)
        } finally { dir.deleteRecursively() }
    }
    @Test fun wavExportConcatenatesPcmWithoutExtraHeaders() {
        val dir = Files.createTempDirectory("reader-export-test").toFile()
        try {
            val a = dir.resolve("a.wav").apply { writeBytes(Wav.encode(Pcm(byteArrayOf(1, 2, 3, 4)))) }
            val b = dir.resolve("b.wav").apply { writeBytes(Wav.encode(Pcm(byteArrayOf(5, 6)))) }
            val out = ByteArrayOutputStream(); WavExport.write(listOf(a, b), out)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), Wav.decode(out.toByteArray()).bytes)
            val full = dir.resolve("full.wav").apply { writeBytes(Wav.encode(Pcm(ByteArray(48_000)))) }
            assertEquals(1000L, WavExport.inspect(full).durationMs)
            val different = dir.resolve("other.wav").apply { writeBytes(Wav.encode(Pcm(byteArrayOf(1,2), 22050))) }
            try { WavExport.write(listOf(a, different), ByteArrayOutputStream()); fail("Mixed sample rates must fail") } catch (_: IllegalArgumentException) {}
        } finally { dir.deleteRecursively() }
    }
    @Test fun truncatedExportRejectedBeforeWriting() {
        val dir = Files.createTempDirectory("reader-export-bad").toFile()
        try {
            val a = dir.resolve("a.wav").apply { writeBytes(Wav.encode(Pcm(ByteArray(100))).copyOf(50)) }
            val output = ByteArrayOutputStream()
            try { WavExport.write(listOf(a), output); fail("Expected malformed audio error") } catch (_: IllegalArgumentException) {}
            assertEquals(0, output.size())
        } finally { dir.deleteRecursively() }
    }
    @Test fun tokenRenewalDeduplicatesRefreshAndHandlesExpiryAnd401() = runBlocking {
        var now = 1_000_000L
        val session = TokenSession { now }
        val calls = AtomicInteger()
        suspend fun fetch(): AccessToken { delay(10); return AccessToken("token-${calls.incrementAndGet()}", now + 3600_000) }
        val tokens = (1..10).map { async { session.get("broker", fetch = ::fetch) } }.awaitAll()
        assertEquals(setOf("token-1"), tokens.toSet()); assertEquals(1, calls.get())
        now += 3550_000
        assertEquals("token-2", session.get("broker", fetch = ::fetch))
        val renewed = (1..5).map { async { session.get("broker", rejected = "token-2", fetch = ::fetch) } }.awaitAll()
        assertEquals(setOf("token-3"), renewed.toSet()); assertEquals(3, calls.get())
        assertEquals("token-4", session.get("other-broker", fetch = ::fetch))
    }
    @Test fun brokerRequiresHttpsPinAndPairingSecret() {
        val good = Settings(vertexBrokerUrl = "https://LOCAL_DEVICE_ADDRESS/token", vertexBrokerPin = "a".repeat(64), vertexBrokerSecret = "s".repeat(40))
        VertexAuth.validate(good)
        for (bad in listOf(good.copy(vertexBrokerUrl = "http://LOCAL_DEVICE_ADDRESS/token"), good.copy(vertexBrokerPin = "bad"), good.copy(vertexBrokerSecret = "short"), good.copy(vertexBrokerUrl = "https://host/token?secret=bad"))) {
            try { VertexAuth.validate(bad); fail("Unsafe broker configuration accepted") } catch (_: IllegalArgumentException) {}
        }
    }
}
