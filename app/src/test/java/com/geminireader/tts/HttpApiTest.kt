package com.geminireader.tts

import com.geminireader.data.Settings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class HttpApiTest {
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
