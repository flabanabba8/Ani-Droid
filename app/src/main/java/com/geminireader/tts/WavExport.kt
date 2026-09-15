package com.geminireader.tts

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavExport {
    data class Info(val offset: Long, val bytes: Long, val rate: Int) {
        val durationMs get() = bytes * 1000 / (rate * 2L)
    }
    fun inspect(file: File): Info = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 44) { "Truncated WAV" }
        val head = ByteArray(12); input.readFully(head)
        require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "Not a WAV file" }
        var rate = 0; var offset = -1L; var bytes = 0L
        while (input.filePointer + 8 <= input.length()) {
            val chunk = ByteArray(8); input.readFully(chunk)
            val tag = String(chunk, 0, 4)
            val size = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() and 0xffffffffL
            val start = input.filePointer
            require(start + size <= input.length()) { "Truncated WAV chunk" }
            if (tag == "fmt ") {
                require(size >= 16)
                val fmt = ByteArray(16); input.readFully(fmt)
                val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                require(b.getShort(0).toInt() == 1 && b.getShort(2).toInt() == 1 && b.getShort(14).toInt() == 16) { "Export requires mono 16-bit PCM" }
                rate = b.getInt(4)
            } else if (tag == "data") { offset = start; bytes = size }
            input.seek(start + size + size % 2)
        }
        require(rate in 8000..192000 && offset >= 0 && bytes % 2 == 0L) { "Invalid WAV data" }
        Info(offset, bytes, rate)
    }
    fun write(files: List<File>, output: OutputStream, checkpoint: () -> Unit = {}) {
        require(files.isNotEmpty()) { "No generated audio to export" }
        val info = files.map(::inspect)
        val rate = info.first().rate
        require(info.all { it.rate == rate }) { "Cannot mix sample rates in one export" }
        val total = info.sumOf { it.bytes }
        require(total <= 0xffffffffL - 36) { "WAV export exceeds the 4 GB format limit" }
        val header = Wav.encode(Pcm(byteArrayOf(), rate))
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(4, (total + 36).toInt()); putInt(40, total.toInt()) }
        output.write(header)
        val buffer = ByteArray(64 * 1024)
        files.zip(info).forEach { (file, meta) ->
            RandomAccessFile(file, "r").use { input ->
                input.seek(meta.offset); var remaining = meta.bytes
                while (remaining > 0) {
                    checkpoint()
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, count); output.write(buffer, 0, count); remaining -= count
                }
            }
        }
    }
}
