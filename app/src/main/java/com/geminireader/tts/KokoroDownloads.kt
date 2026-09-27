package com.geminireader.tts

import android.content.Context
import android.os.Build
import android.os.Process
import com.geminireader.data.json
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

@Serializable data class KokoroRange(val path: String, val offset: Long, val size: Long, val sha256: String, val method: Int)
@Serializable data class KokoroRuntimeDownload(val manifest: KokoroManifest, val ranges: List<KokoroRange>)
@Serializable data class KokoroModelDownload(val url: String, val size: Long, val sha256: String, val sourceSize: Long, val sourceSha256: String, val manifest: KokoroManifest)
@Serializable data class KokoroCatalog(val model: KokoroModelDownload, val runtimeUrl: String, val runtimes: Map<String, KokoroRuntimeDownload>)

class KokoroDownloads(private val context: Context) {
    val catalog: KokoroCatalog by lazy { context.assets.open("kokoro-downloads.json").bufferedReader().use { json.decodeFromString(it.readText()) } }
    val abi: String get() = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).firstOrNull { it in catalog.runtimes }.orEmpty()
    private val root get() = context.noBackupFilesDir
    private val modelRoot get() = File(root, "kokoro")
    private val runtimeRoot get() = File(root, "kokoro-runtime")
    val modelFolder get() = File(modelRoot, catalog.model.manifest.version)
    val runtimeFolder get() = File(runtimeRoot, catalog.runtimes.getValue(abi).manifest.version)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            require(chain.request().url.isHttps) { "Kokoro downloads require HTTPS" }; chain.proceed(chain.request())
        }.build()
    private fun complete(folder: File, manifest: KokoroManifest) = File(folder,".ready").isFile &&
        File(folder,".ready").readText() == json.encodeToString(manifest) && manifest.files.all { File(folder,it.path).length() == it.size }
    fun modelReady() = complete(modelFolder, catalog.model.manifest)
    fun installed() = abi.isNotBlank() && modelReady() && complete(runtimeFolder,catalog.runtimes.getValue(abi).manifest)
    fun downloadBytes(): Long = if (abi.isBlank()) 0 else (if (modelReady()) 0 else catalog.model.size) +
        (if (complete(runtimeFolder,catalog.runtimes.getValue(abi).manifest)) 0 else catalog.runtimes.getValue(abi).ranges.sumOf { it.size })
    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256"); val ctx = currentCoroutineContext()
        file.inputStream().use { input -> val b=ByteArray(65536); while(true) { ctx.ensureActive(); val n=input.read(b); if(n<0) break; digest.update(b,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private suspend fun fetch(url: String, size: Long, expected: String, target: File, range: Long? = null, progress: (Long) -> Unit) {
        val request = Request.Builder().url(url).apply { if(range != null) header("Range", "bytes=$range-${range+size-1}") }.build()
        val ctx = currentCoroutineContext()
        http.newCall(request).execute().use { response ->
            if (range == null) check(response.isSuccessful) { "Download failed (HTTP ${response.code})" }
            else check(response.code == 206 && response.header("Content-Range")?.startsWith("bytes $range-${range+size-1}/") == true) { "The download server did not provide the requested runtime portion" }
            response.body.byteStream().use { input -> target.outputStream().use { out ->
                val b=ByteArray(65536); var total=0L
                while(true) { ctx.ensureActive(); val n=input.read(b); if(n<0) break; total+=n; check(total<=size) { "Download exceeds expected size" }; out.write(b,0,n); progress(n.toLong()) }
                check(total==size) { "Incomplete download; retry" }; out.fd.sync()
            } }
        }
        check(hash(target)==expected) { "Download verification failed; retry" }
    }
    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        require(abi.isNotBlank()) { "Kokoro does not support this device architecture" }
        context.assets.open("kokoro-recipe.bin").use { check(it.read() == 31 && it.read() == 139) { "Invalid model recipe asset" } }
        val temp=File(root,"kokoro-download.partial"); temp.deleteRecursively(); check(temp.mkdirs())
        val total=downloadBytes(); var done=0L
        val update: (Long)->Unit = { done+=it; progress("Downloading Kokoro: ${done/1_000_000} / ${total/1_000_000} MB") }
        try {
            val runtime=catalog.runtimes.getValue(abi)
            if (!complete(runtimeFolder,runtime.manifest)) {
                val parts=mutableMapOf<String,File>()
                for (part in runtime.ranges) {
                    val file=File(temp,part.path+".compressed")
                    fetch(catalog.runtimeUrl,part.size,part.sha256,file,part.offset,update); parts[part.path]=file
                }
                progress("Verifying $abi runtime…")
                KokoroModelFiles.prepare(runtimeRoot,runtime.manifest,runtime.manifest.version) { name ->
                    val input=parts.getValue(name).inputStream()
                    when(runtime.ranges.first { it.path==name }.method) { 0->input; 8->InflaterInputStream(input,Inflater(true)); else->error("Unsupported runtime compression") }
                }
                runtime.manifest.files.forEach { check(File(runtimeFolder,it.path).setReadOnly()) }
            }
            if (!modelReady()) {
                val model=catalog.model
                // Upgrade from an older full-precision installation without another large download.
                var existing: File? = null
                for (candidate in modelRoot.listFiles().orEmpty().filter { it.isDirectory }) {
                    val original=File(candidate,"model.onnx")
                    if (original.length()!=model.sourceSize || hash(original)!=model.sourceSha256) continue
                    var valid=true
                    for (entry in model.manifest.files.filter { it.path!="model.onnx" }) {
                        val file=File(candidate,entry.path)
                        if (!file.isFile || file.length()!=entry.size || hash(file)!=entry.sha256) { valid=false;break }
                    }
                    if (valid) { existing=candidate; break }
                }
                val extracted=existing ?: File(temp,"model").apply { mkdirs() }
                if (existing==null) {
                    val archive=File(temp,"model.tar.bz2")
                    fetch(model.url,model.size,model.sha256,archive,progress=update)
                    progress("Unpacking the Kokoro model on this phone…")
                    val allowed=model.manifest.files.associateBy { it.path }
                    TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            val entry=tar.nextEntry ?: break
                            val prefix="kokoro-multi-lang-v1_0/"
                            if(!entry.name.startsWith(prefix) || !entry.isFile) continue
                            val name=entry.name.removePrefix(prefix); val asset=allowed[name] ?: continue
                            val size=if(name=="model.onnx") model.sourceSize else asset.size
                            check(entry.size==size) { "Unexpected model asset size" }
                            val file=KokoroModelFiles.resolve(extracted,name); file.parentFile!!.mkdirs()
                            file.outputStream().use { out -> val b=ByteArray(65536); var left=size
                                while(left>0) { currentCoroutineContext().ensureActive(); val n=tar.read(b,0,minOf(left,b.size.toLong()).toInt()); check(n>0); out.write(b,0,n);left-=n }
                            }
                        }
                    }
                }
                val original=File(extracted,"model.onnx")
                check(hash(original)==model.sourceSha256) { "Unexpected original Kokoro model" }
                progress("Preparing the selective-8-bit model on this phone…")
                val converted=File(temp,"converted.onnx")
                context.assets.open("kokoro-recipe.bin").use { KokoroRecipe.apply(original,it,converted) }
                KokoroModelFiles.prepare(modelRoot,model.manifest) { if(it=="model.onnx") converted.inputStream() else File(extracted,it).inputStream() }
                modelRoot.listFiles().orEmpty().filter { it.isDirectory && it!=modelFolder }.forEach { it.deleteRecursively() }
            }
            progress("Kokoro installed. Ready for offline speech.")
        } finally { temp.deleteRecursively() }
    }
    suspend fun verifyForLoad() {
        check(installed()) { "Download Kokoro in Settings before generating speech" }
        for ((folder,manifest) in listOf(modelFolder to catalog.model.manifest, runtimeFolder to catalog.runtimes.getValue(abi).manifest)) {
            for (entry in manifest.files) check(hash(File(folder,entry.path))==entry.sha256) { "Kokoro files changed; delete and download Kokoro again" }
        }
    }
    fun delete() { check(modelRoot.deleteRecursively()); check(runtimeRoot.deleteRecursively()) }
}
