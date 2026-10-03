package com.geminireader.tts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.*

object AudiobookExport {
    data class ChapterAudio(val title: String, val files: List<File>)
    fun write(chapters: List<ChapterAudio>, output: File, title: String, author: String, cover: ByteArray?, checkpoint: () -> Unit) {
        require(chapters.isNotEmpty() && chapters.size <= 255) { "Export supports 1–255 chapters at a time" }
        val files = chapters.flatMap { it.files }
        require(files.isNotEmpty()) { "Prepare audio before exporting" }
        val info = files.map(WavExport::inspect)
        val rate = info.first().rate
        require(info.all { it.rate == rate }) { "Prepare all chapters with the same speech sample rate" }
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        var muxer: MediaMuxer? = null
        var started = false
        var codecStarted = false
        var input: RandomAccessFile? = null
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start(); codecStarted = true
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var track = -1; var fileIndex = 0; var remaining = 0L; var samples = 0L; var inputEnded = false; var outputEnded = false
            val transfer = ByteArray(16_384)
            var lastProgress = System.nanoTime()
            while (!outputEnded) {
                checkpoint()
                require(System.nanoTime() - lastProgress < 60_000_000_000L) { "Audio encoder stalled" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)); buffer.clear()
                        while (remaining == 0L && fileIndex < files.size) {
                            input?.close(); input = RandomAccessFile(files[fileIndex], "r")
                            val meta = info[fileIndex++]; input.seek(meta.offset); remaining = meta.bytes
                        }
                        val count = minOf(remaining, buffer.remaining().toLong(), transfer.size.toLong()).toInt() and -2
                        val timestamp = samples * 1_000_000 / rate
                        if (count > 0) {
                            input!!.readFully(transfer, 0, count); buffer.put(transfer, 0, count); remaining -= count; samples += count / 2
                            codec.queueInputBuffer(index, 0, count, timestamp, 0)
                        } else { codec.queueInputBuffer(index, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        lastProgress = System.nanoTime()
                    }
                }
                val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!started); track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                } else if (index >= 0) {
                    val buffer = requireNotNull(codec.getOutputBuffer(index))
                    if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started); buffer.position(bufferInfo.offset); buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(track, buffer, bufferInfo)
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false); lastProgress = System.nanoTime()
                }
            }
            muxer.stop(); started = false
        } catch (e: Exception) { output.delete(); throw e }
        finally { input?.close(); if (codecStarted) runCatching { codec.stop() }; codec.release(); if (started) runCatching { muxer?.stop() }; muxer?.release() }
        try {
            var time = 0L
            val markers = chapters.map { ch -> (time to ch.title).also { time += ch.files.sumOf { WavExport.inspect(it).durationMs } } }
            addMetadata(output, title, author, cover, markers)
        } catch (e: Exception) { output.delete(); throw e }
    }
    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
    private fun atom(name: String, payload: ByteArray): ByteArray = bytes { writeInt(payload.size + 8); write(name.toByteArray(Charsets.ISO_8859_1)); write(payload) }
    private fun field(name: String, value: ByteArray, type: Int = 1) = atom(name, atom("data", bytes { writeInt(type); writeInt(0); write(value) }))
    private fun addMetadata(file: File, title: String, author: String, cover: ByteArray?, chapters: List<Pair<Long, String>>) {
        val tags = field("©nam", title.toByteArray()) + field("©ART", author.toByteArray()) +
            (cover?.takeIf { it.size <= 5 * 1024 * 1024 }?.let { field("covr", it, if (it.firstOrNull() == 0x89.toByte()) 14 else 13) } ?: byteArrayOf())
        val handler = atom("hdlr", bytes { writeInt(0); writeInt(0); writeBytes("mdir"); write(ByteArray(12)); writeByte(0) })
        val meta = atom("meta", ByteArray(4) + handler + atom("ilst", tags))
        val markers = atom("chpl", bytes {
            writeInt(0x01000000); writeInt(0); writeByte(chapters.size)
            chapters.forEach { (ms, title) ->
                var label = title
                while (label.toByteArray().size > 255) label = label.dropLast(if (label.last().isLowSurrogate()) 2 else 1)
                val data = label.toByteArray(); writeLong(ms * 10_000); writeByte(data.size); write(data)
            }
        })
        // Replace the original moov with same-sized free space and append the enlarged moov.
        // Media data offsets remain unchanged, regardless of the original atom ordering.
        RandomAccessFile(file, "rw").use { io ->
            var position = 0L
            while (position + 8 <= io.length()) {
                io.seek(position); var size = io.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4); io.readFully(type)
                var header = 8
                if (size == 1L) { size = io.readLong(); header = 16 }
                if (size == 0L) size = io.length() - position
                require(size >= header && size <= io.length() - position) { "Invalid MP4 atom" }
                if (String(type) == "moov") {
                    require(size <= 32 * 1024 * 1024) { "MP4 metadata too large" }
                    val payload = ByteArray((size - header).toInt()); io.readFully(payload)
                    io.seek(position + 4); io.writeBytes("free")
                    io.seek(io.length()); io.write(atom("moov", payload + atom("udta", meta + markers))); return
                }
                position += size
            }
            error("Audio export contains no MP4 movie metadata")
        }
    }
}
