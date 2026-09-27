package com.geminireader.tts

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geminireader.data.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KokoroOnDeviceTest {
    @Test fun chapterPreparationQueuesIndividualSentences() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as com.geminireader.ReaderApp
        val paragraphs = listOf("The gate opened. A bird flew away! Where did it go?", "Another day began.")
        val book = withContext(Dispatchers.IO) {
            app.books.save(com.geminireader.data.Book(title = "Sentence queue test", format = "txt",
                chapters = listOf(com.geminireader.data.Chapter("One", paragraphs))))
        }
        try {
            val settings = Settings(engine = "kokoro", characterMode = "narrator")
            val plan = app.playback.prepareChapter(book, 0, settings)
            assertEquals(listOf("The gate opened. ", "A bird flew away! ", "Where did it go?", "Another day began."), plan.map { it.text })
            val speech = plan.mapIndexed { i, segment -> app.playback.speechFor(segment, settings, plan.getOrNull(i + 1)?.paragraph != segment.paragraph) }
            assertEquals(listOf(80, 80, 350, 350), speech.map { it.pauseMs })
            assertEquals(paragraphs, app.books.load(book.id).chapters[0].paragraphs)
        } finally { withContext(Dispatchers.IO) { app.books.delete(book.id) } }
    }

    @Test fun realLocalGenerationCancellationAndReuse() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = KokoroTtsClient(context)
        val settings = Settings(engine = "kokoro", characterMode = "narrator")
        try {
            if (InstrumentationRegistry.getArguments().getString("download_kokoro") == "yes") {
                client.startDownload()
                withTimeout(900_000) { client.downloading.first { !it } }
                assertTrue(client.status.value, client.installed.value)
            }
            val started = SystemClock.elapsedRealtime()
            val pcm = client.synthesize(Speech("Thus my first order of business was summoning this Guardian Beast.", "", "af_heart"), settings)
            java.io.File(context.cacheDir, "kokoro-test-output.wav").writeBytes(Wav.encode(pcm))
            assertEquals(24000, pcm.rate)
            assertTrue(pcm.bytes.size > 24000)
            assertTrue(pcm.bytes.any { it != 0.toByte() })
            assertNoVocoderRinging(pcm)
            Log.i("KokoroOnDeviceTest", "Generated ${pcm.bytes.size / 48000.0}s of real local speech in ${(SystemClock.elapsedRealtime() - started) / 1000.0}s")
            val canceled = launch { client.synthesize(Speech("The story continues on this phone. ".repeat(12), "", "af_heart"), settings) }
            withTimeout(60_000) {
                client.status.first { it.startsWith("Generating") }
                canceled.cancelAndJoin()
            }
            assertTrue(canceled.isCancelled)
            val next = client.synthesize(Speech("Listening continues.", "", "am_michael"), settings)
            assertTrue(next.bytes.isNotEmpty())
        } finally { client.release() }
    }
    /** Regression for the int8 model's narrow 4.8/9.6 kHz tones, measured before playback. */
    private fun assertNoVocoderRinging(pcm: Pcm) {
        val buffer = java.nio.ByteBuffer.wrap(pcm.bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val samples = DoubleArray(pcm.bytes.size / 2) { buffer.short / 32768.0 }
        val window = 2400 // 100 ms: 10 Hz bins at the model's 24 kHz sample rate.
        fun power(frequency: Int): Double {
            val coefficient = 2 * kotlin.math.cos(2 * Math.PI * frequency / pcm.rate)
            var total = 0.0
            for (start in 0..samples.size - window step window / 2) {
                var previous = 0.0; var before = 0.0
                for (i in 0 until window) {
                    val weighted = samples[start + i] * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / window))
                    val current = weighted + coefficient * previous - before
                    before = previous; previous = current
                }
                total += previous * previous + before * before - coefficient * previous * before
            }
            return total.coerceAtLeast(1e-30)
        }
        for (frequency in listOf(4800, 9600)) {
            val neighbors = (40..190 step 10).flatMap { listOf(power(frequency - it), power(frequency + it)) }.sorted()
            val floor = (neighbors[15] + neighbors[16]) / 2
            val prominence = 10 * kotlin.math.log10(power(frequency) / floor)
            Log.i("KokoroOnDeviceTest", "$frequency Hz tone prominence: $prominence dB")
            assertTrue("Kokoro has a narrow $frequency Hz ringing artifact: $prominence dB", prominence < 8.0)
        }
    }

}
