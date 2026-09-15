package com.geminireader.analysis

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Playback waiters may be cancelled by seeking; the bounded chapter analysis keeps its progress. */
class SharedAnalysis<T>(private val scope: CoroutineScope, private val cooldownMs: Long = 30_000) {
    private data class Entry<T>(val task: Deferred<T>, var finishedAt: Long = Long.MAX_VALUE)
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry<T>>()
    suspend fun forgetCompleted(prefix: String) = mutex.withLock {
        entries.entries.removeAll { (key, entry) -> key.startsWith(prefix) && entry.task.isCompleted }
    }
    suspend fun cancelPrefix(prefix: String) {
        val tasks = mutex.withLock {
            val selected = entries.filterKeys { it.startsWith(prefix) }
            selected.keys.forEach { entries.remove(it) }
            selected.values.map { it.task }
        }
        tasks.forEach { it.cancel() }
        tasks.joinAll()
    }
    suspend fun get(key: String, work: suspend () -> T): T {
        val task = mutex.withLock {
            val now = System.nanoTime() / 1_000_000
            entries.entries.removeAll { (_, e) -> e.task.isCompleted && now - e.finishedAt >= cooldownMs }
            entries[key]?.task ?: scope.async(start = CoroutineStart.LAZY) {
                try { work() } finally {
                    withContext(NonCancellable) { mutex.withLock { entries[key]?.finishedAt = System.nanoTime() / 1_000_000 } }
                }
            }.also { entries[key] = Entry(it); it.start() }
        }
        return task.await()
    }
}
