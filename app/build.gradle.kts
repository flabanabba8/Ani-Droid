import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
abstract class PrepareKokoroTask : DefaultTask() {
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val recipe: RegularFileProperty
    @get:InputFile abstract val packager: RegularFileProperty
    @get:InputFile abstract val quantizer: RegularFileProperty
    @get:InputDirectory abstract val licenses: DirectoryProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
        @get:OutputFile abstract val runtimeAar: RegularFileProperty
    @get:Inject abstract val execOperations: ExecOperations
    @TaskAction fun prepare() {
        execOperations.exec { commandLine("python3", script.get().asFile.absolutePath) }
    }
}
val prepareKokoro = tasks.register<PrepareKokoroTask>("prepareKokoro") {
    script.set(rootProject.file("tools/prepare-kokoro-android.py"))
    recipe.set(rootProject.file("tools/kokoro-recipe.py"))
    packager.set(rootProject.file("tools/package-kokoro-downloads.py"))
    quantizer.set(rootProject.file("tools/quantize-kokoro.py"))
    licenses.set(rootProject.file("docs/kokoro-licenses"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/kokoro/bootstrap-assets"))
    runtimeAar.set(layout.buildDirectory.file("generated/kokoro/sherpa-onnx.aar"))
}
android {
    namespace = "com.geminireader"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { applicationId = "com.geminireader"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        // The JNI runtime depends only on ONNX Runtime; these alternative API wrappers are unused.
        jniLibs.excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareKokoro, PrepareKokoroTask::assetsDirectory)
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation(files(prepareKokoro.flatMap { it.runtimeAar }))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Always write fresh APK archives. Incremental ZIP updates can retain large holes
// after bundled models are removed, even when their entries are no longer listed.
tasks.configureEach {
    if (name.matches(Regex("package(Debug|Release)(AndroidTest)?"))) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        doFirst {
            project.delete(outputs.files)
            project.delete(layout.buildDirectory.dir("intermediates/incremental/$name"))
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("pagecast.root", rootProject.projectDir.absolutePath)
}
