package com.geminireader.data

import android.media.MediaExtractor
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geminireader.ReaderApp
import com.geminireader.analysis.Character
import com.geminireader.text.Segment
import com.geminireader.tts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class CanaryFeaturesOnDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ReaderApp

    @Test fun audiobookContainsPlayableAacAndChapterAndCoverMetadata() {
        val folder = File(app.cacheDir, "audiobook-check").apply { mkdirs() }
        val wav = File(folder, "one.wav").apply { writeBytes(Wav.encode(Pcm(ByteArray(48_000), 24000))) }
        val cover = ByteArrayOutputStream().also { out -> val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle() }.toByteArray()
        val output = File(folder, "example.m4b")
        val extractor = MediaExtractor()
        try {
            AudiobookExport.write(listOf(AudiobookExport.ChapterAudio("First", listOf(wav)), AudiobookExport.ChapterAudio("Second", listOf(wav))), output, "Synthetic book", "Test author", cover) {}
            extractor.setDataSource(output.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mp4a-latm", format.getString("mime"))
            assertEquals(24000, format.getInteger("sample-rate"))
            val metadata = android.media.MediaMetadataRetriever()
            try {
                metadata.setDataSource(output.path)
                assertEquals("Synthetic book", metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE))
                assertEquals("Test author", metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST))
                assertArrayEquals(cover, metadata.embeddedPicture)
            } finally { metadata.release() }
            extractor.selectTrack(0)
            val buffer = java.nio.ByteBuffer.allocate(16_384)
            assertTrue(extractor.readSampleData(buffer, 0) > 0)
            val bytes = output.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(bytes.contains("chpl")); assertTrue(bytes.contains("First")); assertTrue(bytes.contains("Second"))
            assertTrue(bytes.contains("covr")); assertTrue(bytes.contains("Synthetic book")); assertTrue(bytes.contains("Test author"))
        } finally { extractor.release(); folder.deleteRecursively() }
    }

    @Test fun backupRoundtripPreservesLibraryButExcludesAuthentication() = runBlocking {
        val originalSettings = app.settingsStore.flow.first()
        val before = app.books.list().map { it.id }.toSet()
        val originals = listOf("series.json", "series-links.json", "pronunciations.json").associateWith { File(app.filesDir, it).takeIf(File::exists)?.readBytes() }
        val book = app.books.save(Book(title = "Backup fixture", format = "txt", chapters = listOf(Chapter("One", listOf("Hello.")))))
        try {
            app.settingsStore.save(originalSettings.copy(vertexToken = "synthetic-auth-exclusion-marker", vertexProject = "synthetic-private-project"))
            app.books.position(book.id, Position(paragraph = 0, segment = 2, offsetMs = 200, audioKey = "old.wav"))
            app.analyzer.saveCast(book.id, listOf(Character("alice", "Alice", voice = "Aoede", performanceLocked = true)))
            app.notes.save(book.id, ReadingNote(chapter = 0, paragraph = 0, start = 0, end = 6, quote = "Hello.", note = "A note"))
            app.performances.saveSeries(listOf(Series("fixture-series", "Fixture", listOf(VoiceProfile("voice", "Alice", "Aoede")))))
            app.performances.saveLink(book.id, SeriesLink("fixture-series", mapOf("alice" to "voice")))
            app.performances.save(listOf(Pronunciation(written = "Alice", spoken = "Al-iss", owner = book.id)))
            val output = ByteArrayOutputStream(); LibraryBackup.write(app, output)
            val text = ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip -> zip.nextEntry; zip.bufferedReader().readText() }
            assertFalse(text.contains("synthetic-auth-exclusion-marker")); assertFalse(text.contains("synthetic-private-project"))
            assertFalse(text.contains("vertexToken")); assertFalse(text.contains("vertexBroker"))
            LibraryBackup.restore(app, ByteArrayInputStream(output.toByteArray()))
            val restored = app.books.list().single { it.title == "Backup fixture" && it.id != book.id }
            assertEquals(book.chapters, app.books.load(restored.id).chapters)
            assertEquals("A note", app.notes.list(restored.id).single().note)
            assertEquals("Aoede", app.analyzer.cast(restored.id).single().voice)
            assertNotEquals("fixture-series", app.performances.link(restored.id).series)
            assertEquals("Al-iss", app.performances.applicable(restored.id).single().spoken)
            assertEquals("", app.books.position(restored.id).audioKey)
            assertEquals("synthetic-auth-exclusion-marker", app.settingsStore.flow.first().vertexToken)
        } finally {
            app.books.list().filter { it.id !in before }.forEach { app.books.delete(it.id) }
            originals.forEach { (name, data) -> val file = File(app.filesDir, name); if (data == null) file.delete() else file.writeBytes(data) }
            app.settingsStore.save(originalSettings)
        }
    }

    @Test fun malformedBackupCannotExtractPathsOrChangeLibrary() {
        val before = app.books.list()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry("../settings.json")); it.write("not valid".toByteArray()); it.closeEntry() }
        try { LibraryBackup.restore(app, ByteArrayInputStream(output.toByteArray())); fail("Invalid archive accepted") } catch (_: IllegalArgumentException) { } catch (_: java.util.zip.ZipException) { }
        assertEquals(before, app.books.list())
    }

    @Test fun sleepAtParagraphAndChapterBoundaryPausesAndCanResume() = runBlocking {
        val s = app.settingsStore.flow.first()
        val lastFile = File(app.filesDir, "last-book.txt"); val originalLast = lastFile.takeIf(File::exists)?.readBytes()
        val book = app.books.save(Book(title = "Timer fixture", format = "txt", chapters = listOf(Chapter("One", listOf("First.", "Second.")))))
        val settings = Settings(characterMode = "narrator")
        try {
            withContext(Dispatchers.Main) { app.playback.stopAndJoin(); app.settingsStore.save(settings); app.settings = settings; app.book = book; app.chapter = 0 }
            val lines = book.chapters[0].paragraphs.mapIndexed { i, text ->
                val speech = Speech(text, "", "Charon")
                val file = "${AudioCache.key(speech, settings)}.wav"
                app.offline.audio(book.id, 0, file).writeBytes(Wav.encode(Pcm(ByteArray(48_000))))
                PreparedLine(Segment(i, 0, text.length, text), speech, "Narrator", file)
            }
            app.offline.save(book.id, 0, PreparedChapter(app.offline.signature(book, 0, settings), lines, complete = true))
            withContext(Dispatchers.Main) {
                app.playback.setSleep("paragraph"); app.playback.play(book, 0, 0)
                withTimeout(10_000) { while (app.playback.sleepMode != "off") delay(100) }
                assertFalse(app.playback.player.playWhenReady); assertEquals(1, app.playback.active?.paragraph)
                app.playback.setSleep("chapter"); app.playback.toggle()
                withTimeout(10_000) { while (app.playback.sleepMode != "off") delay(100) }
                assertFalse(app.playback.player.playWhenReady)
                assertEquals(0, app.playback.activeChapter)
            }
        } finally {
            withContext(Dispatchers.Main) { app.playback.stopAndJoin(); app.playback.setSleep("off"); app.book = null; app.settingsStore.save(s); app.settings = s }
            app.books.delete(book.id)
            if (originalLast == null) lastFile.delete() else lastFile.writeBytes(originalLast)
        }
    }
    @Test fun skippedAudioKeepsPlaybackAndExportPositionsAligned() = runBlocking {
        val original = app.settingsStore.flow.first()
        val settings = Settings(characterMode = "narrator", skipFailedSpeech = true)
        val book = app.books.save(Book(title = "Recovery fixture", format = "txt", chapters = listOf(Chapter("One", listOf("First.", "Second.")))))
        val lastFile = File(app.filesDir, "last-book.txt"); val originalLast = lastFile.takeIf(File::exists)?.readBytes()
        try {
            withContext(Dispatchers.Main) { app.playback.stopAndJoin(); app.settingsStore.save(settings); app.settings = settings; app.book = book; app.chapter = 0 }
            book.chapters[0].paragraphs.forEachIndexed { i, text ->
                val speech = com.geminireader.analysis.VoiceDirector.direct(Segment(i, 0, text.length, text), settings, null, "", true)
                val file = app.offline.audio(book.id, 0, "${AudioCache.key(speech, settings)}.wav")
                file.writeBytes(if (i == 0) byteArrayOf(1) else Wav.encode(Pcm(ByteArray(240_000))))
            }
            withContext(Dispatchers.Main) {
                app.playback.play(book, 0, 0)
                withTimeout(10_000) { while (app.playback.active?.paragraph != 1 || app.playback.player.mediaItemCount != 1) delay(100) }
                app.playback.player.pause()
                assertEquals(0, app.skippedPassages.list(book.id).single().segment.paragraph)
                assertEquals(1, app.books.position(book.id).paragraph)
                assertTrue(app.playback.exportSelection().description.contains("paragraphs 2–2"))
                assertFalse(app.playback.speechFailed)
            }
        } finally {
            withContext(Dispatchers.Main) { app.playback.stopAndJoin(); app.book = null; app.settingsStore.save(original); app.settings = original }
            app.books.delete(book.id)
            if (originalLast == null) lastFile.delete() else lastFile.writeBytes(originalLast)
        }
    }

    @Test fun scheduledQueueCompletesAndPausesWhenConfigurationChanges() = runBlocking {
        val original = app.settingsStore.flow.first()
        val queueFile = File(app.filesDir, "preparation-queue.json"); val oldQueue = queueFile.takeIf(File::exists)?.readBytes()
        val settings = Settings(characterMode = "narrator")
        val book = app.books.save(Book(title = "Queue fixture", format = "txt", chapters = listOf(Chapter("One", listOf("Hello.")))))
        try {
            withContext(Dispatchers.Main) { app.playback.stopAndJoin(); app.settingsStore.save(settings); app.settings = settings }
            val speech = Speech("Hello.", "", "Charon")
            val name = "${AudioCache.key(speech, settings)}.wav"
            app.offline.audio(book.id, 0, name).writeBytes(Wav.encode(Pcm(ByteArray(48_000))))
            app.offline.save(book.id, 0, PreparedChapter(app.offline.signature(book, 0, settings), listOf(PreparedLine(Segment(0, 0, 6, "Hello."), speech, "Narrator", name)), true))
            val request = com.geminireader.playback.PreparationRequest(book.id, listOf(0), com.geminireader.playback.PreparationQueue.fingerprint(settings), false, false)
            suspend fun forceRun() {
                withContext(Dispatchers.Main) { com.geminireader.playback.PreparationQueue.schedule(app, request) }
                val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd jobscheduler run -f ${app.packageName} 204")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
            }
            forceRun()
            withTimeout(15_000) { while (com.geminireader.playback.PreparationQueue.read(app)?.chapters?.isEmpty() != true || app.preparing) delay(100) }
            assertNotNull(app.offline.ready(book, 0, settings))
            app.settingsStore.save(settings.copy(narratorVoice = "Kore"))
            forceRun()
            withTimeout(15_000) { while (!app.status.startsWith("Preparation paused:")) delay(100) }
            assertEquals(listOf(0), com.geminireader.playback.PreparationQueue.read(app)!!.chapters)
        } finally {
            withContext(Dispatchers.Main) { app.cancelPreparationAndJoin(); app.settingsStore.save(original); app.settings = original }
            app.books.delete(book.id)
            if (oldQueue == null) queueFile.delete() else queueFile.writeBytes(oldQueue)
        }
    }

}
