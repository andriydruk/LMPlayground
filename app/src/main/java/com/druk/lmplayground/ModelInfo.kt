package com.druk.lmplayground

import android.net.Uri
import java.io.File

data class ModelInfo(
    val name: String,
    val file: File? = null,
    val remoteUri: Uri? = null,
    val downloadId: Long? = null,
    val inputPrefix: String? = null,
    val inputSuffix: String? = null,
    val antiPrompt: String? = null,
    val description: String
)