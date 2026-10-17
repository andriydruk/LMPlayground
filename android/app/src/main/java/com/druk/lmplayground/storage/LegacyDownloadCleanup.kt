package com.druk.lmplayground.storage

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Startup migration: remove stray model files left in the app's private files
 * directories by the old download flow.
 *
 * Before the move to streaming-direct-to-SAF, [com.druk.lmplayground.download.DownloadWorker]
 * downloaded into `getExternalFilesDir()/<name>.gguf` and then copied the
 * finished file into the user's SAF folder. If that copy failed — or the worker
 * process was killed mid-copy, which is exactly what happened when the device
 * ran out of space (the copy transiently needs 2x the model size) — the
 * full-size temp file was orphaned there, silently eating gigabytes.
 *
 * The current flow streams straight into the SAF folder and never touches these
 * directories, so any "*.gguf" file found here is dead weight and safe to
 * delete. The scan is cheap and idempotent (these dirs are empty under the new
 * flow), so it runs on every cold start as a self-healing janitor rather than a
 * one-shot gated behind a flag.
 */
object LegacyDownloadCleanup {

    private const val TAG = "LegacyDownloadCleanup"

    /**
     * Scan the app-private external and internal files dirs and delete any
     * orphaned model files. Must be called off the main thread — it does
     * file I/O.
     *
     * @return total bytes reclaimed.
     */
    fun run(context: Context): Long {
        val dirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
        val reclaimed = cleanDirs(dirs)
        if (reclaimed > 0) {
            Log.i(TAG, "Reclaimed ${reclaimed / 1_000_000} MB from orphaned model files in app-private storage")
        }
        return reclaimed
    }

    /**
     * Delete orphaned model files from [dirs]. A file is considered an orphaned
     * model if its name contains ".gguf" (covers "x.gguf", a stale "x.gguf.part",
     * and provider-mangled "x.gguf.part.bin"). Directories and unrelated files
     * are left untouched.
     *
     * Pure java.io (no Android dependencies) so it can be unit-tested against a
     * temp directory without a [Context]. Logging lives in [run].
     *
     * @return total bytes reclaimed.
     */
    internal fun cleanDirs(dirs: List<File>): Long {
        var reclaimed = 0L
        for (dir in dirs) {
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                if (!file.name.contains(".gguf", ignoreCase = true)) continue
                val size = file.length()
                if (file.delete()) {
                    reclaimed += size
                }
            }
        }
        return reclaimed
    }
}
