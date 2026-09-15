package com.geminireader.analysis

import com.geminireader.data.*
import com.geminireader.text.Segmenter
import com.geminireader.tts.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class AnalysisBatchTest {
    @Test fun boundedBatchesKeepEveryQuoteInOrder() {
        val paragraphs = List(100) { "Alice said “${"Hello there. ".repeat(15)}” Then she left." }
        val segments = Segmenter.dialogue(paragraphs)
        val chunks = CharacterAnalyzer.tagged(paragraphs, segments)
        val ids = chunks.flatMap { Regex("<q id=\"([^\"]+)\">").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(segments.mapNotNull { it.q }, ids)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 6000 })
        assertTrue(chunks.all { Regex("<q id=").findAll(it).count() <= 24 })
    }
    @Test fun oversizedParagraphSplitsWithoutReordering() {
        val paragraphs = listOf("Opening.", List(60) { "“Quote $it.” Alice said. " }.joinToString(""), "Ending.")
        val chunks = CharacterAnalyzer.tagged(paragraphs, Segmenter.dialogue(paragraphs))
        assertTrue(chunks.first().startsWith("<p id=\"0\">Opening."))
        assertTrue(chunks.last().contains("Ending."))
        val ids = chunks.flatMap { Regex("<q id=\"([^\"]+)\">").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(List(60) { "1.$it" }, ids)
        assertTrue(chunks.all { Regex("<q id=").findAll(it).count() <= 24 })
    }
    @Test fun canceledWaiterDoesNotRestartSharedWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val shared = SharedAnalysis<Int>(scope)
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val first = launch { shared.get("chapter") { calls.incrementAndGet(); started.complete(Unit); release.await(); 42 } }
            started.await(); first.cancelAndJoin()
            val second = async { shared.get("chapter") { calls.incrementAndGet(); 99 } }
            release.complete(Unit)
            assertEquals(42, second.await()); assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }
    @Test fun failuresDoNotHammerServiceOnEveryParagraphTap() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val shared = SharedAnalysis<Int>(scope)
            var calls = 0
            repeat(2) { try { shared.get("chapter") { calls++; error("offline") } } catch (_: IllegalStateException) {} }
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }
    @Test fun partialAnalysisResumesAfterFailureAndProcessRestart() = runBlocking {
        val root = Files.createTempDirectory("reader-batches-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val count = AtomicInteger(); val requested = mutableListOf<List<String>>()
        server.createContext("/") { e ->
            val body = json.parseToJsonElement(e.requestBody.bufferedReader().readText()).jsonObject
            val prompt = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            val ids = Regex("<q id=\"([0-9.]+)\">").findAll(prompt).map { it.groupValues[1] }.distinct().toList()
            requested += ids
            val status = if (count.incrementAndGet() == 2) 401 else 200
            val analysis = json.encodeToString(Analysis(lines = ids.map { Attribution(it) }))
            val bytes = obj("candidates" to arr(obj("content" to obj("parts" to arr(obj("text" to str(analysis))))))).toString().toByteArray()
            e.sendResponseHeaders(status, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = BookRepository(root)
            val book = repo.save(Book(title = "Test", format = "txt", chapters = listOf(Chapter("One", List(60) { "“Hello.” Alice said." }))))
            val s = Settings(vertexProject = "mock", vertexToken = "mock", vertexUrl = "http://127.0.0.1:${server.address.port}")
            try { CharacterAnalyzer(repo, HttpApi(), scope).analyze(book, 0, s); fail("Expected failure") } catch (e: ApiFailure) { assertEquals(401, e.code) }
            assertEquals(1, repo.directory(book.id).resolve("analysis").listFiles()!!.size)
            val result = CharacterAnalyzer(repo, HttpApi(), scope).analyze(book, 0, s)
            assertEquals(60, result.lines.size)
            assertEquals(4, count.get()) // first batch once, second failed + retried, third once
            assertEquals(1, requested.count { it == requested.first() })
            CharacterAnalyzer(repo, HttpApi(), scope).analyze(book, 0, s)
            assertEquals(4, count.get())
        } finally { scope.cancel(); server.stop(0); root.deleteRecursively() }
    }
    @Test fun fastThinkingConfigAndDarkDefault() {
        assertEquals("dark", Settings().theme)
        assertEquals(0, CharacterAnalyzer.generationConfig("gemini-2.5-flash")["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.int)
        assertFalse(CharacterAnalyzer.generationConfig("gemini-2.5-pro").containsKey("thinkingConfig"))
        val choices = AnalysisModels.choices("custom-current", listOf("gemini-new-flash", "gemini-3.1-flash-tts-preview"))
        assertTrue("custom-current" in choices && "gemini-new-flash" in choices)
        assertFalse("gemini-3.1-flash-tts-preview" in choices)
    }
}
