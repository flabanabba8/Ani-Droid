package com.geminireader.playback

import com.geminireader.tts.ApiFailure
import com.geminireader.tts.MissingAudio
import kotlinx.coroutines.*
import java.io.IOException

// Await inside supervisorScope so failed prefetch cannot cancel earlier audio.
internal suspend fun <T> retryPrefetched(first: Deferred<T>, onRetry: (Int) -> Unit, generate: suspend () -> T): T {
    for (attempt in 0..2) {
        currentCoroutineContext().ensureActive()
        try {
            return if (attempt == 0) first.await() else generate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val temporary = when (e) {
                is ApiFailure -> e.code == 429 || e.code in 500..599
                is MissingAudio -> !e.blocked
                else -> e is IOException
            }
            if (!temporary || attempt == 2) throw e
            onRetry(attempt + 1)
            delay(2000L shl attempt)
        }
    }
    error("Playback retries exhausted")
}
