package com.druk.lmplayground.models

import android.app.ActivityManager
import android.content.Context

/**
 * Heuristic for refusing to load models that won't fit on this device.
 * Gates ConversationViewModel.loadModel against the dominant Vitals
 * crash for v1.5.x — kernel evicts mmap'd weight pages, the next
 * compute kernel reads an unmapped page and segfaults.
 *
 * Heuristic source: llama.cpp issues #18949 / #14999 / discussion #1876
 * confirm that mmap-backed weights need roughly the model's on-disk size
 * resident in RAM during decode; KV cache and activations add ~10–30 %
 * working set on top.
 */
object DeviceCapability {

    fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /**
     * Weight repacking keeps the repacked copy of the quantized weights in
     * RAM on top of the still-mapped file buffer, so the peak resident
     * footprint during load is ~2x the on-disk model size. (Measured on a
     * 7.0 GB GGUF: load_tensors reports a ~7.0 GB CPU_Mapped buffer *and* a
     * ~7.0 GB CPU_REPACK buffer.)
     */
    private const val REPACK_FOOTPRINT_MULTIPLIER = 2

    /**
     * True when loading [modelSizeBytes] *with repacking* would blow the RAM
     * budget — the caller then loads the model memory-mapped instead
     * (repack disabled), trading matmul speed for a far smaller resident set.
     *
     * We budget against the repacked footprint (~2x the on-disk size), not
     * the raw size. Comparing only the raw size — as before — let models in
     * the "fits once but not twice" band (e.g. a 7.4 GB model on a 12 GB
     * phone: 63 % of RAM, under the old 70 % gate) pick the repack path and
     * get OOM-killed mid-load when the second ~7 GB buffer is allocated.
     * Repacking is kept only while the doubled footprint stays within 70 %
     * of total RAM; above that we fall back to mmap-only loading.
     */
    fun exceedsRamBudget(modelSizeBytes: Long, totalRamBytes: Long): Boolean {
        if (modelSizeBytes <= 0 || totalRamBytes <= 0) return false
        val repackedFootprintBytes = modelSizeBytes * REPACK_FOOTPRINT_MULTIPLIER
        return repackedFootprintBytes * 10 > totalRamBytes * 7
    }
}
