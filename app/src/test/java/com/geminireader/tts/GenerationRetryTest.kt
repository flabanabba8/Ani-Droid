package com.geminireader.tts

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class GenerationRetryTest {
    @Test fun retriesTextThenEmptyAudioUntilGenerationSucceeds() = runBlocking {
        var attempts = 0
        val result = retryGeneration {
            when (++attempts) {
                1 -> ApiAudio.gemini(obj())
                2 -> ApiAudio.gemini(obj("output_audio" to obj("data" to str(""))))
                else -> ApiAudio.gemini(obj("output_audio" to obj("data" to str("AQI="))))
            }
        }
        assertEquals(3, attempts)
        assertArrayEquals(byteArrayOf(1, 2), result.bytes)
    }

    @Test fun persistentGenerationFailureStopsAtThreeAttempts() = runBlocking {
        var attempts = 0
        try {
            retryGeneration { attempts++; ApiAudio.cloud(obj()) }
            fail("Expected exhausted generation failure")
        } catch (e: MissingAudio) { assertFalse(e.blocked) }
        assertEquals(3, attempts)
    }

    @Test fun explicitBlockIsNotRetriedEvenWithAudioAttached() = runBlocking {
        var attempts = 0
        try {
            retryGeneration {
                attempts++
                ApiAudio.gemini(obj(
                    "promptFeedback" to obj("blockReason" to str("PROHIBITED_CONTENT")),
                    "output_audio" to obj("data" to str("AQI="))
                ))
            }
            fail("Expected block")
        } catch (e: MissingAudio) { assertTrue(e.blocked) }
        assertEquals(1, attempts)
    }

    @Test fun cancellationDuringBackoffStopsRequests() = runBlocking {
        var attempts = 0
        val firstAttempt = CompletableDeferred<Unit>()
        val job = launch {
            retryGeneration {
                attempts++
                firstAttempt.complete(Unit)
                throw MissingAudio(false, "empty audio")
            }
        }
        firstAttempt.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }

    @Test fun emptyWavIsGenerationFailureForBothFormats() {
        val encoded = Base64.getEncoder().encodeToString(Wav.encode(Pcm(byteArrayOf())))
        for (parse in listOf<() -> Pcm>(
            { ApiAudio.cloud(obj("audioContent" to str(encoded))) },
            { ApiAudio.gemini(obj("output_audio" to obj("data" to str(encoded)))) }
        )) {
            try { parse(); fail("Expected empty audio failure") }
            catch (e: MissingAudio) { assertFalse(e.blocked) }
        }
    }

    @Test fun programmingErrorsAreNotRetried() = runBlocking {
        var attempts = 0
        try {
            retryGeneration { attempts++; error("invalid configuration") }
            fail("Expected configuration failure")
        } catch (e: IllegalStateException) { assertEquals("invalid configuration", e.message) }
        assertEquals(1, attempts)
    }
}
