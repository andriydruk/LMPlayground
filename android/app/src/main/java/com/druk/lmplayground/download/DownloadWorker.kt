package com.druk.lmplayground.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.druk.lmplayground.storage.StorageDocuments
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadWorker"

        const val KEY_URL = "url"
        const val KEY_FILENAME = "filename"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_STORAGE_URI = "storage_uri"
        const val KEY_WORK_NAME = "work_name"

        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SPEED_BYTES_PER_SEC = "speed_bytes_per_sec"
        const val KEY_ETA_SECONDS = "eta_seconds"
        const val KEY_STATUS = "status"
        const val KEY_PROGRESS = "progress"

        const val KEY_ERROR = "error"

        const val TAG_MODEL_DOWNLOAD = "model_download"

        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val SPEED_WINDOW_MS = 3000L
        private const val BUFFER_SIZE = 64 * 1024
        // openOutputStream on a local SAF folder hands back a real file
        // descriptor (PFD), so writes are plain syscalls — same speed as a
        // direct File write, no per-call IPC. A large BufferedOutputStream
        // still helps by batching many small network reads into fewer write()
        // syscalls.
        private const val SAF_WRITE_BUFFER = 1 shl 20 // 1 MiB
        // Suffix for the in-progress download. Stays out of the model list
        // because the scanner only accepts names ending in ".gguf"
        // (StorageRepository.getModelFiles).
        private const val PART_SUFFIX = ".part"
    }

    private val notificationManager = DownloadNotificationManager(applicationContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Override required for the framework to query our foreground info up-front
     * (e.g. when running as expedited work) without relying on `doWork()` having
     * reached its `setForeground()` call. Returning a fully-formed
     * [ForegroundInfo] here is the documented `CoroutineWorker` pattern for
     * avoiding `ForegroundServiceDidNotStartInTimeException`.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: ""
        val filename = inputData.getString(KEY_FILENAME) ?: ""
        val workName = inputData.getString(KEY_WORK_NAME) ?: "download_$filename"
        val notificationId = notificationManager.getNotificationId(modelName)
        val notification = notificationManager.buildProgressNotification(
            modelName = modelName,
            progress = 0f,
            bytesDownloaded = 0,
            totalBytes = 0,
            speedBytesPerSec = 0,
            etaSeconds = 0,
            workName = workName
        )
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result {
        // Promote to foreground IMMEDIATELY, before any other work. The system
        // gives us ~5s after startForegroundService() to call setForeground; on
        // a cold-started low-end device the OkHttpClient construction and
        // inputData reads below can eat into that budget. Building the
        // ForegroundInfo in getForegroundInfo() also lets the framework promote
        // us itself if it queries first.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground: ${e.message}")
        }

        val url = inputData.getString(KEY_URL) ?: return failure("Missing download URL")
        val filename = inputData.getString(KEY_FILENAME) ?: return failure("Missing filename")
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: return failure("Missing model name")
        val storageUriString = inputData.getString(KEY_STORAGE_URI) ?: return failure("Missing storage URI")
        val workName = inputData.getString(KEY_WORK_NAME) ?: "download_$filename"

        val storageUri = Uri.parse(storageUriString)
        val dir = StorageDocuments.fromStorageUri(applicationContext, storageUri)
            ?: return failure("Storage folder not accessible")
        val notificationId = notificationManager.getNotificationId(modelName)

        try {
            // Stream straight into "<filename>.part" inside the user's SAF
            // folder, then atomically rename it to the final ".gguf" name. This
            // replaces the old download-to-temp-then-copy flow, which needed 2x
            // the disk space and a slow second copy with no progress feedback.
            val partDoc = downloadToPart(url, dir, filename, modelName, workName, notificationId)
                ?: return failure("Download stopped")

            if (!finalizeDownload(dir, partDoc, filename)) {
                return failure("Failed to finalize download")
            }

            notificationManager.showNotification(
                notificationId,
                notificationManager.buildCompleteNotification(modelName)
            )

            return Result.success()
        } catch (e: IOException) {
            // Keep the .part file so the WorkManager retry resumes from where
            // it stopped (HTTP Range request against the existing length).
            Log.e(TAG, "Download IO error: ${e.message}", e)
            notificationManager.showNotification(
                notificationId,
                notificationManager.buildFailureNotification(modelName, e.message ?: "Network error")
            )
            return Result.retry()
        } catch (e: Exception) {
            // Non-recoverable: drop the partial so we don't leave a stale
            // ".part" lingering in the user's folder.
            Log.e(TAG, "Download error: ${e.message}", e)
            findPart(dir, filename)?.delete()
            notificationManager.showNotification(
                notificationId,
                notificationManager.buildFailureNotification(modelName, e.message ?: "Download failed")
            )
            return failure(e.message ?: "Download failed")
        }
    }

    /**
     * Download into "<filename>.part" inside [dir], resuming from any existing
     * partial. Returns the part [DocumentFile] on success, or null if the work
     * was stopped (cancelled) mid-stream.
     *
     * @param allowResume false forces a clean restart from byte 0 (used by the
     * truncation guard below when a provider doesn't honor append mode).
     */
    private suspend fun downloadToPart(
        url: String,
        dir: DocumentFile,
        filename: String,
        modelName: String,
        workName: String,
        notificationId: Int,
        allowResume: Boolean = true
    ): DocumentFile? {
        var partDoc = findPart(dir, filename)
        if (!allowResume) {
            partDoc?.delete()
            partDoc = null
        }
        var existingBytes = partDoc?.length() ?: 0L
        var append = partDoc != null && existingBytes > 0L
        if (append) {
            Log.d(TAG, "Resuming download from byte $existingBytes")
        }

        val requestBuilder = Request.Builder().url(url)
        if (append) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw IOException("HTTP ${response.code}: ${response.message}")
        }

        // Asked to resume but the server ignored Range — restart from scratch.
        if (append && response.code != 206) {
            Log.d(TAG, "Server does not support Range, restarting from scratch")
            append = false
            existingBytes = 0L
        }

        // Ensure a fresh part file whenever we're not appending.
        if (!append) {
            partDoc?.delete()
            partDoc = dir.createFile("application/octet-stream", filename + PART_SUFFIX)
            existingBytes = 0L
        }
        if (partDoc == null) {
            response.close()
            throw IOException("Could not create part file in storage folder")
        }
        val partUri = partDoc.uri

        val body = response.body ?: throw IOException("Empty response body")

        val contentLength = body.contentLength()
        val totalBytes = if (contentLength > 0) existingBytes + contentLength else -1L

        val mode = if (append) "wa" else "w"
        val rawOut = applicationContext.contentResolver.openOutputStream(partUri, mode)
            ?: run { response.close(); throw IOException("Could not open part file for writing") }

        // Guard: if we requested append but the provider truncated the file
        // (didn't honor "wa"), writing the 206 body onto it would corrupt the
        // model. Detect the length mismatch and restart cleanly from byte 0.
        if (append && partDoc.length() != existingBytes) {
            Log.w(TAG, "Append not honored (len ${partDoc.length()} != $existingBytes), restarting")
            rawOut.close()
            response.close()
            return downloadToPart(url, dir, filename, modelName, workName, notificationId, allowResume = false)
        }

        val speedTracker = SpeedTracker()
        var bytesDownloaded = existingBytes
        var lastProgressUpdate = 0L

        val outputStream = BufferedOutputStream(rawOut, SAF_WRITE_BUFFER)
        val inputStream = body.byteStream()

        try {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (isStopped) {
                    Log.d(TAG, "Download cancelled")
                    outputStream.flush()
                    return null
                }

                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                bytesDownloaded += bytesRead
                speedTracker.addBytes(bytesRead.toLong())

                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                    lastProgressUpdate = now

                    val speed = speedTracker.getSpeedBytesPerSec()
                    val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
                    val eta = if (totalBytes > 0 && speed > 0) {
                        (totalBytes - bytesDownloaded) / speed
                    } else {
                        -1L
                    }

                    reportStatus(modelName, progress, "Downloading…", bytesDownloaded, totalBytes, speed, eta)

                    // Update the pinned FGS notification directly. Calling
                    // setForeground() every 500ms re-routes through
                    // SystemForegroundService and reopens the overlap window
                    // tracked in WorkManager bug b/432069314 (fixed in 2.10.5
                    // but the cheaper notify() path is the right pattern
                    // regardless). notify() updates the same notification ID
                    // that's pinned to our FGS, so the FGS state is preserved.
                    notificationManager.showNotification(
                        notificationId,
                        notificationManager.buildProgressNotification(
                            modelName, progress, bytesDownloaded, totalBytes, speed, eta, workName
                        )
                    )
                }
            }
            outputStream.flush()
        } finally {
            inputStream.close()
            outputStream.close()
            response.close()
        }

        return partDoc
    }

    private suspend fun reportStatus(
        modelName: String,
        progress: Float,
        status: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        speed: Long,
        eta: Long
    ) {
        setProgress(
            Data.Builder()
                .putString(KEY_MODEL_NAME, modelName)
                .putFloat(KEY_PROGRESS, progress)
                .putString(KEY_STATUS, status)
                .putLong(KEY_BYTES_DOWNLOADED, bytesDownloaded)
                .putLong(KEY_TOTAL_BYTES, totalBytes)
                .putLong(KEY_SPEED_BYTES_PER_SEC, speed)
                .putLong(KEY_ETA_SECONDS, eta)
                .build()
        )
    }

    /**
     * Find the in-progress partial for [filename]. Matched by prefix because
     * SAF's createFile may append an extension (e.g. ".bin") to the
     * "<filename>.part" display name we request.
     */
    private fun findPart(dir: DocumentFile, filename: String): DocumentFile? {
        val partName = filename + PART_SUFFIX
        return dir.listFiles().firstOrNull { it.name?.startsWith(partName) == true }
    }

    /**
     * Publish the completed partial: delete any existing final file, then
     * rename "<filename>.part" -> "<filename>". The rename maps to
     * DocumentsContract.renameDocument — a metadata-only operation on local
     * storage, so no bytes are copied and no extra space is needed.
     */
    private fun finalizeDownload(dir: DocumentFile, partDoc: DocumentFile, filename: String): Boolean {
        dir.findFile(filename)?.delete()
        return try {
            val renamed = partDoc.renameTo(filename)
            if (!renamed) Log.e(TAG, "renameTo($filename) returned false")
            renamed
        } catch (e: Exception) {
            Log.e(TAG, "Finalize rename failed: ${e.message}", e)
            false
        }
    }

    private fun failure(message: String): Result {
        return Result.failure(
            Data.Builder()
                .putString(KEY_ERROR, message)
                .build()
        )
    }

    private class SpeedTracker {
        private data class Sample(val timestampMs: Long, val bytes: Long)

        private val samples = mutableListOf<Sample>()

        fun addBytes(bytes: Long) {
            samples.add(Sample(System.currentTimeMillis(), bytes))
            pruneOldSamples()
        }

        fun getSpeedBytesPerSec(): Long {
            pruneOldSamples()
            if (samples.size < 2) return 0

            val windowStart = samples.first().timestampMs
            val windowEnd = samples.last().timestampMs
            val durationMs = windowEnd - windowStart
            if (durationMs <= 0) return 0

            val totalBytes = samples.sumOf { it.bytes }
            return (totalBytes * 1000L) / durationMs
        }

        private fun pruneOldSamples() {
            val cutoff = System.currentTimeMillis() - SPEED_WINDOW_MS
            samples.removeAll { it.timestampMs < cutoff }
        }
    }
}
