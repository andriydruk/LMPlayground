package com.druk.lmplayground.harness

import java.io.File

/**
 * Test images, read straight from the instrumented tests' assets rather than
 * copied, so device and host probes look at the same pictures.
 */
object Fixtures {
    private val dir = File(Repo.root(), "app/src/androidTest/assets")

    /** [name] is a fixture filename, or an absolute path for ad-hoc images. */
    fun image(name: String): ByteArray? {
        val f = if (name.startsWith("/")) File(name) else File(dir, name)
        return f.takeIf { it.isFile }?.readBytes()
    }
}

/** Locates the Android project (android/) and repository roots from the working directory. */
object Repo {
    /** The Gradle root, `android/`. */
    fun root(): File {
        var d: File? = File(System.getProperty("user.dir"))
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile
        }
        return File(System.getProperty("user.dir"))
    }

    /** The repository root, which holds `core/` shared with the iOS app. */
    fun top(): File = root().absoluteFile.parentFile
}
