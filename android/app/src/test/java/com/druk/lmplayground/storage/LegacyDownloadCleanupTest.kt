package com.druk.lmplayground.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyDownloadCleanupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deletesGgufOrphansAndReportsReclaimedBytes() {
        val dir = tempFolder.newFolder("files")
        val model = dir.resolve("Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf").apply { writeBytes(ByteArray(2048)) }
        val partial = dir.resolve("gpt-oss-20b-mxfp4.gguf.part").apply { writeBytes(ByteArray(1024)) }
        val mangled = dir.resolve("gpt-oss-20b-mxfp4.gguf.part.bin").apply { writeBytes(ByteArray(512)) }

        val reclaimed = LegacyDownloadCleanup.cleanDirs(listOf(dir))

        assertEquals(2048L + 1024L + 512L, reclaimed)
        assertFalse(model.exists())
        assertFalse(partial.exists())
        assertFalse(mangled.exists())
    }

    @Test
    fun leavesNonModelFilesUntouched() {
        val dir = tempFolder.newFolder("files")
        val keep = dir.resolve("settings.json").apply { writeBytes(ByteArray(64)) }
        val subdir = dir.resolve("cache").apply { mkdirs() }

        val reclaimed = LegacyDownloadCleanup.cleanDirs(listOf(dir))

        assertEquals(0L, reclaimed)
        assertTrue(keep.exists())
        assertTrue(subdir.exists())
    }

    @Test
    fun toleratesMissingDirectories() {
        val missing = tempFolder.root.resolve("does-not-exist")

        // Should not throw, and reclaims nothing.
        assertEquals(0L, LegacyDownloadCleanup.cleanDirs(listOf(missing)))
    }
}
