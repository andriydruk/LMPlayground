package com.druk.lmplayground.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.storage.DownloadProgress
import com.druk.lmplayground.storage.StorageDocuments
import com.druk.lmplayground.storage.StoragePreferences
import java.util.concurrent.TimeUnit

class DownloadRepository(private val context: Context) {

    companion object {
        private const val TAG = "DownloadRepository"
    }

    private val workManager = WorkManager.getInstance(context)

    /**
     * Download a model. The image module (mmproj) for vision models is enqueued
     * as a separate job; pass [includeMmproj] = false to fetch the text model
     * only (the module can be added later via [startMmprojDownload]).
     */
    fun startDownload(model: ModelInfo, storageUri: Uri, includeMmproj: Boolean = true) {
        val remoteUri = model.remoteUri ?: return
        val workName = workNameFor(model)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val textInputData = Data.Builder()
            .putString(DownloadWorker.KEY_URL, remoteUri.toString())
            .putString(DownloadWorker.KEY_FILENAME, model.filename)
            .putString(DownloadWorker.KEY_MODEL_NAME, model.name)
            .putString(DownloadWorker.KEY_STORAGE_URI, storageUri.toString())
            .putString(DownloadWorker.KEY_WORK_NAME, workName)
            .build()

        val textRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(textInputData)
            .setConstraints(constraints)
            .addTag(DownloadWorker.TAG_MODEL_DOWNLOAD)
            .addTag(model.name)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, textRequest)

        if (includeMmproj) {
            enqueueMmproj(model, storageUri, constraints)
        }
    }

    /**
     * Download only the image module (mmproj) for an already-installed vision
     * model. No-op if the model isn't a vision model.
     */
    fun startMmprojDownload(model: ModelInfo, storageUri: Uri) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        enqueueMmproj(model, storageUri, constraints)
    }

    /** Enqueue the mmproj projector download as a separate, uniquely-named job. */
    private fun enqueueMmproj(model: ModelInfo, storageUri: Uri, constraints: Constraints) {
        if (model.mmprojFilename == null || model.mmprojUri == null) return
        val mmprojWorkName = workNameFor(model) + "_mmproj"

        val mmprojInputData = Data.Builder()
            .putString(DownloadWorker.KEY_URL, model.mmprojUri.toString())
            .putString(DownloadWorker.KEY_FILENAME, model.mmprojFilename)
            .putString(DownloadWorker.KEY_MODEL_NAME, model.name + " (vision)")
            .putString(DownloadWorker.KEY_STORAGE_URI, storageUri.toString())
            .putString(DownloadWorker.KEY_WORK_NAME, mmprojWorkName)
            .build()

        val mmprojRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(mmprojInputData)
            .setConstraints(constraints)
            .addTag(DownloadWorker.TAG_MODEL_DOWNLOAD)
            .addTag(model.name)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(mmprojWorkName, ExistingWorkPolicy.KEEP, mmprojRequest)
    }

    fun cancelDownload(model: ModelInfo) {
        workManager.cancelUniqueWork(workNameFor(model))
        if (model.mmprojFilename != null) {
            workManager.cancelUniqueWork(workNameFor(model) + "_mmproj")
        }

        // Downloads now stream straight into "<filename>.part" inside the SAF
        // model folder (not app temp storage), so drop those partials here.
        // Prefix-match because createFile may have appended an extension.
        try {
            val storageUri = StoragePreferences(context).modelStorageUri
            if (storageUri != null) {
                val tree = StorageDocuments.fromStorageUri(context, storageUri)
                val files = tree?.listFiles()
                val partPrefixes = listOfNotNull(
                    "${model.filename}.part",
                    model.mmprojFilename?.let { "$it.part" },
                )
                partPrefixes.forEach { prefix ->
                    files?.firstOrNull { it.name?.startsWith(prefix) == true }?.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete partial on cancel: ${e.message}")
        }

        val notificationManager = DownloadNotificationManager(context)
        notificationManager.cancelNotification(notificationManager.getNotificationId(model.name))
        notificationManager.cancelNotification(notificationManager.getNotificationId(model.name + " (vision)"))
    }

    /**
     * WorkInfo stream for one model's (text-file) download — lets callers
     * react to terminal states, unlike [observeDownloads] which drops
     * finished work.
     */
    fun observeModelDownload(model: ModelInfo): LiveData<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkLiveData(workNameFor(model))

    fun observeDownloads(): LiveData<Map<String, DownloadProgress>> {
        return workManager
            .getWorkInfosByTagLiveData(DownloadWorker.TAG_MODEL_DOWNLOAD)
            .map { workInfoList -> mapWorkInfoToProgress(workInfoList) }
    }

    private fun mapWorkInfoToProgress(workInfoList: List<WorkInfo>): Map<String, DownloadProgress> {
        val result = mutableMapOf<String, DownloadProgress>()

        for (workInfo in workInfoList) {
            if (workInfo.state.isFinished) continue

            val progress = workInfo.progress
            val modelName = progress.getString(DownloadWorker.KEY_MODEL_NAME)

            if (modelName == null) {
                val tags = workInfo.tags
                val nameTag = tags.firstOrNull {
                    it != DownloadWorker.TAG_MODEL_DOWNLOAD && !it.startsWith("download_")
                }
                if (nameTag != null) {
                    val status = when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> "Waiting for network…"
                        WorkInfo.State.BLOCKED -> "Waiting…"
                        else -> "Starting download…"
                    }
                    result[nameTag] = DownloadProgress(
                        modelName = nameTag,
                        progress = -1f,
                        status = status
                    )
                }
                continue
            }

            val progressValue = progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f)
            val status = progress.getString(DownloadWorker.KEY_STATUS) ?: "Downloading…"
            val bytesDownloaded = progress.getLong(DownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
            val totalBytes = progress.getLong(DownloadWorker.KEY_TOTAL_BYTES, 0L)
            val speed = progress.getLong(DownloadWorker.KEY_SPEED_BYTES_PER_SEC, 0L)
            val eta = progress.getLong(DownloadWorker.KEY_ETA_SECONDS, -1L)

            result[modelName] = DownloadProgress(
                modelName = modelName,
                progress = progressValue,
                status = status,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                speedBytesPerSec = speed,
                etaSeconds = eta
            )
        }

        return result
    }

    private fun workNameFor(model: ModelInfo): String = "download_${model.filename}"
}
