package com.druk.lmplayground.storage

import android.net.Uri

data class ModelFile(
    val name: String,
    val displayName: String,
    val sizeBytes: Long,
    val uri: Uri
)

data class StorageInfo(
    val path: String,
    val usedBytes: Long,
    val totalBytes: Long,
    val availableBytes: Long,
    val isCustomFolder: Boolean
)
