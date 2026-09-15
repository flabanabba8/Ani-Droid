package com.geminireader.tts

import com.geminireader.data.*
import com.geminireader.text.Segment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64

class PerformanceFeaturesTest {
    @Test fun pronunciationIsBoundedAndDoesNotCascade() {
        val rules = listOf(Pronunciation(written = "Alice", spoken = "Al-iss"), Pronunciation(written = "Al-iss", spoken = "wrong"), Pronunciation(written = "Alice Smith", spoken = "Full name"))
        assertEquals("Al-iss’s palace, malice, Full name and Al-iss.", PronunciationRules.apply("Alice’s palace, malice, Alice Smith and ALICE.", rules))
        assertEquals("Roo’s", PronunciationRules.apply("竜’s", listOf(Pronunciation(written = "竜", spoken = "Roo"))))
    }
    @Test fun specificScopeWinsWithoutChangingSource() {
        val source = "Alice went home."
        val rules = listOf(Pronunciation(written="Alice", spoken="global", scope="global"), Pronunciation(written="Alice", spoken="series", scope="series"), Pronunciation(written="Alice", spoken="book", scope="book"))
        assertEquals("book went home.", PronunciationRules.apply(source, rules))
        assertEquals("Alice went home.", source)
    }
    @Test fun seriesAndDictionaryDoNotLeakAcrossUnlinkedBooks() {
        val folder = Files.createTempDirectory("reader-performances").toFile()
        try {
            val repo = PerformanceRepository(folder)
            val profile = VoiceProfile(label = "Known speaker", voice = "Charon", style = "Measured")
            repo.saveSeries(listOf(Series(id="series-one", name="One", profiles=listOf(profile))))
            repo.saveLink("book-one", SeriesLink("series-one", mapOf("speaker" to profile.id)))
            repo.save(listOf(Pronunciation(written="Name", spoken="Naym", scope="series", owner="series-one")))
            assertEquals(1, repo.applicable("book-one").size)
            assertTrue(repo.applicable("book-two").isEmpty())
            assertTrue(repo.link("book-two").voices.isEmpty())
            assertFalse(json.encodeToString(profile).contains("aliases"))
            assertFalse(json.encodeToString(profile).contains("description"))
        } finally { folder.deleteRecursively() }
    }
    @Test fun offlineManifestRequiresCompleteMatchingSettingsAndValidFiles() {
        val folder = Files.createTempDirectory("reader-offline").toFile()
        try {
            val books = BookRepository(folder.resolve("books")); val performances = PerformanceRepository(folder)
            val offline = OfflineChapters(books, performances)
            val book = books.save(Book(id="test", title="Test", format="txt", chapters=listOf(Chapter("One", listOf("Hello")))))
            val settings = Settings(); val speech = Speech("Hello", "Warm", "Charon")
            val line = PreparedLine(Segment(0,0,5,"Hello"), speech,"Narrator", "${AudioCache.key(speech, settings)}.wav")
            val plan = PreparedChapter(offline.signature(book,0,settings),listOf(line))
            offline.audio(book.id,0,line.file).writeBytes(Wav.encode(Pcm(ByteArray(100))))
            offline.save(book.id,0,plan); assertNull(offline.ready(book,0,settings))
            offline.save(book.id,0,plan.copy(complete=true)); assertNotNull(offline.ready(book,0,settings))
            assertNotNull(offline.ready(book,0,settings.copy(vertexToken="renewed", theme="light", bufferSeconds=60)))
            performances.save(listOf(Pronunciation(written="Hello",spoken="Hey",owner=book.id)))
            assertNull(offline.ready(book,0,settings))
        } finally { folder.deleteRecursively() }
    }
    @Test fun missingAudioReportsBlockInsteadOfRetryingIt() {
        val exception = ApiAudio.missing(obj("promptFeedback" to obj("blockReason" to str("SAFETY"))))
        assertTrue(exception.blocked); assertEquals("SAFETY", exception.reason)
        val normal = ApiAudio.missing(obj("candidates" to arr(obj("finishReason" to str("STOP")))))
        assertFalse(normal.blocked)
    }
    @Test fun vertexRecoversFromTextOnlyWithoutChangingTranscript() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        server.createContext("/") { exchange ->
            requests += exchange.requestBody.bufferedReader().readText()
            val response = if (requests.size < 3) obj("candidates" to arr(obj("finishReason" to str("STOP"), "content" to obj("parts" to arr(obj("text" to str("text output")))))))
                else obj("output_audio" to obj("data" to str(Base64.getEncoder().encodeToString(byteArrayOf(1,2)))))
            val bytes = response.toString().toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val pcm = VertexTtsClient(HttpApi()).synthesize(Speech("A test passage", "Calm", "Charon"), Settings(vertexUrl="http://127.0.0.1:${server.address.port}", vertexProject="test", vertexToken="test"))
            assertEquals(2,pcm.bytes.size); assertEquals(3,requests.size); assertEquals(1,requests.toSet().size)
        } finally { server.stop(0) }
    }
}
