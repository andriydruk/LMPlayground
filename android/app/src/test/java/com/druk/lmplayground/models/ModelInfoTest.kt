package com.druk.lmplayground.models

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelInfoTest {

    @Test
    fun testIsVisionReturnsTrueWhenMmprojFilenameIsSet() {
        val model = ModelInfo(
            name = "Test Vision Model",
            filename = "test-model.gguf",
            description = "Test",
            mmprojFilename = "test-mmproj.gguf"
        )
        assertTrue(model.isVision)
    }

    @Test
    fun testIsVisionReturnsFalseWhenMmprojFilenameIsNull() {
        val model = ModelInfo(
            name = "Test Text Model",
            filename = "test-model.gguf",
            description = "Test"
        )
        assertFalse(model.isVision)
    }

    @Test
    fun testKnownFilenamesIncludesMmprojFiles() {
        val knownFilenames = ModelInfoProvider.knownFilenames
        // Check that vision model mmproj files are in the known filenames
        val visionModels = ModelInfoProvider.allModels.filter { it.isVision }
        assertTrue("Should have vision models", visionModels.isNotEmpty())
        for (model in visionModels) {
            assertTrue(
                "Known filenames should include mmproj file ${model.mmprojFilename}",
                model.mmprojFilename!! in knownFilenames
            )
            assertTrue(
                "Known filenames should include text model file ${model.filename}",
                model.filename in knownFilenames
            )
        }
    }

    @Test
    fun testGetModelsWithStatusDownloadedWithTextFileOnly() {
        // Vision model with only text file downloaded — should still be selectable
        val visionModel = ModelInfoProvider.allModels.first { it.isVision }
        val downloadedFilenames = setOf(visionModel.filename)

        val result = ModelInfoProvider.getModelsWithStatus(downloadedFilenames)
        val modelStatus = result.find { it.model.filename == visionModel.filename }

        assertTrue(
            "Vision model should be marked as downloaded when text file is present (vision is optional)",
            modelStatus!!.isDownloaded
        )
    }

    @Test
    fun testGetModelsWithStatusDownloadedWhenBothFilesPresent() {
        val visionModel = ModelInfoProvider.allModels.first { it.isVision }
        val downloadedFilenames = setOf(visionModel.filename, visionModel.mmprojFilename!!)

        val result = ModelInfoProvider.getModelsWithStatus(downloadedFilenames)
        val modelStatus = result.find { it.model.filename == visionModel.filename }

        assertTrue(
            "Vision model should be marked as downloaded when both files present",
            modelStatus!!.isDownloaded
        )
    }

    @Test
    fun testGranite41IsOnlyListedWhenAlreadyDownloaded() {
        // Superseded by Granite 4.2 3B: not offered, but still recognized.
        val file = "granite-4.1-3b-Q4_K_M.gguf"
        assertFalse(
            ModelInfoProvider.getModelsWithStatus(emptySet()).any { it.model.filename == file }
        )
        assertTrue(
            ModelInfoProvider.getModelsWithStatus(setOf(file)).any { it.model.filename == file }
        )
        assertTrue(
            ModelInfoProvider.getModelsWithStatus(emptySet())
                .any { it.model.filename == "granite-4.2-3b-Q4_K_M.gguf" }
        )
    }
}
