package com.geminireader.playback

object BufferPolicy {
    fun remainingMs(durations: List<Long>, index: Int, positionMs: Long, speed: Float): Long {
        if (index !in durations.indices) return 0
        return ((durations.subList(index, durations.size).sum() - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(.1f)).toLong()
    }
    fun label(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "${seconds / 60}m ${seconds % 60}s ready"
    }
    fun resumeOffset(savedKey: String, fileKey: String, savedOffset: Long, durationMs: Long): Long =
        if (savedKey.isNotBlank() && savedKey == fileKey) savedOffset.coerceIn(0, (durationMs - 1).coerceAtLeast(0)) else 0
}
