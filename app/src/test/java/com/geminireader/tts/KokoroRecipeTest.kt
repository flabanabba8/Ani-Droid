package com.geminireader.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class KokoroRecipeTest {
    @Test fun reconstructsExactBenchmarkedModelFromUpstream() = runBlocking {
        val root = File(System.getProperty("pagecast.root"))
        val source = File(root, ".cache/kokoro-android/model.fp32.onnx")
        val recipe = File(root, "app/src/main/assets/kokoro-recipe.bin")
        val target = File.createTempFile("kokoro-conversion", ".onnx")
        try {
            org.junit.Assume.assumeTrue("Optional full-model check: run tools/prepare-kokoro-android.py first", source.isFile)
            recipe.inputStream().use { KokoroRecipe.apply(source,it,target) }
            val md=MessageDigest.getInstance("SHA-256")
            target.inputStream().use { stream -> val b=ByteArray(65536); while(true) { val n=stream.read(b); if(n<0) break;md.update(b,0,n) } }
            assertEquals("84514ba144a99ac1e00d2ab60ebf9207ba7794b3b86c96ecb2ed9fb983f54f51",md.digest().joinToString("") { "%02x".format(it) })
        } finally { target.delete() }
    }
}
