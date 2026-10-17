package com.druk.lmplayground.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Resolves the configured model-storage URI to a [DocumentFile] directory.
 *
 * Storage is normally a SAF tree URI returned by the system folder picker,
 * but ACTION_OPEN_DOCUMENT_TREE is handled only by the hidden DocumentsUI
 * system package — on devices where it is disabled or missing (debloated
 * ROMs, some OEM builds) the picker cannot open at all. There the app falls
 * back to a plain file:// URI under its own external-files dir, which needs
 * no picker and no permission.
 */
object StorageDocuments {

    /** [DocumentFile] for a storage [uri], branching on scheme (file:// fallback vs SAF tree). */
    fun fromStorageUri(context: Context, uri: Uri): DocumentFile? {
        return if (uri.scheme == "file") {
            uri.path?.let { DocumentFile.fromFile(File(it)) }
        } else {
            DocumentFile.fromTreeUri(context, uri)
        }
    }

    /**
     * App-private fallback models directory as a file:// URI, created on
     * demand. Returns null only if the directory cannot be created.
     */
    fun appStorageFallbackUri(context: Context): Uri? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "Models")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return Uri.fromFile(dir)
    }
}
