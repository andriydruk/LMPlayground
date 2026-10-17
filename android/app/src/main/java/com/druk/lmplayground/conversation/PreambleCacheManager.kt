package com.druk.lmplayground.conversation

import android.util.Log
import com.druk.llamacpp.LlamaGenerationSession
import java.io.File
import java.security.MessageDigest

/**
 * Manages the persistent preamble (system prompt + tools) KV-cache files.
 * Path is `<filesDir>/kv_preamble/<fingerprint>` where fingerprint is SHA-1
 * over (model filename + size, system prompt, tools JSON). Cache files are
 * shared across sessions: any new conversation with the same model / sys
 * prompt / tool set re-uses the same disk cache. LRU prune keeps disk
 * footprint bounded ([KV_PREAMBLE_KEEP] most-recent files).
 */
class PreambleCacheManager(private val filesDir: File) {

    /**
     * Point [session] at its preamble cache file. [modelName] / [modelSize]
     * identify the loaded model — the byte size is part of the fingerprint so
     * a replaced-but-same-named model file invalidates stale caches (filename
     * alone wouldn't catch re-quantization or upgrades where the filename was
     * kept; the byte size differs in virtually all real cases). No-op (cache
     * disabled for the session) when the model name is unavailable.
     */
    fun apply(
        session: LlamaGenerationSession,
        modelName: String?,
        modelSize: Long,
        systemPrompt: String,
        toolsJson: String,
    ) {
        try {
            if (modelName.isNullOrEmpty()) {
                // Model info isn't ready (shouldn't happen here but be safe).
                session.setPreambleCachePath("", "")
                return
            }
            val modelKey = "$modelName:$modelSize"
            val fingerprint = sha1Hex(
                "$modelKey $systemPrompt $toolsJson"
            )
            val dir = kvPreambleDir().apply { mkdirs() }
            val path = File(dir, fingerprint).absolutePath
            session.setPreambleCachePath(path, fingerprint)
            pruneOldKvPreambles(KV_PREAMBLE_KEEP)
        } catch (t: Throwable) {
            Log.w(TAG, "applyPreambleCache failed (continuing without cache)", t)
            try { session.setPreambleCachePath("", "") } catch (_: Throwable) {}
        }
    }

    private fun kvPreambleDir(): File = File(filesDir, "kv_preamble")

    internal fun pruneOldKvPreambles(keep: Int) {
        try {
            val dir = kvPreambleDir()
            val bins = dir.listFiles()?.filter { it.name.endsWith(".bin") } ?: return
            if (bins.size <= keep) return
            val ordered = bins.sortedByDescending { it.lastModified() }
            for (i in keep until ordered.size) {
                val bin = ordered[i]
                bin.delete()
                File(bin.absolutePath.removeSuffix(".bin") + ".json").delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pruneOldKvPreambles failed", t)
        }
    }

    internal fun sha1Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "PreambleCacheManager"

        // Number of preamble cache files to retain (LRU by mtime). Each
        // file is small relative to the model itself but scales with
        // (system_prompt + tools_description) token count — typically a
        // few KB to a few hundred KB. 8 covers "user has 8 different
        // model + tool-set combinations they use regularly" without
        // bloating /data.
        const val KV_PREAMBLE_KEEP = 8

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
