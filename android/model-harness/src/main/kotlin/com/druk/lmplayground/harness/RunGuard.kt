package com.druk.lmplayground.harness

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Guards against the two ways a sweep can quietly produce a wrong report.
 */
object RunGuard {

    /**
     * Exclusive lock on the report directory, held for the run.
     *
     * Two sweeps write the same report.md / results.json / raw tree, so a
     * second run started while one is in flight silently overwrites the
     * first's output — and, because they also compete for CPU, corrupts the
     * timings of both. An OS file lock is used rather than a marker file so
     * a killed run cannot leave a stale lock behind.
     *
     * Only the parent orchestrator takes this; workers inherit the
     * protection and must not lock (they would deadlock against their own
     * parent).
     */
    fun lockReportDir(reportDir: File): AutoCloseable {
        reportDir.mkdirs()
        val file = RandomAccessFile(File(reportDir, ".run.lock"), "rw")
        val lock: FileLock? = try {
            file.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            file.close()
            error(
                "another capability run is already writing $reportDir.\n" +
                    "        Wait for it to finish, or point this run elsewhere. Running two " +
                    "at once overwrites the report and skews every timing in it."
            )
        }
        return AutoCloseable {
            try { lock.release() } finally { file.close() }
        }
    }

    /**
     * Fingerprint of the compiled harness on the classpath.
     *
     * Workers are spawned with the parent's classpath, which points at build
     * output rather than a snapshot. Rebuilding the harness mid-sweep
     * therefore changes what later workers execute: probes added partway
     * through appear for some models and not others, with nothing in the
     * report to say why. Cheap to detect — newest mtime and file count over
     * the classpath entries.
     */
    fun buildStamp(): String {
        var newest = 0L
        var count = 0
        for (entry in System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)) {
            val f = File(entry)
            if (!f.exists()) continue
            if (f.isFile) {
                newest = maxOf(newest, f.lastModified()); count++
            } else {
                f.walkTopDown().forEach {
                    if (it.isFile) { newest = maxOf(newest, it.lastModified()); count++ }
                }
            }
        }
        return "$count:$newest"
    }

    /**
     * Aborts if the harness was rebuilt since [expected] was taken. Better to
     * stop with a clear reason than to publish a matrix whose rows came from
     * different builds.
     */
    fun requireUnchangedBuild(expected: String) {
        val now = buildStamp()
        if (now != expected) {
            error(
                "the harness was rebuilt while this run was in progress " +
                    "($expected -> $now).\n" +
                    "        Later models would run different probe code than earlier ones, so " +
                    "the report would mix builds. Re-run the sweep."
            )
        }
    }
}
