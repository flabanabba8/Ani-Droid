package com.geminireader.playback

import com.geminireader.tts.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackRetryTest {
    @Test fun failedPrefetchDoesNotCancelEarlierAudioAndRetriesInOrder() = runBlocking {
        supervisorScope {
            val earlier = async { yield(); "first" }
            val later = async<String> { throw IOException("connection lost") }
            later.join()
            assertEquals("first", earlier.await())
            val retries = mutableListOf<Int>()
            assertEquals("second", retryPrefetched(later, { retries += it }) { "second" })
            assertEquals(listOf(1), retries)
        }
    }

    @Test fun retriesAreBounded() = runBlocking {
        var retries = 0
        val failure = MissingAudio(false, "empty audio")
        val first = CompletableDeferred<String>().apply { completeExceptionally(failure) }
        try {
            retryPrefetched(first, {}) { retries++; throw failure }
            fail("Expected exhausted retry")
        } catch (e: MissingAudio) { assertSame(failure, e) }
        assertEquals(2, retries)
    }

    @Test fun permanentFailuresDoNotRetry() = runBlocking {
        for (failure in listOf(MissingAudio(true, "SAFETY"), ApiFailure(401, "auth"), ApiFailure(400, "invalid"), IllegalArgumentException("bad settings"))) {
            val first = CompletableDeferred<String>().apply { completeExceptionally(failure) }
            try {
                retryPrefetched(first, { fail("Unexpected retry") }) { error("Unexpected generation") }
                fail("Expected failure")
            } catch (e: Exception) {
                assertEquals(failure.javaClass, e.javaClass)
                assertEquals(failure.message, e.message)
            }
        }
    }

    @Test fun stoppingDuringRetryDelayCancelsGeneration() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        var generations = 0
        val first = CompletableDeferred<String>().apply { completeExceptionally(ApiFailure(503, "unavailable")) }
        val job = launch {
            retryPrefetched(first, { retryStarted.complete(Unit) }) { generations++; "audio" }
        }
        retryStarted.await()
        job.cancelAndJoin()
        assertEquals(0, generations)
    }
}
