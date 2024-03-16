package com.druk.lmplayground.conversation

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.DOWNLOAD_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TreeMap
import kotlin.math.round


class ConversationViewModel(val app: Application) : AndroidViewModel(app) {

    private lateinit var llamaCpp: LlamaCpp
    private var llamaModel: LlamaModel? = null
    private var llamaSession: LlamaGenerationSession? = null
    private var generatingJob: Job? = null
    private val _isGenerating = MutableLiveData(false)
    private val _modelLoadingProgress = MutableLiveData(0f)
    private val _loadedModel = MutableLiveData<ModelInfo?>(null)
    private val _models = MutableLiveData<List<ModelInfo>>(emptyList())
    private var downloadModels = TreeMap<Long, ModelInfo>()
    private val downloadManager: DownloadManager by lazy {
        app.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
    }

    val isGenerating: LiveData<Boolean> = _isGenerating
    val modelLoadingProgress: LiveData<Float> = _modelLoadingProgress
    val loadedModel: LiveData<ModelInfo?> = _loadedModel
    val models: LiveData<List<ModelInfo>> = _models

    val uiState = ConversationUiState(
        initialMessages = emptyList(),
        channelName = "#composers",
        channelMembers = 42
    )

    private val downloadReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val model = downloadModels[id] ?: return
            val query = DownloadManager.Query()
            query.setFilterById(id)
            val cursor: Cursor = downloadManager.query(query)
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                when (cursor.getInt(columnIndex)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val filename = model.remoteUri?.lastPathSegment ?: return
                        val files = path.listFiles()
                        val file = files?.firstOrNull { it.name == filename } ?: return
                        loadModel(model.copy(file = file))
                    }
                    DownloadManager.STATUS_FAILED -> {
                        _loadedModel.postValue(null)
                        _modelLoadingProgress.postValue(0f)
                    }
                }
            }
            else {
                _loadedModel.postValue(null)
                _modelLoadingProgress.postValue(0f)
            }
            downloadModels.remove(id)
            if (downloadModels.isEmpty()) {
                handler.removeCallbacks(runnable)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun setLlama(llamaCpp: LlamaCpp) {
        this.llamaCpp = llamaCpp
    }

    override fun onCleared() {
        generatingJob?.cancel()
        viewModelScope.launch {
            llamaModel?.unloadModel()
        }
        app.unregisterReceiver(downloadReceiver)
        super.onCleared()
    }

    @MainThread
    fun loadModelList() {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                _models.postValue(
                    ModelInfoProvider.buildModelList()
                )
            }
        }
    }

    @MainThread
    fun loadModel(modelInfo: ModelInfo) {
        val file = modelInfo.file ?: return
        _models.postValue(emptyList())
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                _modelLoadingProgress.postValue(0f)
                _loadedModel.postValue(
                    modelInfo.copy(description = "Loading...")
                )
                val llamaModel = llamaCpp.loadModel(
                    file.path,
                    object: LlamaProgressCallback {
                        override fun onProgress(progress: Float) {
                            val progressDescription = "${round(100 * progress).toInt()}%"
                            _modelLoadingProgress.postValue(progress)
                            _loadedModel.postValue(
                                modelInfo.copy(
                                    description = progressDescription
                                )
                            )
                        }
                    }
                )
                val modelSize = llamaModel.getModelSize()
                val modelDescription = Formatter.formatFileSize(app, modelSize)
                val llamaSession = llamaModel.createSession(
                    modelInfo.inputPrefix,
                    modelInfo.inputSuffix
                )
                // warmup the model
                llamaSession.generate(object: LlamaGenerationCallback {
                    override fun newTokens(newTokens: ByteArray) {
                        // ignore
                    }
                })

                val callback = object: LlamaGenerationCallback {
                    override fun newTokens(newTokens: ByteArray) {
                        // ignore
                    }
                }
                while (this.isActive && llamaSession.generate(callback) == 0) {
                    // wait for the response
                }
                this@ConversationViewModel.llamaModel = llamaModel
                this@ConversationViewModel.llamaSession = llamaSession
                _modelLoadingProgress.postValue(0f)
                _loadedModel.postValue(
                    modelInfo.copy(
                        description = modelDescription
                    )
                )
            }
        }
    }

    @MainThread
    fun addMessage(message: Message) {
        uiState.addMessage(message)
        uiState.addMessage(
            Message(
                "Assistant",
                "",
                "8:07 PM"
            )
        )

        _isGenerating.postValue(true)
        generatingJob = viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val llamaSession = llamaSession ?: return@withContext
                llamaSession.addMessage(message.content)

                val callback = object: LlamaGenerationCallback {
                    var responseByteArray = ByteArray(0)
                    override fun newTokens(newTokens: ByteArray) {
                        responseByteArray += newTokens
                        uiState.updateLastMessage(String(responseByteArray, Charsets.UTF_8))
                    }
                }
                while (this.isActive && llamaSession.generate(callback) == 0) {
                    // wait for the response
                }
                llamaSession.printReport()
                _isGenerating.postValue(false)
            }
        }
    }

    @MainThread
    fun cancelGeneration() {
        generatingJob?.cancel()
        generatingJob = null
    }

    fun getReport(): String? {
        return llamaSession?.getReport()
    }

    fun unloadModel() {
        viewModelScope.launch {
            val loadedModel = _loadedModel.value ?: return@launch
            if (loadedModel.file != null) {
                generatingJob?.cancel()
                generatingJob = null
                llamaSession?.destroy()
                llamaSession = null
                llamaModel?.unloadModel()
                _loadedModel.postValue(null)
            }
            else {
                downloadModels.forEach { (id, model) ->
                    if (model.name == loadedModel.name) {
                        downloadManager.remove(id)
                    }
                }
            }
            uiState.resetMessages()
        }
    }

    fun resetModelList() {
        _models.postValue(emptyList())
    }

    fun downloadModel(model: ModelInfo) {
        val filename = model.remoteUri?.lastPathSegment ?: return
        val request = DownloadManager.Request(model.remoteUri)
        request.setTitle(filename)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val downloadManager = app.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            filename
        )
        val downloadId = downloadManager.enqueue(request)
        downloadModels[downloadId] = model
        handler.post(runnable)
    }

    private val handler: Handler = Handler(Looper.getMainLooper())
    private val runnable: Runnable = object : Runnable {
        override fun run() {
            downloadModels.lastKey()?.let {
                checkDownloadProgress(it, downloadModels[it]!!)
                handler.postDelayed(this, 100)
            }
        }
    }

    private fun checkDownloadProgress(downloadId: Long, model: ModelInfo) {
        val query = DownloadManager.Query()
        query.setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor.moveToFirst()) {
            val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            if (bytesDownloadedIndex == -1 || bytesTotalIndex == -1) {
                return
            }
            val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
            val bytesTotal = cursor.getLong(bytesTotalIndex)
            if (bytesTotal > 0) {
                val progress = bytesDownloaded.toFloat() / bytesTotal
                _loadedModel.postValue(
                    model.copy(
                        description = "Downloading ${(progress * 100L).toInt()}%"
                    )
                )
                _modelLoadingProgress.postValue(progress)
            }
        }
        cursor.close()
    }
}