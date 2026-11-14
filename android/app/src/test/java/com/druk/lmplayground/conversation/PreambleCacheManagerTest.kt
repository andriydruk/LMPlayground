package com.druk.lmplayground.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PreambleCacheManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager() = PreambleCacheManager(tmp.root)

    private fun kvDir(): File = File(tmp.root, "kv_preamble").apply { mkdirs() }

    /** Create a .bin + .json cache pair with a controlled mtime. */
    private fun cachePair(dir: File, name: String, mtime: Long) {
        File(dir, "$name.bin").apply { writeText("kv"); setLastModified(mtime) }
        File(dir, "$name.json").apply { writeText("{}") }
    }

    @Test
    fun sha1HexMatchesKnownVector() {
        // SHA-1("abc") — standard test vector.
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            manager().sha1Hex("abc")
        )
    }

    @Test
    fun fingerprintChangesWithModelSize() {
        val m = manager()
        // Model size participates in the fingerprint input, so a
        // replaced-but-same-named model file yields a different hash.
        val a = m.sha1Hex("model.gguf:1000 prompt tools")
        val b = m.sha1Hex("model.gguf:2000 prompt tools")
        assertFalse(a == b)
    }

    @Test
    fun pruneKeepsMostRecentPairsAndDeletesJsonSiblings() {
        val dir = kvDir()
        val base = System.currentTimeMillis() - 100_000
        for (i in 0 until 10) {
            cachePair(dir, "fp$i", base + i * 1000L)
        }
        manager().pruneOldKvPreambles(8)

        // The two oldest pairs (fp0, fp1) are gone, .json siblings included.
        for (i in 0 until 2) {
            assertFalse(File(dir, "fp$i.bin").exists())
            assertFalse(File(dir, "fp$i.json").exists())
        }
        for (i in 2 until 10) {
            assertTrue(File(dir, "fp$i.bin").exists())
            assertTrue(File(dir, "fp$i.json").exists())
        }
    }

    @Test
    fun pruneIsNoOpAtOrUnderLimit() {
        val dir = kvDir()
        val base = System.currentTimeMillis() - 100_000
        for (i in 0 until 8) {
            cachePair(dir, "fp$i", base + i * 1000L)
        }
        manager().pruneOldKvPreambles(8)
        assertEquals(8, dir.listFiles()!!.count { it.name.endsWith(".bin") })
    }

    @Test
    fun pruneToleratesMissingDirectory() {
        // No kv_preamble dir created — must not throw.
        manager().pruneOldKvPreambles(8)
    }
}
