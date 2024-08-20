package com.druk.lmplayground.models

import android.net.Uri
import java.io.File

data class ModelInfo(
    val name: String,
    val file: File? = null,
    val remoteUri: Uri? = null,
    val downloadId: Long? = null,
    val inputPrefix: String? = null,
    val inputSuffix: String? = null,
    val antiPrompt: Array<String> = emptyArray(),
    val description: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ModelInfo

        if (name != other.name) return false
        if (file != other.file) return false
        if (remoteUri != other.remoteUri) return false
        if (downloadId != other.downloadId) return false
        if (inputPrefix != other.inputPrefix) return false
        if (inputSuffix != other.inputSuffix) return false
        if (!antiPrompt.contentEquals(other.antiPrompt)) return false
        if (description != other.description) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (file?.hashCode() ?: 0)
        result = 31 * result + (remoteUri?.hashCode() ?: 0)
        result = 31 * result + (downloadId?.hashCode() ?: 0)
        result = 31 * result + (inputPrefix?.hashCode() ?: 0)
        result = 31 * result + (inputSuffix?.hashCode() ?: 0)
        result = 31 * result + antiPrompt.contentHashCode()
        result = 31 * result + description.hashCode()
        return result
    }
}