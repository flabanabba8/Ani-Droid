package com.geminireader.data

import com.geminireader.tts.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class SpendingTest {
    private val url = "https://us-central1-aiplatform.googleapis.com/v1beta1/projects/test/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent"
    private val usage = obj("usageMetadata" to obj("promptTokenCount" to JsonPrimitive(1000),
        "candidatesTokenCount" to JsonPrimitive(100), "thoughtsTokenCount" to JsonPrimitive(50)))

    @Test fun persistsConcurrentUsageSeparatedByBookAndCountsEveryResponse() = runBlocking {
        val root = Files.createTempDirectory("spending").toFile()
        try {
            val file = File(root, "spending.json")
            val tracker = SpendingTracker(file)
            coroutineScope {
                repeat(20) { index -> launch(Dispatchers.Default) {
                    tracker.record(url, obj("text" to str("PRIVATE TEXT")), usage, SpendingBook("book-${index % 2}", "Book ${index % 2}"))
                } }
            }
            tracker.record(url, obj(), usage)
            val rows = SpendingTracker(file).state.value.rows
            assertEquals(3, rows.size)
            assertEquals(21L, rows.sumOf { it.requests })
            assertEquals(10 * 0.000675, rows.first { it.bookId == "book-0" }.usd, 0.00000001)
            assertEquals("Book 1", rows.first { it.bookId == "book-1" }.bookTitle)
            assertEquals(0L, rows.sumOf { it.unpriced })
            assertFalse(file.readText().contains("PRIVATE TEXT"))
            assertEquals(21 * 0.000675, rows.sumOf { it.usd }, 0.00000001)
        } finally { root.deleteRecursively() }
    }

    @Test fun ignoresMocksAndCatalogAndMarksUnknownOrMissingUsage() {
        val root = Files.createTempDirectory("spending-unknown").toFile()
        try {
            val tracker = SpendingTracker(File(root, "history.json"))
            tracker.record("http://127.0.0.1/models/gemini-2.5-flash:generateContent", obj(), usage)
            tracker.record("https://generativelanguage.googleapis.com/v1beta/models", obj(), obj())
            assertTrue(tracker.state.value.rows.isEmpty())
            tracker.record(url.replace("gemini-2.5-flash:", "unknown-model:"), obj(), usage)
            tracker.record(url, obj(), obj())
            assertEquals(2L, tracker.state.value.rows.sumOf { it.unpriced })
            assertEquals(0.0, tracker.state.value.rows.sumOf { it.usd }, 0.0)
        } finally { root.deleteRecursively() }
    }

    @Test fun estimatesCloudSpeechAndCacheReplayAddsNoCost() = runBlocking {
        val root = Files.createTempDirectory("spending-audio").toFile()
        try {
            val tracker = SpendingTracker(File(root, "history.json"))
            val pcm = Pcm(ByteArray(48000)) // One second before silence trimming or playback pauses.
            val response = obj("audioContent" to str(Base64.getEncoder().encodeToString(Wav.encode(pcm))))
            val s = Settings(engine = "cloud")
            val speech = Speech("Text", "Hint", "Charon")
            val engine = object : TtsEngine {
                override suspend fun synthesize(speech: Speech, settings: Settings): Pcm {
                    tracker.record("https://texttospeech.googleapis.com/v1/text:synthesize", CloudTtsClient.body(speech, settings), response, SpendingBook("a", "A"))
                    return pcm
                }
            }
            val cache = AudioCache(File(root, "cache"))
            cache.get(speech, s, engine)
            cache.get(speech, s, engine)
            val row = tracker.state.value.rows.single()
            assertEquals(1L, row.requests)
            assertEquals(1L, row.approximated)
            assertEquals(0.000502, row.usd, 0.000000001)
        } finally { root.deleteRecursively() }
    }

    @Test fun handlesThinkingCacheDiscountAndLongContextRates() {
        assertEquals(0.55, SpendingRates.price("gemini-2.5-flash", 1_000_000, 100_000)!!, 0.000001)
        assertEquals(0.28, SpendingRates.price("gemini-2.5-flash", 1_000_000, 100_000, 1_000_000)!!, 0.000001)
        assertEquals(0.25, SpendingRates.price("gemini-2.5-pro", 200_000, 0)!!, 0.000001)
        assertEquals(0.5000025, SpendingRates.price("gemini-2.5-pro", 200_001, 0)!!, 0.000001)
        assertNull(SpendingRates.price("future-model", 100, 100))
    }

    @Test fun corruptHistoryIsReportedAndNeverOverwritten() {
        val root = Files.createTempDirectory("spending-corrupt").toFile()
        try {
            val file = File(root, "history.json").apply { writeText("broken") }
            val tracker = SpendingTracker(file)
            tracker.record(url, obj(), usage)
            assertTrue(tracker.error.value.isNotEmpty())
            assertEquals("broken", file.readText())
        } finally { root.deleteRecursively() }
    }
}
