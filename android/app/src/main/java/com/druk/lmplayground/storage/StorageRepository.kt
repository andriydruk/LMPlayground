package com.druk.lmplayground.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.util.Log
import com.druk.lmplayground.models.ModelInfoProvider
import java.net.URLDecoder

class StorageRepository(
    private val context: Context,
    private val prefs: StoragePreferences
) {

    companion object {
        private const val TAG = "StorageRepository"
    }

    /**
     * Check if storage folder is configured.
     * User MUST select a folder before using the app.
     */
    fun isStorageConfigured(): Boolean {
        return prefs.modelStorageUri != null
    }

    /**
     * Get the configured storage URI.
     */
    fun getStorageUri(): Uri? = prefs.modelStorageUri

    /**
     * Set the storage folder.
     */
    fun setStorageFolder(uri: Uri) {
        prefs.modelStorageUri = uri
    }

    /**
     * Get list of model files from SAF storage.
     */
    fun getModelFiles(): List<ModelFile> {
        val treeUri = prefs.modelStorageUri ?: return emptyList()
        
        val documentFile = StorageDocuments.fromStorageUri(context, treeUri)
        if (documentFile == null) {
            Log.d(TAG, "getModelFiles() - could not resolve storage URI")
            return emptyList()
        }
        
        Log.d(TAG, "getModelFiles() - DocumentFile exists: ${documentFile.exists()}, canRead: ${documentFile.canRead()}")
        
        val files = documentFile.listFiles()
        Log.d(TAG, "getModelFiles() - found ${files.size} files total")
        
        return files
            .filter { it.name?.endsWith(".gguf") == true }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                Log.d(TAG, "getModelFiles() - found model: $name")
                ModelFile(
                    name = name,
                    displayName = ModelInfoProvider.getDisplayName(name),
                    sizeBytes = file.length(),
                    uri = file.uri
                )
            }
    }

    fun getStorageInfo(): StorageInfo {
        val treeUri = prefs.modelStorageUri
        if (treeUri == null) {
            return StorageInfo(
                path = "Not configured",
                usedBytes = 0,
                totalBytes = 0,
                availableBytes = 0,
                isCustomFolder = true
            )
        }
        
        val documentFile = StorageDocuments.fromStorageUri(context, treeUri)

        // Parse display path from URI
        val displayPath = if (treeUri.scheme == "file") {
            val rawPath = treeUri.path ?: ""
            if (rawPath.startsWith("/storage/emulated/0/")) {
                "Internal Storage/" + rawPath.removePrefix("/storage/emulated/0/")
            } else {
                rawPath
            }
        } else {
            val decodedPath = try {
                URLDecoder.decode(treeUri.path ?: "", "UTF-8")
            } catch (e: Exception) {
                treeUri.path ?: ""
            }
            decodedPath
                .removePrefix("/tree/")
                .replace(":", "/")
                .replace("primary", "Internal Storage")
        }
        
        if (documentFile == null) {
            return StorageInfo(
                path = displayPath,
                usedBytes = 0,
                totalBytes = 0,
                availableBytes = 0,
                isCustomFolder = true
            )
        }
        
        val files = documentFile.listFiles()
        val usedBytes = files
            .filter { it.name?.endsWith(".gguf") == true }
            .sumOf { it.length() }

        // Get storage stats from external storage as approximation
        val externalDir = context.getExternalFilesDir(null)
        val statFs = if (externalDir != null && externalDir.exists()) {
            try {
                StatFs(externalDir.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        return StorageInfo(
            path = displayPath,
            usedBytes = usedBytes,
            totalBytes = statFs?.totalBytes ?: 0,
            availableBytes = statFs?.availableBytes ?: 0,
            isCustomFolder = true
        )
    }

    fun deleteModel(fileName: String): Boolean {
        val treeUri = prefs.modelStorageUri ?: return false
        val documentFile = StorageDocuments.fromStorageUri(context, treeUri)
        val file = documentFile?.findFile(fileName)
        val deleted = file?.delete() == true
        Log.d(TAG, "deleteModel() - fileName: $fileName, deleted: $deleted")
        return deleted
    }

    /**
     * Result of opening a model file.
     * Contains the "fd:N" path for native code and keeps the ParcelFileDescriptor alive.
     * 
     * IMPORTANT: The ParcelFileDescriptor MUST be kept alive while the model is loaded!
     * Native code uses dup() to create copies of the fd, but the original must stay open.
     * Call close() when done with the model.
     */
    class ModelFileHandle(
        /**
         * Legacy `fd:N` path string referring to [pfd] in the *app* process.
         * Still used by code paths that resolve the file in-process. Once the
         * service moves to `:llama` (step 6) this string is no longer valid
         * cross-process — pass [pfd] directly to AIDL instead.
         */
        val path: String,
        /**
         * The open file descriptor for the model. Pass this across the
         * binder when calling the inference service so the service can
         * dup the FD into its own process and build its own `fd:N` string.
         */
        val pfd: ParcelFileDescriptor,
    ) {
        fun close() {
            try {
                pfd.close()
                Log.d(TAG, "ModelFileHandle closed")
            } catch (e: Exception) {
                Log.e(TAG, "ModelFileHandle.close() failed: ${e.message}")
            }
        }
    }
    
    /**
     * Open a model file and return a handle for native code.
     * 
     * Returns "fd:N" path format which our native ggml_fopen/llama_open overrides
     * will handle using dup() + fdopen()/open().
     * 
     * IMPORTANT: Keep the returned handle alive until the model is unloaded!
     */
    fun openModelFile(fileName: String): ModelFileHandle? {
        val treeUri = prefs.modelStorageUri
        if (treeUri == null) {
            Log.e(TAG, "openModelFile() - storage not configured")
            return null
        }
        
        val documentFile = StorageDocuments.fromStorageUri(context, treeUri)
        val file = documentFile?.findFile(fileName)

        if (file == null) {
            Log.e(TAG, "openModelFile() - file not found: $fileName")
            return null
        }
        
        return try {
            val pfd = context.contentResolver.openFileDescriptor(file.uri, "r")
            if (pfd != null) {
                val fd = pfd.fd
                val fdPath = "fd:$fd"
                
                Log.d(TAG, "openModelFile() - fileName: $fileName, fd: $fd, path: $fdPath")
                ModelFileHandle(fdPath, pfd)
            } else {
                Log.e(TAG, "openModelFile() - openFileDescriptor returned null")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "openModelFile() - failed: ${e.message}")
            null
        }
    }

    /**
     * Copy a model file from SAF storage to a regular file path.
     * Used for mmproj files which need a real filesystem path for native code.
     */
    fun copyModelToFile(fileName: String, destFile: java.io.File): Boolean {
        val treeUri = prefs.modelStorageUri ?: return false
        val documentFile = StorageDocuments.fromStorageUri(context, treeUri)
        val file = documentFile?.findFile(fileName) ?: return false

        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
            Log.d(TAG, "copyModelToFile() - copied $fileName to ${destFile.absolutePath} (${destFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyModelToFile() - failed: ${e.message}")
            false
        }
    }

    fun hasValidPermission(): Boolean {
        val uri = prefs.modelStorageUri ?: return false
        // The file:// fallback lives in app-private storage — no grant needed.
        if (uri.scheme == "file") {
            return uri.path?.let { java.io.File(it).canWrite() } == true
        }
        return try {
            val persistedUris = context.contentResolver.persistedUriPermissions
            persistedUris.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        } catch (e: Exception) {
            false
        }
    }
}
