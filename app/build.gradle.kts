plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.geminireader"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { applicationId = "com.geminireader"; minSdk = 26; targetSdk = 36; versionCode = 2; versionName = "0.2.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildTypes {
        create("canary") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".canary"
            versionNameSuffix = "-canary"
            matchingFallbacks += "debug"
        }
    }
    sourceSets.getByName("canary") {
        manifest.srcFile("src/debug/AndroidManifest.xml")
        res.directories.add("src/debug/res")
    }
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
    beforeVariants(selector().withBuildType("canary")) {
        it.hostTests.getValue("UnitTest").enable = true
        it.deviceTests.getValue("AndroidTest").enable = true
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
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
    if (name.matches(Regex("package(Debug|Release|Canary)(AndroidTest)?"))) {
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
