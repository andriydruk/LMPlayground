package com.druk.lmplayground.download

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.druk.lmplayground.models.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadRepositoryTest {

    private lateinit var app: Application
    private lateinit var repository: DownloadRepository
    private lateinit var workManager: WorkManager

    private val storageUri: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AModels")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        workManager = WorkManager.getInstance(app)
        repository = DownloadRepository(app)
    }

    private fun textModel() = ModelInfo(
        name = "Test Model",
        filename = "test-model.gguf",
        description = "test",
        remoteUri = Uri.parse("https://example.com/test-model.gguf"),
    )

    private fun visionModel() = ModelInfo(
        name = "Vision Model",
        filename = "vision-model.gguf",
        description = "test",
        remoteUri = Uri.parse("https://example.com/vision-model.gguf"),
        mmprojFilename = "mmproj-vision.gguf",
        mmprojUri = Uri.parse("https://example.com/mmproj-vision.gguf"),
    )

    private fun workInfos(uniqueName: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(uniqueName).get()

    @Test
    fun startDownloadEnqueuesUniqueTextJobWithInputData() {
        repository.startDownload(textModel(), storageUri)

        val infos = workInfos("download_test-model.gguf")
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        assertTrue(infos[0].tags.contains(DownloadWorker.TAG_MODEL_DOWNLOAD))
        assertTrue(infos[0].tags.contains("Test Model"))
        // No mmproj sibling for a text-only model.
        assertTrue(workInfos("download_test-model.gguf_mmproj").isEmpty())
    }

    @Test
    fun startDownloadEnqueuesMmprojSiblingForVisionModel() {
        repository.startDownload(visionModel(), storageUri)

        assertEquals(1, workInfos("download_vision-model.gguf").size)
        val mmproj = workInfos("download_vision-model.gguf_mmproj")
        assertEquals(1, mmproj.size)
        assertTrue(mmproj[0].tags.contains("Vision Model"))
    }

    @Test
    fun startDownloadSkipsMmprojWhenExcluded() {
        repository.startDownload(visionModel(), storageUri, includeMmproj = false)

        assertEquals(1, workInfos("download_vision-model.gguf").size)
        assertTrue(workInfos("download_vision-model.gguf_mmproj").isEmpty())
    }

    @Test
    fun startMmprojDownloadEnqueuesOnlyTheModule() {
        repository.startMmprojDownload(visionModel(), storageUri)

        assertTrue(workInfos("download_vision-model.gguf").isEmpty())
        assertEquals(1, workInfos("download_vision-model.gguf_mmproj").size)
    }

    @Test
    fun startDownloadWithoutRemoteUriIsNoOp() {
        repository.startDownload(
            ModelInfo(name = "No URI", filename = "no-uri.gguf", description = "test"),
            storageUri,
        )
        assertTrue(workInfos("download_no-uri.gguf").isEmpty())
    }

    @Test
    fun reEnqueueKeepsExistingJob() {
        repository.startDownload(textModel(), storageUri)
        repository.startDownload(textModel(), storageUri)

        // ExistingWorkPolicy.KEEP: the second enqueue must not replace or
        // duplicate the in-flight download.
        assertEquals(1, workInfos("download_test-model.gguf").size)
    }

    @Test
    fun cancelDownloadCancelsTextAndMmprojJobs() {
        repository.startDownload(visionModel(), storageUri)

        repository.cancelDownload(visionModel())

        assertEquals(WorkInfo.State.CANCELLED, workInfos("download_vision-model.gguf")[0].state)
        assertEquals(WorkInfo.State.CANCELLED, workInfos("download_vision-model.gguf_mmproj")[0].state)
    }

    @Test
    fun embeddingModelDownloadEnqueuesSingleJob() {
        val embedding = com.druk.lmplayground.models.ModelInfoProvider.embeddingModel

        repository.startDownload(embedding, storageUri)

        val infos = workInfos("download_${embedding.filename}")
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        // No mmproj sibling — the embedding model is a single GGUF.
        assertTrue(workInfos("download_${embedding.filename}_mmproj").isEmpty())
    }
}
