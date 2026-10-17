plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":llamacpp"))
    implementation(libs.json)
}

val hostNativeDir = rootProject.layout.buildDirectory.dir("host/llamacpp")
val hostNativeBin = hostNativeDir.map { it.dir("bin") }

val configureHostNative by tasks.registering(Exec::class) {
    description = "Configure the macOS host build of libllamacpp"
    outputs.file(hostNativeDir.map { it.file("CMakeCache.txt") })
    commandLine(
        "cmake",
        "-S", rootProject.file("llamacpp/native").path,
        "-B", hostNativeDir.get().asFile.path,
        "-DCMAKE_BUILD_TYPE=Release",
    )
}

val buildHostNative by tasks.registering(Exec::class) {
    description = "Build libllamacpp for the host JVM"
    dependsOn(configureHostNative)
    commandLine(
        "cmake", "--build", hostNativeDir.get().asFile.path,
        "-j", Runtime.getRuntime().availableProcessors().toString(),
        "--target", "llamacpp",
    )
}

fun JavaExec.harnessDefaults() {
    dependsOn(buildHostNative)
    classpath = sourceSets["main"].runtimeClasspath
    val bin = hostNativeBin.get().asFile.absolutePath
    systemProperty("java.library.path", bin)
    systemProperty("lmp.nativeLibDir", bin)
    environment("DYLD_LIBRARY_PATH", bin)
}

tasks.register<JavaExec>("capabilityReport") {
    group = "verification"
    description = "Run model capability probes against local GGUFs"
    harnessDefaults()
    mainClass.set("com.druk.lmplayground.harness.MainKt")
}

// Prints the runtime classpath so the harness can be launched directly with
// `java -cp`, bypassing Gradle. Useful when a sweep must run while the build
// is otherwise busy — Gradle serializes builds, so a concurrent ./gradlew
// invocation would block behind it.
tasks.register("printHarnessClasspath") {
    val cp = sourceSets["main"].runtimeClasspath
    doLast { println(cp.asPath) }
}
