package com.geminireader.tts

import androidx.annotation.Keep
import java.io.File

/** Only invoked after the installer verifies the pinned native library hashes. */
@Keep
object KokoroNativeLoader {
    private var loaded = false
    @Synchronized fun initialize(folder: File) {
        if (loaded) return
        System.load(File(folder, "libonnxruntime.so").absolutePath)
        System.load(File(folder, "libsherpa-onnx-jni.so").absolutePath)
        loaded = true
    }
    @JvmStatic @Synchronized fun loadLibrary(name: String) {
        check(name == "sherpa-onnx-jni" && loaded) { "Download and verify Kokoro before loading its runtime" }
    }
}
