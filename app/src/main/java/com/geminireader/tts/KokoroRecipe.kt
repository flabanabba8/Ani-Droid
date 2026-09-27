package com.geminireader.tts

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Rebuilds the exact pinned selective8 protobuf without shipping ONNX tooling. */
object KokoroRecipe {
    suspend fun apply(source: File, recipe: InputStream, target: File) {
        val context = currentCoroutineContext()
        RandomAccessFile(source, "r").use { input ->
            DataInputStream(GZIPInputStream(recipe)).use { instructions ->
                require(instructions.readInt() == 0x4b515231)
                target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        context.ensureActive()
                        when (val op = instructions.readUnsignedByte()) {
                            255 -> break
                            0, 2 -> {
                                if (op == 0) input.seek(instructions.readLong())
                                var left = instructions.readInt(); require(left >= 0)
                                while (left > 0) {
                                    context.ensureActive()
                                    val n = minOf(left, buffer.size)
                                    if (op == 0) input.readFully(buffer, 0, n) else instructions.readFully(buffer, 0, n)
                                    output.write(buffer, 0, n); left -= n
                                }
                            }
                            3 -> {
                                input.seek(instructions.readLong())
                                val rows = instructions.readInt(); val cols = instructions.readInt()
                                val bytes = ByteArray(Math.multiplyExact(Math.multiplyExact(rows, cols), 4))
                                input.readFully(bytes); val result = ByteArray(bytes.size)
                                for (r in 0 until rows) {
                                    context.ensureActive()
                                    for (c in 0 until cols) System.arraycopy(bytes, (c * rows + r) * 4, result, (r * cols + c) * 4, 4)
                                }
                                output.write(result)
                            }
                            1 -> {
                                input.seek(instructions.readLong())
                                val count = instructions.readInt()
                                val groups = instructions.readInt(); val rows = instructions.readInt(); val cols = instructions.readInt()
                                val transpose = instructions.readBoolean(); val signed = instructions.readBoolean()
                                val scales = instructions.readInt()
                                val scale = FloatArray(scales); val zero = IntArray(scales)
                                for (i in 0 until scales) { scale[i] = instructions.readFloat(); zero[i] = instructions.readInt() }
                                val bytes = ByteArray(Math.multiplyExact(count, 4)); input.readFully(bytes)
                                val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                val result = ByteArray(count)
                                for (i in 0 until count) {
                                    if (i % 16384 == 0) context.ensureActive()
                                    val channel = i / (count / scales)
                                    val q = (Math.rint((floats.get(i) / scale[channel]).toDouble()).toInt() + zero[channel])
                                        .coerceIn(if (signed) -128 else 0, if (signed) 127 else 255)
                                    val dest = if (transpose) {
                                        val perGroup = count / groups; val within = i % perGroup
                                        (i / perGroup) * perGroup + (within % rows) * cols + within / rows
                                    } else i
                                    result[dest] = q.toByte()
                                }
                                repeat(instructions.readInt()) { result[instructions.readInt()] = instructions.readByte() }
                                output.write(result)
                            }
                            else -> error("Unsupported model recipe")
                        }
                    }
                    output.fd.sync()
                }
            }
        }
    }
}
