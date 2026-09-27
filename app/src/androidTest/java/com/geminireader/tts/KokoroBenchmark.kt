package com.geminireader.tts

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in benchmark; run explicitly with -e class and -e variant. Never changes saved settings. */
@RunWith(AndroidJUnit4::class)
class KokoroBenchmark {
    @Test fun compareVariant() {
        val variant = InstrumentationRegistry.getArguments().getString("variant") ?: return
        require(variant in listOf("fp32", "fp16", "selective8"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val downloads = KokoroDownloads(context)
        kotlinx.coroutines.runBlocking { downloads.verifyForLoad() }
        KokoroNativeLoader.initialize(downloads.runtimeFolder)
        val base = File(context.noBackupFilesDir, "kokoro/${KokoroModelFiles.VERSION}")
        val folder = File(context.filesDir, "benchmark").apply { mkdirs() }
        val modelPath = File(folder, "$variant.onnx")
        val result = JSONObject().put("variant", variant).put("modelBytes", modelPath.length())
        val peakPss = AtomicInteger(); val running = AtomicBoolean(true)
        fun pss(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        result.put("baselinePssKiB", pss())
        val sampler = Thread {
            while (running.get()) { peakPss.accumulateAndGet(pss(), ::maxOf); Thread.sleep(250) }
        }.apply { start() }
        var model: OfflineTts? = null
        try {
            val loadStart = SystemClock.elapsedRealtime()
            model = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(model = modelPath.path, voices = File(base, "voices.bin").path,
                    tokens = File(base, "tokens.txt").path, dataDir = File(base, "espeak-ng-data").path,
                    lexicon = File(base, "lexicon-us-en.txt").path, lang = "en-us"),
                numThreads = 2, provider = "cpu", debug = false), maxNumSentences = 1))
            result.put("loadMs", SystemClock.elapsedRealtime() - loadStart).put("loadedPssKiB", pss())
            val texts = listOf(
                "The reader is ready. We can continue the story now.",
                "The garden was quiet after the rain. A small bird landed beside the window, shook its wings, and sang.",
                "Mara opened the old wooden gate and paused to listen. Beyond the orchard, a bell rang twice. She picked up her bag, waved to her brother, and followed the narrow path toward the village. The morning air was cool, and sunlight was beginning to reach the rooftops. Today, she decided, would be a good day to begin again."
            )
            val rows = JSONArray()
            repeat(2) { iteration ->
                texts.forEachIndexed { index, text ->
                    val cpu = Process.getElapsedCpuTime(); val start = SystemClock.elapsedRealtime()
                    val audio = model.generate(text, sid = 3, speed = 1f)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    val row = JSONObject().put("iteration", iteration).put("fixture", index).put("characters", text.length)
                        .put("wallMs", elapsed).put("cpuMs", Process.getElapsedCpuTime() - cpu)
                        .put("audioSeconds", audio.samples.size.toDouble() / audio.sampleRate)
                        .put("pssKiB", pss()).put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                        .put("finite", audio.samples.all { it.isFinite() }).put("clippedSamples", audio.samples.count { kotlin.math.abs(it) >= 0.999f })
                    File(folder, "$variant-$iteration-$index.wav").writeBytes(Wav.encode(KokoroPcm.encode(audio.samples, audio.sampleRate)))
                    rows.put(row)
                }
            }
            result.put("runs", rows)
            result.put("status", "ok")
        } catch (e: Exception) {
            result.put("status", "error").put("error", e.toString())
        } finally {
            result.put("peakPssKiB", peakPss.get())
            model?.release()
            result.put("releasedPssKiB", pss())
            running.set(false); sampler.join()
            File(folder, "$variant.json").writeText(result.toString(2))
            println("KOKORO_BENCHMARK $result")
        }
    }
}
