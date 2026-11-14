import com.android.build.api.dsl.ManagedVirtualDevice
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.paparazzi)
}

// Pinned NDK (used both for android.ndkVersion and to locate the bundled glslc).
val ndkVersionPin = "27.2.12479018"

// --- Vulkan build toolchain discovery (portable; no machine-specific paths) ---
// glslc ships inside every NDK under shader-tools/<host>; auto-derive it from the
// pinned NDK, overridable via -PvulkanGlslc or the VULKAN_GLSLC env var.
val vulkanGlslcPath: String? = run {
    val override = (project.findProperty("vulkanGlslc") as String?) ?: System.getenv("VULKAN_GLSLC")
    if (override != null) return@run override
    val sdkDir = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: Properties().apply {
            val lp = rootProject.file("local.properties")
            if (lp.exists()) lp.inputStream().use { load(it) }
        }.getProperty("sdk.dir")
    val osName = System.getProperty("os.name").lowercase()
    val hostTag = when {
        osName.contains("mac") || osName.contains("darwin") -> "darwin-x86_64"
        osName.contains("win") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
    sdkDir?.let { File("$it/ndk/$ndkVersionPin/shader-tools/$hostTag/glslc") }
        ?.takeIf { it.exists() }?.absolutePath
}

// The NDK sysroot ships only the C Vulkan headers; ggml-vulkan needs the C++
// bindings (vulkan.hpp), so an external Vulkan-Headers source is required.
// Override via -PvulkanIncludeDir / VULKAN_HEADERS_DIR / VULKAN_SDK; otherwise
// probe the usual install locations (Homebrew on macOS, system include on Linux).
val vulkanIncludeDir: String? = run {
    val override = (project.findProperty("vulkanIncludeDir") as String?)
        ?: System.getenv("VULKAN_HEADERS_DIR")
        ?: System.getenv("VULKAN_SDK")?.let { "$it/include" }
    if (override != null) return@run override
    listOf(
        "/opt/homebrew/opt/vulkan-headers/include", // macOS arm64 (brew)
        "/usr/local/opt/vulkan-headers/include",    // macOS x86_64 (brew)
        "/usr/include",                              // Linux system vulkan-headers
    ).firstOrNull { File(it, "vulkan/vulkan.hpp").exists() }
}

// b9496's ggml-vulkan requires find_package(SPIRV-Headers CONFIG). Point CMake
// at the host CONFIG package dir. Override via -PspirvHeadersDir / SPIRV_HEADERS_DIR.
val spirvHeadersDir: String? = run {
    val override = (project.findProperty("spirvHeadersDir") as String?) ?: System.getenv("SPIRV_HEADERS_DIR")
    if (override != null) return@run override
    listOf(
        "/opt/homebrew/opt/spirv-headers/share/cmake/SPIRV-Headers", // macOS arm64 (brew)
        "/usr/local/opt/spirv-headers/share/cmake/SPIRV-Headers",    // macOS x86_64 (brew)
        "/usr/lib/cmake/SPIRV-Headers",                               // Linux system
        "/usr/share/cmake/SPIRV-Headers",
    ).firstOrNull { File(it, "SPIRV-HeadersConfig.cmake").exists() }
}

// ggml-vulkan.cpp #includes <spirv/unified1/spirv.hpp> but the ggml-vulkan
// CMake target only links SPIRV-Headers to the host shader generator, not to
// itself. The LunarG SDK ships these headers inside the Vulkan include dir;
// Homebrew splits them, so we add the SPIRV include dir to the build explicitly
// (consumed by our top-level CMakeLists via include_directories).
val spirvIncludeDir: String? = run {
    val override = (project.findProperty("spirvIncludeDir") as String?) ?: System.getenv("SPIRV_HEADERS_INCLUDE_DIR")
    if (override != null) return@run override
    listOf(
        "/opt/homebrew/opt/spirv-headers/include", // macOS arm64 (brew)
        "/usr/local/opt/spirv-headers/include",    // macOS x86_64 (brew)
        "/usr/include",                            // Linux system
    ).firstOrNull { File(it, "spirv/unified1/spirv.hpp").exists() }
}

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    namespace = "com.druk.lmplayground"

    defaultConfig {
        applicationId = "com.druk.lmplayground"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        val versionProps = Properties().apply {
            // Shared with the iOS app, so it lives at the repo root, one level up.
            rootProject.file("../version.properties").inputStream().use { load(it) }
        }
        val major = versionProps.getProperty("major").toInt()
        val minor = versionProps.getProperty("minor").toInt()
        val patch = versionProps.getProperty("patch").toInt()
        versionName = "$major.$minor.$patch"
        // versionCode = base × 1000 + CI run number. Local builds use 0.
        // Keeps ~1000 CI builds per patch before needing a version bump.
        versionCode = (major * 10000 + minor * 100 + patch) * 1000 +
            (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DLLAMA_CURL=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_VULKAN=ON"
                vulkanGlslcPath?.let { arguments += "-DVulkan_GLSLC_EXECUTABLE=$it" }
                vulkanIncludeDir?.let { arguments += "-DVulkan_INCLUDE_DIR=$it" }
                spirvHeadersDir?.let { arguments += "-DSPIRV-Headers_DIR=$it" }
                spirvIncludeDir?.let { arguments += "-DSPIRV_HEADERS_INCLUDE_DIR=$it" }
                // ggml-vulkan's find_package(SPIRV-Headers CONFIG) is a host
                // package; let the Android cross-compile toolchain search the
                // host prefix for CONFIG packages (does not affect FindVulkan,
                // which resolves the NDK's libvulkan via library/include modes).
                arguments += "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // Shared libs + dynamic CPU backend loading. Builds one
                // libggml-cpu-<variant>.so per ARM feature level (NEON,
                // dotprod, i8mm, SVE, ...) and picks the best match at
                // runtime via dlopen. Big prompt-eval speedups on
                // Snapdragon 8 Gen 1+, Dimensity 9000+, Tensor G3+.
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
            }
        }

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    ndkVersion = ndkVersionPin

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // Resources shared with the iOS app (chat-template overrides) live in
    // core/resources and are packaged as regular assets.
    sourceSets {
        getByName("main") {
            assets.srcDir("../../core/resources")
        }
    }

    // ggml_backend_load_all_from_path uses opendir on the JNI lib dir to
    // pick the best CPU variant, so the .so files must be extracted to a
    // real filesystem path (the modern default keeps them packed inside
    // the APK). Pairs with android:extractNativeLibs="true" in the
    // manifest.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        // We use a bundled debug keystore, to allow debug builds from CI to be upgradable
        val userKeystore = File(System.getProperty("user.home"), ".android/keystore.jks")
        val localKeystore = rootProject.file("debug.keystore")
        val hasKeyInfo = userKeystore.exists()
        named("debug") {
            storeFile = if (hasKeyInfo) userKeystore else localKeystore
            storePassword = if (hasKeyInfo) System.getenv("STORE_PASSWORD") else "android"
            keyAlias = if (hasKeyInfo) System.getenv("LM_PLAYGROUND_KEY_ALIAS") else "androiddebugkey"
            keyPassword = if (hasKeyInfo) System.getenv("LM_PLAYGROUND_KEY_PASSWORD") else "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isJniDebuggable = false
            // Use a distinct applicationId so debug + androidTest installs
            // coexist with any Play Store / release-signed build on the device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro")
            // Package native debug symbols into the AAB so Play Console
            // symbolicates :llama crashes — crash visibility without a
            // crash-reporting SDK (see ARCHITECTURE.md, "Crash visibility").
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    testOptions {
        managedDevices {
            allDevices {
                maybeCreate<ManagedVirtualDevice>("mvdApi35").apply {
                    device = "Pixel"
                    apiLevel = 35
                    systemImageSource = "google"
                    require64Bit = true
                }
                maybeCreate<ManagedVirtualDevice>("mvdTablet7Api35").apply {
                    device = "7in WSVGA (Tablet)"
                    apiLevel = 35
                    systemImageSource = "google"
                    require64Bit = true
                }
            }
        }
    }

    packaging.resources {
        // Multiple dependency bring these files in. Exclude them to enable
        // our test APK to build (has no effect on our AARs)
        excludes += "/META-INF/AL2.0"
        excludes += "/META-INF/LGPL2.1"
    }

    lint {
        // Existing issues live in the baseline; only new issues fail the build.
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }
}

// ── Play Store screenshot organization ───────────────────────────────
// Paparazzi writes files like "pkg_StoreScreenshots_scene0_hero[French].png"
// Play Store expects fastlane/metadata/android/{locale}/images/phoneScreenshots/{n}_{name}.png
val paparazziLocaleToPlayStore = mapOf(
    "English" to "en-US", "Spanish" to "es-ES", "Portuguese" to "pt-BR",
    "French" to "fr-FR", "German" to "de-DE", "Italian" to "it-IT",
    "Polish" to "pl-PL", "Ukrainian" to "uk", "Romanian" to "ro",
    "Turkish" to "tr-TR", "Arabic" to "ar", "Chinese" to "zh-CN",
    "Japanese" to "ja-JP", "Korean" to "ko-KR", "Indonesian" to "id",
    "Hindi" to "hi-IN", "Vietnamese" to "vi",
    "Thai" to "th", "Dutch" to "nl-NL", "Hebrew" to "iw-IL",
    "Czech" to "cs-CZ", "Swedish" to "sv-SE", "Bengali" to "bn-BD",
    "Malay" to "ms", "Filipino" to "fil", "Norwegian" to "nb-NO",
    "Danish" to "da-DK", "Finnish" to "fi-FI"
)

val sceneRenames = mapOf(
    "scene0_hero" to "0_hero",
    "scene1_chooseModel" to "1_choose_model",
    "scene2_chat" to "2_chat",
    "scene3_documents" to "3_documents",
    "scene4_chatWithImage" to "4_chat_with_image",
    "scene5_systemPrompts" to "5_system_prompts",
    "scene6_tools" to "6_tools",
    "scene7_generationParams" to "7_generation_params"
)

// TabletStoreScreenshots emits per-variant English-only landscape shots
// into fastlane's tablet-specific subdirectories. The variant displayName
// (`SevenInch` / `TenInch`) appears as the `[...]` suffix in the
// Paparazzi filename and routes the file to the right subfolder.
val tabletVariantToSubdir = mapOf(
    "SevenInch" to "sevenInchScreenshots",
    "TenInch" to "tenInchScreenshots",
)

tasks.register("organizeScreenshotsForPlayStore") {
    group = "play store"
    description = "Renames Paparazzi snapshots into fastlane/metadata phone + tablet layouts."

    val snapshotsDir = file("src/test/snapshots/images")
    val fastlaneDir = rootProject.file("fastlane/metadata/android")
    val phoneRegex = Regex("""_StoreScreenshots_(scene\d_\w+)\[(\w+)]\.png""")
    // Tablet filename has the form `[<VariantName>_<LocaleName>]`,
    // e.g. `[SevenInch_French]` — the test class composes both into
    // the parameterized name. Capture each piece separately so we
    // can route to `<locale>/images/<variant>Screenshots/`.
    val tabletRegex = Regex("""_TabletStoreScreenshots_(scene\d_\w+)\[(\w+)_(\w+)]\.png""")

    inputs.dir(snapshotsDir)
    outputs.dir(fastlaneDir)

    doLast {
        if (!snapshotsDir.exists()) {
            logger.warn("No Paparazzi snapshots found at $snapshotsDir — run recordPaparazziDebug first.")
            return@doLast
        }
        var copied = 0
        snapshotsDir.listFiles()?.forEach { src ->
            // Tablet files are checked first so the phone regex (which is
            // a substring match) doesn't accidentally pick them up if the
            // class names ever drift.
            tabletRegex.find(src.name)?.let { match ->
                val scene = sceneRenames[match.groupValues[1]] ?: return@let
                val subdir = tabletVariantToSubdir[match.groupValues[2]] ?: return@let
                val locale = paparazziLocaleToPlayStore[match.groupValues[3]] ?: return@let
                val destDir = File(fastlaneDir, "$locale/images/$subdir").apply { mkdirs() }
                src.copyTo(File(destDir, "$scene.png"), overwrite = true)
                copied++
                return@forEach
            }
            phoneRegex.find(src.name)?.let { match ->
                val scene = sceneRenames[match.groupValues[1]] ?: return@let
                val locale = paparazziLocaleToPlayStore[match.groupValues[2]] ?: return@let
                val destDir = File(fastlaneDir, "$locale/images/phoneScreenshots").apply { mkdirs() }
                src.copyTo(File(destDir, "$scene.png"), overwrite = true)
                copied++
            }
        }
        logger.lifecycle("Organized $copied screenshots into $fastlaneDir")
    }
}

tasks.matching { it.name == "recordPaparazziDebug" }.configureEach {
    finalizedBy("organizeScreenshotsForPlayStore")
}

// Paparazzi 2.0.0-alpha04 + current Gradle ships a transitive
// reporting extension that references an `org/gradle/reporting/
// HtmlWriterTools` class that no longer exists. The HTML report
// step crashes after tests succeed, marking the build FAILED and
// blocking the finalizedBy above from firing. We don't need the
// HTML test report for Paparazzi snapshot tests anyway — the
// snapshots themselves are the artifact. Disabling it lets a
// single `recordPaparazziDebug` invocation finish cleanly and
// auto-trigger the organize task.
tasks.withType<Test>().configureEach {
    reports.html.required.set(false)
    // CI runs unit tests with -PskipScreenshots: Paparazzi golden snapshots
    // are not git-tracked, so verify mode would fail there.
    if (project.hasProperty("skipScreenshots")) {
        exclude("**/screenshots/**")
    }
}

dependencies {
    implementation(project(":llamacpp"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.lifecycle.viewModelCompose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.google.android.material)

    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialWindow)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.util)
    implementation(libs.androidx.compose.ui.viewbinding)
    implementation(libs.androidx.compose.ui.googlefonts)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp3)
    implementation(libs.jsoup)
    implementation("androidx.javascriptengine:javascriptengine:1.0.0-beta01")
    // PDF text extraction for document (RAG) attachments
    implementation(libs.pdfbox.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.kt.compose)

    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)

    implementation(libs.haze)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
