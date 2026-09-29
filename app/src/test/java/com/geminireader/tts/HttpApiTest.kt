package com.geminireader.tts

import com.geminireader.data.Settings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class HttpApiTest {
    @Test fun speechProvidersKeepBoundedRetriesAndRejectRedirectsAndPermanentErrors() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val attempts = AtomicInteger()
        var statuses = listOf(200)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            val status = statuses[minOf(attempts.getAndIncrement(), statuses.lastIndex)]
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.responseHeaders.add("Location", "/redirected")
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = HttpApi()
            val requests = listOf<Pair<String, suspend (String, String, kotlinx.serialization.json.JsonObject) -> Any>>(
                "Groq speech" to api::speechAudio, "Groq chat" to api::groqChat,
                "ElevenLabs" to api::elevenAudio, "Fish" to api::fishAudio,
                "Speechify" to api::speechifyAudio, "Inworld" to api::inworldAudio,
                "Deepgram" to api::deepgramAudio, "Cartesia" to api::cartesiaAudio)
            val url = "http://127.0.0.1:${server.address.port}/"
            for ((provider, request) in requests) {
                statuses = listOf(429, 503, 200); attempts.set(0)
                request(url, "test-key", obj())
                assertEquals(provider, 3, attempts.get())
                for (status in listOf(302, 400, 401, 402, 403, 404, 422, 429, 500, 503)) {
                    statuses = listOf(status); attempts.set(0)
                    try { request(url, "test-key", obj()); fail("$provider accepted HTTP $status") }
                    catch (e: ApiFailure) { assertEquals(provider, status, e.code) }
                    // OkHttp itself retries 503 + Retry-After: 0 once within each call.
                    val expected = when (status) { 503 -> 8; 429, 500 -> 4; else -> 1 }
                    assertEquals("$provider HTTP $status", expected, attempts.get())
                }
            }
        } finally { server.stop(0) }
    }

    @Test fun acceptedMalformedJsonIsNotRetriedAndHttpBackoffIsCancellable() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val attempts = AtomicInteger()
        val waiting = CompletableDeferred<Unit>()
        server.createContext("/malformed") { exchange ->
            attempts.incrementAndGet()
            val bytes = "invalid json".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/busy") { exchange ->
            attempts.incrementAndGet()
            exchange.responseHeaders.add("Retry-After", "30")
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            waiting.complete(Unit)
        }
        server.start()
        try {
            val api = HttpApi()
            val base = "http://127.0.0.1:${server.address.port}"
            for (request in listOf(api::groqChat, api::speechifyAudio, api::inworldAudio)) {
                attempts.set(0)
                try { request("$base/malformed", "test-key", obj()); fail("Accepted malformed JSON") }
                catch (_: kotlinx.serialization.SerializationException) { }
                assertEquals(1, attempts.get())
            }
            attempts.set(0)
            val job = launch { api.deepgramAudio("$base/busy", "test-key", obj()) }
            withTimeout(5000) { waiting.await(); job.cancelAndJoin() }
            assertEquals(1, attempts.get())
        } finally { server.stop(0) }
    }

    @Test fun responseSlowerThanOldDefaultReadTimeoutSucceeds() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            Thread.sleep(11_000)
            val bytes = "{}".toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            assertEquals(10_000, okhttp3.OkHttpClient().readTimeoutMillis)
            val api = HttpApi()
            assertEquals(120_000L, api.readTimeoutMs)
            assertEquals(obj(), api.request("http://127.0.0.1:${server.address.port}/", ""))
        } finally { server.stop(0) }
    }
    @Test fun retriesRateLimitButNotAuthenticationFailure() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val attempts = AtomicInteger()
        server.createContext("/retry") { exchange ->
            val status = if (attempts.incrementAndGet() == 1) 429 else 200
            val bytes = "{}".toByteArray(); exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/denied") { exchange ->
            attempts.incrementAndGet(); val bytes = "{}".toByteArray(); exchange.sendResponseHeaders(401, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = HttpApi(); val base = "http://127.0.0.1:${server.address.port}"
            api.request("$base/retry", "mock"); assertEquals(2, attempts.get())
            attempts.set(0)
            try { api.request("$base/denied", "mock"); fail("Expected authentication error") } catch (e: ApiFailure) { assertEquals(401, e.code) }
            assertEquals(1, attempts.get())
        } finally { server.stop(0) }
    }
    @Test fun vertexSendsBearerAndBillingProjectForTextAnalysis() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var auth = ""; var project = ""; var path = ""
        server.createContext("/") { exchange ->
            auth = exchange.requestHeaders.getFirst("Authorization"); project = exchange.requestHeaders.getFirst("x-goog-user-project"); path = exchange.requestURI.path
            val bytes = "{}".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            VertexEndpoint.request(HttpApi(), Settings(vertexUrl = "http://127.0.0.1:${server.address.port}", vertexProject = "credits-project", vertexToken = "test-token"), "gemini-2.5-flash", obj())
            assertEquals("Bearer test-token", auth); assertEquals("credits-project", project); assertTrue(path.contains("projects/credits-project/locations/us-central1"))
        } finally { server.stop(0) }
    }
}
