package com.druk.lmplayground.storage

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.download.DownloadRepository
import com.druk.lmplayground.models.MmprojPairing
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.models.resolveCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Represents download progress for a model
 * @param modelName The name of the model being downloaded
 * @param progress Download progress from 0f to 1f, or -1f for indeterminate (copying to storage)
 * @param status Human readable status text
 * @param bytesDownloaded Bytes downloaded so far
 * @param totalBytes Total file size in bytes, or 0 if unknown
 * @param speedBytesPerSec Current download speed in bytes per second
 * @param etaSeconds Estimated seconds remaining, or -1 if unknown
 */
data class DownloadProgress(
    val modelName: String,
    val progress: Float,
    val status: String,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L
)

/**
 * Represents a pending migration from old storage to new storage.
 * @param oldUri Source folder URI, or null if migrating from Downloads folder
 */
data class MigrationState(
    val oldUri: Uri?,
    val newUri: Uri,
    val modelsToMigrate: List<ModelFile>,
    val isFromDownloads: Boolean = oldUri == null
)

/**
 * Represents migration progress
 */
data class MigrationProgress(
    val currentModel: String,
    val currentIndex: Int,
    val totalCount: Int
)

class StorageViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val prefs = StoragePreferences(application)
    private val repository = StorageRepository(application, prefs)
    private val downloadRepo = DownloadRepository(application)

    private val _storageInfo = MutableLiveData<StorageInfo>()
    val storageInfo: LiveData<StorageInfo> = _storageInfo

    private val _downloadedModels = MutableLiveData<List<ModelFile>>()
    val downloadedModels: LiveData<List<ModelFile>> = _downloadedModels

    private val _allModels = MutableLiveData<List<ModelWithStatus>>()
    val allModels: LiveData<List<ModelWithStatus>> = _allModels

    /**
     * The document-Q&A embedding model's on-disk status. Kept out of
     * [allModels] (it must never appear in the chat picker or the Available
     * list); the Models screen shows it under Downloaded for storage
     * management — delete, and progress while its download is in flight.
     */
    private val _embeddingModel = MutableLiveData<ModelWithStatus?>(null)
    val embeddingModel: LiveData<ModelWithStatus?> = _embeddingModel

    /**
     * The voice-dictation model. Like [embeddingModel] it is not a chat model,
     * so it needs its own row — without one, a 675 MB download would be
     * invisible here and impossible to delete.
     */
    private val _dictationModel = MutableLiveData<ModelWithStatus?>(null)
    val dictationModel: LiveData<ModelWithStatus?> = _dictationModel

    private val _isStorageConfigured = MutableLiveData<Boolean>()
    val isStorageConfigured: LiveData<Boolean> = _isStorageConfigured

    val downloadingModels: LiveData<Map<String, DownloadProgress>> = downloadRepo.observeDownloads()

    val deviceLanguage: String = Locale.getDefault().language
    
    private val _snackbarMessage = MutableLiveData<String?>()
    val snackbarMessage: LiveData<String?> = _snackbarMessage
    
    // Migration state
    private val _pendingMigration = MutableLiveData<MigrationState?>()
    val pendingMigration: LiveData<MigrationState?> = _pendingMigration
    
    private val _migrationProgress = MutableLiveData<MigrationProgress?>()
    val migrationProgress: LiveData<MigrationProgress?> = _migrationProgress
    
    fun showSnackbar(message: String) {
        _snackbarMessage.postValue(message)
    }
    
    fun clearSnackbar() {
        _snackbarMessage.postValue(null)
    }

    fun checkStorageConfigured() {
        _isStorageConfigured.postValue(repository.isStorageConfigured())
    }

    fun loadStorageInfo() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _storageInfo.postValue(repository.getStorageInfo())
                val modelFiles = repository.getModelFiles()
                _downloadedModels.postValue(modelFiles)
                val downloadedFilenames = modelFiles.map { it.name }.toSet()
                val customModels = discoverCustomModels(modelFiles)
                _allModels.postValue(
                    ModelInfoProvider.getModelsWithStatus(downloadedFilenames, customModels)
                        .map { it.copy(model = it.model.resolveCapabilities(prefs)) }
                )
                _embeddingModel.postValue(
                    ModelWithStatus(
                        model = ModelInfoProvider.embeddingModel,
                        isDownloaded = ModelInfoProvider.embeddingModel.filename in downloadedFilenames,
                    )
                )
                _dictationModel.postValue(
                    ModelWithStatus(
                        model = ModelInfoProvider.dictationModel,
                        isDownloaded = ModelInfoProvider.dictationModel.filename in downloadedFilenames,
                    )
                )
            }
        }
    }

    private fun discoverCustomModels(modelFiles: List<ModelFile>): List<ModelInfo> {
        val llamaCpp = (getApplication<Application>() as? App)?.llamaCpp ?: return emptyList()
        val unknownFiles = modelFiles.filter { it.name !in ModelInfoProvider.knownFilenames }
        return unknownFiles.mapNotNull { file ->
            // A projector GGUF is paired with its base model (resolveMmproj), not
            // surfaced as a standalone model. Skip it before the metadata probe —
            // mmproj files have no chat template, so probing them is wasted work.
            if (MmprojPairing.isMmproj(file.name)) return@mapNotNull null
            val cached = prefs.getCustomModelMetadata(file.name)
            val (name, hasChatTemplate) = if (cached != null) {
                cached
            } else {
                val handle = repository.openModelFile(file.name) ?: return@mapNotNull null
                try {
                    // probeModelMetadata can throw InferenceUnavailableException
                    // if the :llama service hasn't connected yet (or just
                    // crashed). Skip discovery for this pass — the next
                    // loadStorageInfo (post bind) picks the model up.
                    val result = try {
                        llamaCpp.probeModelMetadata(handle.pfd) ?: return@mapNotNull null
                    } catch (_: com.druk.llamacpp.InferenceUnavailableException) {
                        return@mapNotNull null
                    }
                    val probedName = result[0]
                    val probedHasTemplate = result[1].toBoolean()
                    prefs.setCustomModelMetadata(file.name, probedName, probedHasTemplate)
                    Pair(probedName, probedHasTemplate)
                } finally {
                    handle.close()
                }
            }
            if (!hasChatTemplate) return@mapNotNull null
            ModelInfoProvider.createCustomModelInfo(file.name, name, file.sizeBytes)
        }
    }

    fun deleteModel(model: ModelFile) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteModel(model.name)
            }
            loadStorageInfo()
        }
    }
    
    fun deleteModel(model: ModelInfo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteModel(model.filename)
                // Also delete mmproj file for vision models
                if (model.mmprojFilename != null) {
                    repository.deleteModel(model.mmprojFilename)
                }
            }
            loadStorageInfo()
        }
    }

    /**
     * Request to change storage folder. If old folder has models, shows migration dialog.
     * For first-time setup (no old folder), checks Downloads folder for existing models.
     */
    fun requestStorageFolderChange(newUri: Uri) {
        val oldUri = repository.getStorageUri()
        
        viewModelScope.launch {
            if (oldUri == null) {
                // First time setup - check Downloads folder for existing models
                val modelsInDownloads = withContext(Dispatchers.IO) {
                    getModelFilesFromDownloads()
                }
                
                if (modelsInDownloads.isEmpty()) {
                    // No models to migrate, just set folder
                    setStorageFolderInternal(newUri)
                } else {
                    // Show migration dialog for Downloads folder
                    _pendingMigration.value = MigrationState(
                        oldUri = null, // null indicates Downloads folder
                        newUri = newUri,
                        modelsToMigrate = modelsInDownloads
                    )
                }
            } else if (oldUri == newUri) {
                // Same folder, nothing to do
                return@launch
            } else {
                // Changing from one folder to another - check old folder for models
                val modelsInOldFolder = withContext(Dispatchers.IO) {
                    getModelFilesFromUri(oldUri)
                }
                
                if (modelsInOldFolder.isEmpty()) {
                    // No models to migrate, just change folder
                    setStorageFolderInternal(newUri)
                } else {
                    // Show migration dialog
                    _pendingMigration.value = MigrationState(
                        oldUri = oldUri,
                        newUri = newUri,
                        modelsToMigrate = modelsInOldFolder
                    )
                }
            }
        }
    }
    
    /**
     * User confirmed migration - copy models from old folder to new folder
     */
    fun confirmMigration() {
        val migration = _pendingMigration.value ?: return
        _pendingMigration.value = null
        
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val models = migration.modelsToMigrate
                val newDocumentFile = StorageDocuments.fromStorageUri(context, migration.newUri)
                
                if (newDocumentFile == null) {
                    showSnackbar("Cannot access new folder")
                    return@withContext
                }
                
                var successCount = 0
                var failCount = 0
                
                models.forEachIndexed { index, modelFile ->
                    _migrationProgress.postValue(
                        MigrationProgress(
                            currentModel = modelFile.displayName,
                            currentIndex = index + 1,
                            totalCount = models.size
                        )
                    )
                    
                    try {
                        // Read from old location - handle both file:// and content:// URIs
                        val inputStream = if (migration.isFromDownloads) {
                            // Downloads folder uses file:// URI
                            File(modelFile.uri.path!!).inputStream()
                        } else {
                            // SAF folder uses content:// URI
                            context.contentResolver.openInputStream(modelFile.uri)
                        }
                        
                        if (inputStream == null) {
                            failCount++
                            return@forEachIndexed
                        }
                        
                        // Delete existing file in new location if any
                        newDocumentFile.findFile(modelFile.name)?.delete()
                        
                        // Create file in new location
                        val destFile = newDocumentFile.createFile("application/octet-stream", modelFile.name)
                        if (destFile == null) {
                            inputStream.close()
                            failCount++
                            return@forEachIndexed
                        }
                        
                        // Copy content
                        context.contentResolver.openOutputStream(destFile.uri)?.use { outputStream ->
                            inputStream.use { input ->
                                input.copyTo(outputStream, bufferSize = 8192)
                            }
                        }

                        // createFile may have appended an extension (e.g.
                        // ".bin") to the requested name — rename back so the
                        // model stays visible to the ".gguf" listing filter.
                        if (destFile.name != modelFile.name) {
                            destFile.renameTo(modelFile.name)
                        }

                        successCount++
                    } catch (e: Exception) {
                        failCount++
                    }
                }
                
                _migrationProgress.postValue(null)
                
                // Set new folder
                repository.setStorageFolder(migration.newUri)
                _isStorageConfigured.postValue(true)
                
                // Show result
                if (failCount == 0) {
                    showSnackbar("Migrated $successCount model(s)")
                } else {
                    showSnackbar("Migrated $successCount, failed $failCount model(s)")
                }
            }
            
            loadStorageInfo()
        }
    }
    
    /**
     * User declined migration - just change to new folder without copying
     */
    fun skipMigration() {
        val migration = _pendingMigration.value ?: return
        _pendingMigration.value = null
        setStorageFolderInternal(migration.newUri)
    }
    
    /**
     * User cancelled folder change
     */
    fun cancelMigration() {
        _pendingMigration.value = null
    }
    
    private fun setStorageFolderInternal(uri: Uri) {
        repository.setStorageFolder(uri)
        _isStorageConfigured.postValue(true)
        loadStorageInfo()
    }
    
    /**
     * Get model files from a specific URI (used for migration check)
     * Only returns files that match known model filenames.
     */
    private fun getModelFilesFromUri(uri: Uri): List<ModelFile> {
        val documentFile = StorageDocuments.fromStorageUri(context, uri) ?: return emptyList()
        val knownFilenames = ModelInfoProvider.knownFilenames
        
        return documentFile.listFiles()
            .filter { it.name in knownFilenames }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                ModelFile(
                    name = name,
                    displayName = ModelInfoProvider.getDisplayName(name),
                    sizeBytes = file.length(),
                    uri = file.uri
                )
            }
    }
    
    /**
     * Get model files from the system Downloads folder (for first-time migration)
     * Only returns files that match known model filenames.
     */
    private fun getModelFilesFromDownloads(): List<ModelFile> {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        
        if (!downloadsDir.exists() || !downloadsDir.canRead()) {
            return emptyList()
        }
        
        val knownFilenames = ModelInfoProvider.knownFilenames
        
        return downloadsDir.listFiles()
            ?.filter { it.isFile && it.name in knownFilenames }
            ?.map { file ->
                ModelFile(
                    name = file.name,
                    displayName = ModelInfoProvider.getDisplayName(file.name),
                    sizeBytes = file.length(),
                    uri = Uri.fromFile(file)
                )
            } ?: emptyList()
    }

    fun hasValidPermission(): Boolean {
        return repository.hasValidPermission()
    }

    fun getRepository(): StorageRepository = repository

    fun downloadModel(model: ModelInfo, includeMmproj: Boolean = true) {
        if (model.remoteUri == null) return
        val storageUri = repository.getStorageUri()
        if (storageUri == null) {
            showSnackbar("${model.name}: Storage not configured")
            return
        }
        downloadRepo.startDownload(model, storageUri, includeMmproj)
    }

    /** Download only the image module (mmproj) for an already-installed vision model. */
    fun downloadMmprojOnly(model: ModelInfo) {
        if (model.mmprojUri == null) return
        val storageUri = repository.getStorageUri()
        if (storageUri == null) {
            showSnackbar("${model.name}: Storage not configured")
            return
        }
        downloadRepo.startMmprojDownload(model, storageUri)
    }

    fun cancelDownload(model: ModelInfo) {
        // cancelDownload enumerates and deletes partial files via SAF
        // (DocumentsContract.deleteDocument), which are blocking binder calls.
        // Run off the main thread so a slow document provider can't ANR the UI.
        viewModelScope.launch(Dispatchers.IO) {
            downloadRepo.cancelDownload(model)
        }
    }
}
