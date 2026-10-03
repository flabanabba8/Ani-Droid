package com.geminireader

import com.geminireader.analysis.*
import com.geminireader.analysis.Character
import com.geminireader.data.*
import com.geminireader.importer.*
import com.geminireader.playback.PreparationQueue
import com.geminireader.text.*
import com.geminireader.tts.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReadingFeaturesTest {
    private val line = Segment(0, 0, 6, "Hello.", "0.0")
    @Test fun distinctIsDefaultAndNarrationKeepsNarrator() {
        val s = Settings()
        assertEquals("distinct", s.characterMode)
        val person = Character("alice", "Alice", gender = "female", suggestedVoice = "Aoede")
        assertEquals("Aoede", VoiceDirector.direct(line, s, person, "curious", true).voice)
        assertEquals("Charon", VoiceDirector.direct(line, s, null, "", true).voice)
        assertEquals("performance", json.decodeFromString<Settings>("""{"characterMode":"performance"}""").characterMode)
    }
    @Test fun lockOverridesPerformanceButNotNarratorAndChangesAudioKey() {
        val s = Settings(characterMode = "performance")
        val original = Character("alice", "Alice", gender = "female", voice = "Aoede")
        val locked = original.copy(performanceLocked = true, delivery = "steady", intensity = .2f)
        val before = VoiceDirector.direct(line, s, original, "excited", true)
        val after = VoiceDirector.direct(line, s, locked, "excited", true)
        assertEquals("Charon", before.voice); assertEquals("Aoede", after.voice)
        assertFalse(after.prompt.contains("Keep the narrator's identity"))
        assertTrue(after.prompt.contains("Delivery: steady")); assertTrue(after.prompt.contains("20/100"))
        assertNotEquals(AudioCache.key(before, s), AudioCache.key(after, s))
        assertEquals("Charon", VoiceDirector.direct(line, s.copy(characterMode = "narrator"), locked, "", true).voice)
    }
    @Test fun modelChoicesAreProviderSpecificAndPreserveSavedIds() {
        assertTrue(TtsModels.choices("vertex", "custom-tts", listOf("text-model", "future-tts")).containsAll(listOf("custom-tts", "future-tts", "gemini-2.5-pro-tts")))
        assertFalse("text-model" in TtsModels.choices("vertex", "custom-tts", listOf("text-model")))
        assertTrue("gemini-2.5-pro-preview-tts" in TtsModels.choices("gemini", "", emptyList()))
        assertFalse("gemini-2.5-pro-tts" in TtsModels.choices("gemini", "", emptyList()))
    }
    @Test fun spoilerReferenceExcludesCurrentParagraphAndFutureChapter() {
        val book = Book(title = "Reference", format = "txt", chapters = listOf(
            Chapter("One", listOf("Alice is a gardener.", "Alice is a queen.")), Chapter("Two", listOf("Alice vanishes."))))
        assertEquals(listOf("Alice is a gardener."), ReadReference.find(book, 0, 1, "Alice").map { it.text })
        assertTrue(ReadReference.find(book, 0, 0, "Alice").isEmpty())
        assertTrue(ReadReference.find(book, 1, 0, "ali").isEmpty())
    }
    @Test fun cleanupRequiresExplicitOptionsAndLeavesSourceUntouched() {
        val source = "  A long-\nlasting story[12].  "
        assertEquals(source, ImportCleanup.paragraph(source, CleanupOptions()))
        assertEquals("A longlasting story.", ImportCleanup.paragraph(source, CleanupOptions(footnoteMarkers = true, wrappedLines = true)))
        assertEquals("", ImportCleanup.paragraph("Page 12", CleanupOptions(pageNumbers = true)))
        assertEquals("12 doors", ImportCleanup.paragraph("12 doors", CleanupOptions(pageNumbers = true)))
    }
    @Test fun queueFingerprintIgnoresRenewedCredentialsButDetectsVoiceChange() {
        val s = Settings()
        assertEquals(PreparationQueue.fingerprint(s), PreparationQueue.fingerprint(s.copy(vertexToken = "synthetic-renewed-token", vertexBrokerSecret = "synthetic-secret")))
        assertNotEquals(PreparationQueue.fingerprint(s), PreparationQueue.fingerprint(s.copy(narratorVoice = "Kore")))
    }
    @Test fun assignedSpeakerVoiceChangeInvalidatesPreparedSignature() {
        val root = Files.createTempDirectory("pagecast-signature").toFile()
        try {
            val books = BookRepository(root.resolve("books")); val offline = OfflineChapters(books, PerformanceRepository(root))
            val book = books.save(Book(title = "Example", format = "txt", chapters = listOf(Chapter("One", listOf("Hello.")))))
            atomicWrite(books.directory(book.id).resolve("analysis/0.json"), json.encodeToString(Analysis(lines = listOf(Attribution("0.0", "unknown")))))
            atomicWrite(books.directory(book.id).resolve("analysis/0-overrides.json"), json.encodeToString(mapOf("0.0" to "alice")))
            val cast = books.directory(book.id).resolve("cast.json")
            atomicWrite(cast, json.encodeToString(listOf(Character("alice", "Alice", voice = "Aoede"))))
            val before = offline.signature(book, 0, Settings())
            atomicWrite(cast, json.encodeToString(listOf(Character("alice", "Alice", voice = "Kore"))))
            assertNotEquals(before, offline.signature(book, 0, Settings()))
        } finally { root.deleteRecursively() }
    }
    @Test fun savedQuoteSurvivesEditAndExportsLocation() {
        val root = Files.createTempDirectory("pagecast-notes").toFile()
        try {
            val books = BookRepository(root); val notes = ReadingNotes(books)
            val book = books.save(Book(title = "Example", format = "txt", chapters = listOf(Chapter("One", listOf("Hello.")))))
            val note = ReadingNote(chapter = 0, paragraph = 0, start = 0, end = 6, quote = "Hello.", note = "Remember this")
            notes.save(book.id, note); assertTrue(notes.unchanged(book, note))
            val edited = books.editPassage(book.id, 0, 0, "Hello.", "Welcome.")
            assertFalse(notes.unchanged(edited, note)); assertEquals(note, notes.list(book.id).single())
            assertTrue(notes.export(book, listOf(note)).contains("Chapter 1: One"))
            notes.delete(book.id, note.id); assertTrue(notes.list(book.id).isEmpty())
        } finally { root.deleteRecursively() }
    }
}
