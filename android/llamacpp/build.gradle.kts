plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

// Deliberately a plain Kotlin/JVM module, not an Android library: everything
// here must be runnable on a desktop JVM so :model-harness can drive the real
// engine on macOS. Anything needing android.* (InferenceClient, the AIDL
// proxies, SamplerParams' @Parcelize) stays in :app.

dependencies {
    api(libs.kotlinx.coroutines.core)
    // org.json is provided by the Android framework at runtime, so the library
    // must not bundle it into the APK. JVM consumers (:model-harness, this
    // module's own unit tests) add the real artifact themselves.
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
