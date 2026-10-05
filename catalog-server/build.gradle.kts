plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("../anidroid/src/main/java")
        kotlin.include("dev/anidroid/Providers.kt", "dev/anidroid/CatalogProtocol.kt", "dev/anidroid/server/**")
    }
}
application { mainClass = "dev.anidroid.server.ServerKt"; applicationDefaultJvmArgs = listOf("-Xmx256m") }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("org.json:json:20240303")
    implementation("org.xerial:sqlite-jdbc:3.51.3.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("junit:junit:4.13.2")
}
// Ship the same upstream notices as the Android module.
tasks.processResources {
    from("../anidroid/src/main/assets/licenses") { into("licenses") }
}
