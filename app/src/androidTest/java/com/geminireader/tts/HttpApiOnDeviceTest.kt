package com.geminireader.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Exercise every consolidated retry route through Android's actual HTTP stack. */
@RunWith(AndroidJUnit4::class)
class HttpApiOnDeviceTest {
    @Test fun allProviderRoutesRetryAndPreserveAuthentication() = runBlocking {
        val api = HttpApi()
        val requests = listOf<Pair<String, suspend (String, String, JsonObject) -> Any>>(
            "authorization: Bearer test-key" to api::speechAudio,
            "authorization: Bearer test-key" to api::groqChat,
            "xi-api-key: test-key" to api::elevenAudio,
            "authorization: Bearer test-key" to api::fishAudio,
            "authorization: Bearer test-key" to api::speechifyAudio,
            "authorization: Basic test-key" to api::inworldAudio,
            "authorization: Token test-key" to api::deepgramAudio,
            "authorization: Bearer test-key" to api::cartesiaAudio)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10000
            val worker = FutureTask {
                requests.forEachIndexed { index, (auth, _) ->
                    repeat(2) { attempt ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            assertEquals("POST /$index HTTP/1.1", reader.readLine())
                            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                            assertTrue(headers.any { it.equals(auth, ignoreCase = true) })
                            val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                            repeat(length) { check(reader.read() >= 0) }
                            val status = if (attempt == 0) "429 Too Many Requests" else "200 OK"
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 $status\r\nContent-Length: 2\r\nRetry-After: 0\r\nConnection: close\r\n\r\n{}".toByteArray())
                                flush()
                            }
                        }
                    }
                }
            }
            Thread(worker, "provider-retry-test").apply { isDaemon = true; start() }
            requests.forEachIndexed { index, (_, request) ->
                val response = request("http://127.0.0.1:${server.localPort}/$index", "test-key", obj())
                if (response is ByteArray) assertEquals("{}", response.decodeToString()) else assertEquals(obj(), response)
            }
            worker.get(15, TimeUnit.SECONDS)
            Unit
        }
    }
}
