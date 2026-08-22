package com.druk.lmplayground.conversation

import android.app.Application
import android.text.format.Formatter
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.druk.llamacpp.InferenceLimits
import com.druk.llamacpp.InferenceState
import com.druk.llamacpp.LlamaCpp
import com.druk.lmplayground.App
import com.druk.lmplayground.inference.ModelRuntime
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.ConversationMetadata
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.models.DeviceCapability
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ThinkingMode
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.models.resolveCapabilities
import com.druk.lmplayground.data.RagDocumentEntity
import com.druk.lmplayground.dictation.DictationController
import com.druk.lmplayground.dictation.DictationManager
import com.druk.lmplayground.download.DownloadRepository
import com.druk.lmplayground.rag.DocumentTextExtractor
import com.druk.lmplayground.rag.DocumentTextExtractors
import com.druk.lmplayground.rag.RagPromptBuilder
import com.druk.lmplayground.rag.RagRepository
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import com.druk.llamacpp.tools.ToolRegistry
import com.druk.lmplayground.tools.createDefault
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import android.content.Intent
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import android.net.Uri
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


class ConversationViewModel(val app: Application) : AndroidViewModel(app) {

    private val llamaCpp: LlamaCpp? = (app as? App)?.llamaCpp

    private val imageStore = ChatImageStore(app)
    private val preambleCache = PreambleCacheManager(app.filesDir)
    private val notifications = InferenceNotificationUpdater(app, llamaCpp)

    private val _isGenerating = MutableLiveData(false)
    private val _isModelReady = MutableLiveData(false)
    private val _modelLoadingProgress = MutableLiveData(0f)
    private val _loadedModel = MutableLiveData<ModelInfo?>(null)
    private val _loadedModelStatus = MutableLiveData<String?>(null)

    private val _models = MutableLiveData<List<ModelWithStatus>>(emptyList())
    private val _supportsThinking = MutableLiveData(false)
    private val _thinkingEnabled = MutableLiveData(false)
    // Distinct from supportsThinking: a model can think (so the budget applies)
    // while the on/off switch is meaningless because it always reasons.
    private val _thinkingToggleable = MutableLiveData(false)
    private val _generationParams = MutableLiveData(GenerationParams())
    private val _maxContextSize = MutableLiveData(4096)
    private val _sessionModelHint = MutableLiveData<Pair<String, String>?>(null) // (modelName, modelFilename)
    // Set when a vision-capable model loads without its image module (mmproj),
    // offering a one-time download. Carries the model so the tap can fetch it.
    private val _visionModuleHint = MutableLiveData<ModelInfo?>(null)
    private val _supportsVision = MutableLiveData(false)
    private val _supportsToolCalling = MutableLiveData(false)
    private val _toolEnabledStates = MutableLiveData<Map<String, Boolean>>(emptyMap())
    val toolRegistry = ToolRegistry.createDefault(app)
    val toolEnabledStates: LiveData<Map<String, Boolean>> = _toolEnabledStates
    private val _systemPrompt = MutableLiveData("")
    private val _systemPromptId = MutableLiveData<String?>(null)
    /**
     * One-shot user-facing error messages (e.g. "message too long").
     * The UI shows a Toast and resets to null via [consumeUserError].
     */
    private val _userError = MutableLiveData<String?>(null)
    /**
     * Set when [loadModel] hits the RAM-fit gate. The UI surfaces a
     * confirmation dialog so the user can override and load anyway.
     * Carries the (modelInfo, neededRam, totalRam) tuple so the dialog
     * can show concrete numbers without re-querying.
     */
    private val _pendingRamWarning =
        MutableLiveData<RamWarning?>(null)

    /**
     * Set when the native loader returns null — the GGUF is corrupt,
     * unreadable, or uses an architecture this build of llama.cpp
     * doesn't recognize. The UI surfaces a one-shot AlertDialog and
     * resets to null via [consumeModelLoadError].
     */
    private val _modelLoadError = MutableLiveData<String?>(null)

    private val storagePreferences = StoragePreferences(app)
    val storageRepository = StorageRepository(app, storagePreferences)

    /**
     * Translates engine lifecycle events into this ViewModel's LiveData.
     * Callbacks arrive from background dispatchers — postValue only.
     */
    private val runtimeListener = object : ModelRuntime.Listener {
        override fun onLoadProgress(progress: Float) {
            _modelLoadingProgress.postValue(progress)
        }

        override fun onLoadStatus(status: String?) {
            _loadedModelStatus.postValue(status)
        }

        override fun onModelResolved(model: ModelInfo) {
            _loadedModel.postValue(model)
            _thinkingEnabled.postValue(false)
            _supportsThinking.postValue(false)
            _thinkingToggleable.postValue(false)
            _supportsVision.postValue(false)
        }

        override fun onVisionModuleHint(model: ModelInfo?) {
            _visionModuleHint.postValue(model)
        }

        override fun onMaxContextSize(maxContextSize: Int) {
            _maxContextSize.postValue(maxContextSize)
        }

        override fun onGenerationParams(params: GenerationParams) {
            _generationParams.postValue(params)
        }

        override fun onSystemPromptReset() {
            _systemPrompt.postValue("")
            _systemPromptId.postValue(null)
        }

        override fun onCapabilities(
            model: ModelInfo,
            thinking: Boolean,
            vision: Boolean,
            toolCalling: Boolean,
        ) {
            // The template's flag is the fallback; measured behaviour wins where
            // we have it. Without this the toggle appears for models that never
            // think, and offers "off" to models that always do.
            val mode = model.thinkingMode
            val canThink = when (mode) {
                ThinkingMode.NONE -> false
                ThinkingMode.OPTIONAL, ThinkingMode.ALWAYS -> true
                ThinkingMode.UNKNOWN -> thinking
            }
            _supportsThinking.postValue(canThink)
            // Only offer the switch when turning it off actually does something.
            _thinkingToggleable.postValue(canThink && mode != ThinkingMode.ALWAYS)
            // A model that always reasons is shown as thinking, not as "off".
            if (mode == ThinkingMode.ALWAYS) _thinkingEnabled.postValue(true)
            _supportsVision.postValue(vision)
            _supportsToolCalling.postValue(toolCalling)
            if (toolCalling) {
                val states = mutableMapOf<String, Boolean>()
                for (tool in toolRegistry.getAllTools()) {
                    // Per-model override wins, else the global default.
                    val enabled = storagePreferences.effectiveToolEnabled(
                        model.filename, tool.name
                    )
                    toolRegistry.setToolEnabled(tool.name, enabled)
                    states[tool.name] = enabled
                }
                _toolEnabledStates.postValue(states)
            } else {
                _toolEnabledStates.postValue(emptyMap())
            }
            _sessionModelHint.postValue(null)
        }

        override fun onModelReady(model: ModelInfo) {
            _isModelReady.postValue(true)
            // Update session model info if we have an active session
            val sessionId = _currentSessionId.value
            if (sessionId != null) {
                viewModelScope.launch {
                    sessionStore.updateSessionModel(sessionId, model.filename, model.name)
                }
            }
        }

        override fun onModelLoadFailed(modelName: String) {
            _modelLoadError.postValue(
                app.getString(
                    com.druk.lmplayground.R.string.model_load_failed_message,
                    modelName,
                )
            )
        }

        override fun onUserError(message: String) {
            _userError.postValue(message)
        }
    }

    private val runtime = ModelRuntime(
        app,
        llamaCpp,
        (app as? App)?.inferenceClient,
        storageRepository,
        storagePreferences,
        notifications,
        runtimeListener,
    )

    /**
     * Voice dictation. Deliberately independent of [runtime]: the ASR model is
     * its own engine, so the microphone works whatever chat model is loaded.
     */
    val dictation = DictationController(
        app,
        DictationManager(llamaCpp, storageRepository),
        storageRepository,
        viewModelScope,
    )

    /**
     * Translates generation-turn events into uiState mutations and
     * persistence. Streaming callbacks arrive from background threads.
     */
    private val generationListener = object : GenerationCoordinator.Listener {
        override fun onToolStatesHydrated(states: Map<String, Boolean>) {
            _toolEnabledStates.postValue(states)
        }

        override fun onAssistantDelta(
            text: String,
            thinkingTokens: Int,
            responseTokens: Int,
            thinkingJustStarted: Boolean,
        ) {
            Snapshot.withMutableSnapshot {
                if (thinkingJustStarted) {
                    uiState.markThinkingStarted()
                }
                uiState.updateLastMessage(
                    text,
                    thinkingTokens = thinkingTokens,
                    responseTokens = responseTokens
                )
            }
        }

        override fun onToolCalls(infos: List<ToolCallInfo>) {
            Snapshot.withMutableSnapshot {
                uiState.addToolCallsToLastMessage(infos)
            }
        }

        override fun onThinkingRestarted() {
            Snapshot.withMutableSnapshot {
                uiState.markThinkingStarted()
            }
        }

        override fun onTurnAborted(messageId: Long) {
            Snapshot.withMutableSnapshot {
                // Drop the empty assistant placeholder so the chat
                // doesn't sit forever on a half-blank bubble.
                if (uiState.messages.lastOrNull()?.id == messageId) {
                    uiState.removeLastMessage()
                }
            }
            _isGenerating.postValue(false)
        }

        override fun onUserError(message: String) {
            _userError.postValue(message)
        }

        override suspend fun onTurnFinished(
            ourJob: Job?,
            messageId: Long,
            sessionId: String,
            totalTokens: Int,
        ) {
            // If a newer generation has taken over this slot
            // (crash + reload + new prompt while we were
            // draining the dead worker), our cleanup must NOT
            // touch any UI/persistence — uiState.messages
            // now belongs to the new turn, finalizing it
            // would clobber the in-flight new generation.
            // The new job's own finally will handle its
            // state. We just exit quietly.
            val supersededByNewer = runtime.generatingJob !== ourJob
            // Belt-and-suspenders: also confirm the last
            // message in uiState is still our placeholder
            // by stable Message.id identity.
            val last = uiState.messages.lastOrNull()
            val stillOurMessage = last != null &&
                last.author == "Assistant" &&
                last.id == messageId
            if (supersededByNewer || !stillOurMessage) {
                return
            }

            Snapshot.withMutableSnapshot {
                uiState.finalizeLastMessage()
            }
            _isGenerating.postValue(false)
            // Generation (or cancellation) is done — freeze the
            // silent notification on "Response ready" with the
            // final token count, and attach Copy/Share actions
            // bound to the finalized response (think-tags
            // stripped, matching the in-chat share/copy). Skipped
            // on the superseded path above, so a newer in-flight
            // turn's "Generating…" line is preserved.
            val readyBody = (uiState.messages.lastOrNull()
                ?.takeIf { it.author == "Assistant" }
                ?.content
                ?.let { stripThinkTags(it) })
                ?.takeIf { it.isNotBlank() }
            notifications.update(
                com.druk.lmplayground.R.string.inference_notification_ready_title,
                notifications.tokensLine(totalTokens),
                actionBody = readyBody,
            )

            // If the user isn't looking at the app, play a short
            // chime so they know the answer is ready. Gated on the
            // in-app setting, a non-blank response (so a cancelled/
            // empty turn stays quiet), and background state; the
            // helper itself also respects silent/vibrate/DND.
            if (storagePreferences.soundOnCompletion &&
                readyBody != null &&
                (app as? App)?.isAppInForeground == false
            ) {
                com.druk.lmplayground.inference.ResponseSound.playIfAudible(app)
            }

            // Persist whatever the assistant produced — including
            // a partially-streamed response on cancel — so
            // reload-from-DB matches what the user saw on screen.
            val assistantMessage = uiState.messages.lastOrNull()
            if (assistantMessage != null && assistantMessage.author == "Assistant") {
                try {
                    sessionStore.persistMessage(sessionId, assistantMessage)
                    sessionStore.touchSessionTimestamp(sessionId)
                    persistConversationMetadata(sessionId)
                } catch (_: Throwable) { /* best-effort */ }
            }
        }
    }

    private val generation = GenerationCoordinator(
        app,
        runtime,
        toolRegistry,
        storagePreferences,
        notifications,
        imageStore,
        preambleCache,
        generationListener,
    )


    // Whether to show the What's New "Set up tools" button. Shown until the
    // user has opened the Tools settings once (the flag is set there, not on
    // tap, so the button doesn't visibly vanish under the user's finger).
    // Re-read on resume so it disappears after returning from Tools settings.
    private val _showToolsSetup = MutableLiveData(!storagePreferences.toolsSetupSeen)
    val showToolsSetup: LiveData<Boolean> = _showToolsSetup

    @MainThread
    fun refreshToolsSetupVisibility() {
        _showToolsSetup.value = !storagePreferences.toolsSetupSeen
    }

    val isGenerating: LiveData<Boolean> = _isGenerating
    val isModelReady: LiveData<Boolean> = _isModelReady
    val modelLoadingProgress: LiveData<Float> = _modelLoadingProgress
    val loadedModel: LiveData<ModelInfo?> = _loadedModel
    val loadedModelStatus: LiveData<String?> = _loadedModelStatus
    val models: LiveData<List<ModelWithStatus>> = _models
    val supportsThinking: LiveData<Boolean> = _supportsThinking
    val thinkingEnabled: LiveData<Boolean> = _thinkingEnabled
    val thinkingToggleable: LiveData<Boolean> = _thinkingToggleable
    val generationParams: LiveData<GenerationParams> = _generationParams
    val maxContextSize: LiveData<Int> = _maxContextSize
    val sessionModelHint: LiveData<Pair<String, String>?> = _sessionModelHint
    val visionModuleHint: LiveData<ModelInfo?> = _visionModuleHint
    val supportsVision: LiveData<Boolean> = _supportsVision
    val supportsToolCalling: LiveData<Boolean> = _supportsToolCalling
    val systemPrompt: LiveData<String> = _systemPrompt
    val systemPromptId: LiveData<String?> = _systemPromptId
    val userError: LiveData<String?> = _userError
    val pendingRamWarning: LiveData<RamWarning?> = _pendingRamWarning
    val modelLoadError: LiveData<String?> = _modelLoadError

    /** Called by the UI after surfacing the error (e.g. as a Toast). */
    @MainThread
    fun consumeUserError() { _userError.value = null }

    @MainThread
    fun consumeModelLoadError() { _modelLoadError.value = null }

    @MainThread
    fun dismissRamWarning() { _pendingRamWarning.value = null }

    @MainThread
    fun confirmLoadDespiteRamWarning() {
        val pending = _pendingRamWarning.value ?: return
        _pendingRamWarning.value = null
        loadModel(pending.modelInfo, forceLoad = true)
    }

    val uiState = ConversationUiState(
        initialMessages = emptyList()
    )

    // Session persistence
    private val sessionStore = ChatSessionStore(
        (app as? App)?.chatRepository,
        (app as? App)?.systemPromptRepository,
        imageStore,
    )
    private val _currentSessionId = MutableLiveData<String?>(null)
    val currentSessionId: LiveData<String?> = _currentSessionId
    val sessions: LiveData<List<ChatSessionEntity>> = sessionStore.allSessions()
    /**
     * Per-model MRU list. When the loaded model changes, switchMap swaps in
     * the corresponding query so the picker reflects "prompts I've used on
     * *this* model" with the most-recently-used one first.
     */
    val recentSystemPrompts: LiveData<List<SystemPromptEntity>> =
        _loadedModel.switchMap { model ->
            sessionStore.recentSystemPromptsForModel(model?.filename)
        }

    // ── Document (RAG) attachments ───────────────────────────────────────
    private val ragRepository: RagRepository? = (app as? App)?.ragRepository

    /** Documents attached to the current chat, for the chips row. */
    val sessionDocuments: LiveData<List<RagDocumentEntity>> =
        _currentSessionId.switchMap { id ->
            val rag = ragRepository
            if (id == null || rag == null) MutableLiveData(emptyList())
            else rag.observeDocuments(id)
        }

    /** A picked document waiting on user action or a model download. */
    data class PendingDocument(
        val uri: Uri,
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long,
    )

    /**
     * Set when the user attached a document but the embedding model isn't
     * downloaded yet. The UI shows a download dialog; confirm keeps the
     * document pending and indexing starts when the download lands.
     */
    private val _embeddingModelPrompt = MutableLiveData<PendingDocument?>(null)
    val embeddingModelPrompt: LiveData<PendingDocument?> = _embeddingModelPrompt

    private var pendingDocument: PendingDocument? = null
    private var embeddingDownloadLiveData: LiveData<List<WorkInfo>>? = null
    private val embeddingDownloadObserver = Observer<List<WorkInfo>> { infos ->
        when (infos.firstOrNull()?.state) {
            WorkInfo.State.SUCCEEDED -> {
                stopObservingEmbeddingDownload()
                val doc = pendingDocument
                pendingDocument = null
                if (doc != null) {
                    viewModelScope.launch { startIndexing(doc) }
                }
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                stopObservingEmbeddingDownload()
                pendingDocument = null
                _userError.value =
                    app.getString(com.druk.lmplayground.R.string.document_embedding_download_failed)
            }
            else -> {}
        }
    }

    init {
        // Surface :llama process death to the UI. When the inference engine
        // crashes, the app process keeps running — we just need to tear
        // down stale handles, mark the in-flight assistant message as
        // interrupted, and let the user reload the model.
        val client = (app as? App)?.inferenceClient
        if (client != null) {
            viewModelScope.launch {
                client.state.collect { s ->
                    if (s is InferenceState.Crashed) onInferenceCrashed()
                }
            }
        }

        // Failed document indexing leaves no chip behind (the row is
        // deleted) — the reason arrives here once and surfaces as a toast.
        ragRepository?.let { rag ->
            viewModelScope.launch {
                rag.indexingFailures.collect { failure ->
                    _userError.postValue(app.getString(documentErrorRes(failure.reasonCode)))
                }
            }
        }
    }

    private fun documentErrorRes(reasonCode: String): Int = when (reasonCode) {
        "ENCRYPTED" -> com.druk.lmplayground.R.string.document_error_encrypted
        "NO_TEXT" -> com.druk.lmplayground.R.string.document_error_no_text
        "TOO_LARGE" -> com.druk.lmplayground.R.string.document_too_large
        "UNSUPPORTED" -> com.druk.lmplayground.R.string.document_unsupported_format
        "PARSE_FAILED" -> com.druk.lmplayground.R.string.document_error_parse_failed
        else -> com.druk.lmplayground.R.string.document_error_embedding
    }

    private fun onInferenceCrashed() {
        // Disable Send IMMEDIATELY (synchronously) so a tap that lands
        // between the crash and the recovery flow can't enqueue a new
        // generation through the stale UI state. setValue is safe here —
        // we're already on the main dispatcher (state.collect runs
        // inside viewModelScope.launch which uses Dispatchers.Main).
        _isModelReady.value = false
        _isGenerating.value = false

        // Snapshot the handles that were live at the time of the crash —
        // cleanup below must not touch a NEW model the user may load
        // during the drain wait (see ModelRuntime.crashSnapshot).
        val snapshot = runtime.crashSnapshot()

        viewModelScope.launch {
            // If the user already reloaded a model during the wait, the
            // current handles are NOT the stale ones — they belong to a
            // working session on a fresh :llama process. Bail without
            // touching anything; the new load already set
            // _isModelReady=true and a sensible status.
            if (!runtime.recoverFromCrash(snapshot)) {
                return@launch
            }

            _loadedModelStatus.value = app.getString(
                com.druk.lmplayground.R.string.inference_engine_crashed,
            )
            Snapshot.withMutableSnapshot {
                // If the assistant was mid-response when the engine died,
                // append a clear marker so the user understands the
                // message stopped because of a crash, not because the
                // model finished.
                val last = uiState.messages.lastOrNull()
                if (last != null && last.author == "Assistant" && last.responseStartTimeMs > 0) {
                    val suffix = "\n\n_${app.getString(com.druk.lmplayground.R.string.inference_engine_crashed)}_"
                    uiState.updateLastMessage(
                        last.content + suffix,
                        thinkingTokens = last.thinkingTokens,
                        responseTokens = last.responseTokens,
                    )
                }
                uiState.finalizeLastMessage()
            }
        }
    }

    override fun onCleared() {
        stopObservingEmbeddingDownload()
        runtime.shutdown()
        super.onCleared()
    }

    @MainThread
    fun loadModelList() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val modelFiles = storageRepository.getModelFiles()
                val downloadedFilenames = modelFiles.map { it.name }.toSet()
                val customModels = modelFiles
                    .filter { it.name !in ModelInfoProvider.knownFilenames }
                    .mapNotNull { file ->
                        val cached = storagePreferences.getCustomModelMetadata(file.name)
                            ?: return@mapNotNull null
                        if (!cached.second) return@mapNotNull null
                        ModelInfoProvider.createCustomModelInfo(file.name, cached.first, file.sizeBytes)
                    }
                _models.postValue(
                    ModelInfoProvider.getModelsWithStatus(downloadedFilenames, customModels)
                        .map { it.copy(model = it.model.resolveCapabilities(storagePreferences)) }
                )
            }
        }
    }

    @MainThread
    fun loadModel(modelInfo: ModelInfo, forceLoad: Boolean = false) {
        val llamaCpp = llamaCpp ?: return

        // Clear any prior vision-module offer; the mmproj block below re-posts
        // it if this model loads vision-capable but without its image module.
        _visionModuleHint.value = null

        viewModelScope.launch {
            // RAM-fit check. Run BEFORE we tear down the currently-loaded
            // model so the user can cancel the warning and keep their
            // existing session intact.
            //
            // Weight repacking is controlled by the user via Settings →
            // Advanced ("Enable repack"), ON by default. With it ON every
            // model repacks (faster matmuls) at the cost of a second resident
            // copy of the weights; with it OFF weights stay memory-mapped
            // (smaller footprint, slower decode). A model over the RAM budget
            // is never refused — but while repacking is on it can OOM-kill the
            // :llama process, so we warn once (unless repack is already off, in
            // which case the mmap-only load won't blow the budget). "Load
            // anyway" re-enters with forceLoad=true.
            val repackEnabled = storagePreferences.repackEnabled
            val modelFiles = withContext(Dispatchers.IO) { storageRepository.getModelFiles() }
            val fileSizeBytes = modelFiles.find { it.name == modelInfo.filename }?.sizeBytes ?: 0L
            // Pair the model with a multimodal projector present on disk (its
            // declared one, or a convention-matched sibling for custom/sideloaded
            // models). Authoritative for every load path, incl. loadModelByFilename.
            val model = ModelInfoProvider.resolveMmproj(
                modelInfo, modelFiles.map { it.name }.toSet()
            )
            val totalRamBytes = DeviceCapability.totalRamBytes(app)
            val exceedsRam = DeviceCapability.exceedsRamBudget(fileSizeBytes, totalRamBytes)
            if (!forceLoad && exceedsRam && repackEnabled) {
                _pendingRamWarning.value = RamWarning(
                    modelInfo = modelInfo,
                    neededRam = Formatter.formatFileSize(app, fileSizeBytes),
                    totalRam = Formatter.formatFileSize(app, totalRamBytes),
                )
                return@launch
            }

            _models.postValue(emptyList())
            _isModelReady.postValue(false)

            // Engine-side load: crash acknowledge, previous-model teardown,
            // native load + first session + history replay. Lifecycle events
            // come back through [runtimeListener].
            runtime.loadModel(model, disableRepack = !repackEnabled) { uiState.messages.toList() }
        }
    }

    /**
     * Pre-flight check for every session-recreation path (see
     * [HistoryReplay.validateReplaySize]). Maps failures to a localized
     * one-shot [_userError] and returns false; the caller MUST then abort
     * without mutating session state.
     */
    private fun validateReplaySize(systemPrompt: String, messages: List<Message>): Boolean {
        return when (val result = HistoryReplay.validateReplaySize(systemPrompt, messages)) {
            HistoryReplay.ValidationResult.Ok -> true
            is HistoryReplay.ValidationResult.SystemPromptTooLarge -> {
                _userError.postValue(
                    app.getString(
                        com.druk.lmplayground.R.string.system_prompt_too_large,
                        result.promptBytes / 1024,
                        result.maxBytes / 1024,
                    )
                )
                false
            }
            HistoryReplay.ValidationResult.MessageTooLarge -> {
                _userError.postValue(
                    app.getString(com.druk.lmplayground.R.string.history_message_too_large)
                )
                false
            }
        }
    }

    @MainThread
    fun toggleThinking() {
        _thinkingEnabled.value = _thinkingEnabled.value != true
    }


    @MainThread
    fun updateGenerationParams(params: GenerationParams) {
        val oldParams = _generationParams.value ?: GenerationParams()
        val systemPrompt = _systemPrompt.value.orEmpty()

        // Pre-validate BEFORE mutating any state. Without this, an
        // oversized prompt or saved message would set _generationParams
        // (UI shows the new params) and persist the update to Room,
        // then fail to recreate the session — leaving the UI showing
        // the new params but the engine running on the old session.
        val messagesToReplay = if (oldParams.contextSize != params.contextSize) {
            // Context-size change resets the conversation, no replay.
            emptyList()
        } else {
            uiState.messages.toList()
        }
        if (runtime.model != null && !validateReplaySize(systemPrompt, messagesToReplay)) return

        _generationParams.value = params

        // Save as per-model defaults
        val modelFilename = _loadedModel.value?.filename
        if (modelFilename != null) {
            storagePreferences.setModelGenerationParams(modelFilename, params.toMap())
        }

        // Persist to Room if we have an active session
        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                sessionStore.updateSessionParams(sessionId, params)
            }
        }

        // If context size changed, must recreate session (resets conversation)
        if (oldParams.contextSize != params.contextSize) {
            if (runtime.model == null) return
            viewModelScope.launch {
                runtime.cancelAndJoinGeneration()

                _currentSessionId.value = null
                uiState.resetMessages()

                runtime.resetSessionForContextChange(params, systemPrompt)
            }
        } else {
            // Other params: recreate session but replay history. We
            // already pre-validated at the top, so no validation here.
            if (runtime.model == null) return
            val messages = uiState.messages.toList()
            viewModelScope.launch {
                runtime.recreateSessionWithReplay(params, systemPrompt, messages)
            }
        }
    }

    @MainThread
    fun addMessage(message: Message, imageUri: Uri? = null) {
        // Persist the image so it survives the picker URI lifetime and is kept
        // with the saved conversation (path stored on the message row below).
        val persistedImageFile = imageUri?.let { imageStore.persistImageFile(it) }
        val userMessage = if (persistedImageFile != null) {
            message.copy(imageUri = imageStore.imageContentUri(persistedImageFile))
        } else {
            message
        }

        val enableThinking = _thinkingEnabled.value == true

        // Pre-validate the message size BEFORE we mutate any UI state.
        // If we appended the user/assistant placeholder first, an
        // oversized message would throw later — leaving the chat stuck
        // with `_isGenerating=true` and a half-empty assistant bubble.
        // A clean abort here matches what the user expects: nothing
        // visibly happened, but the input shows an error.
        val sizeBytes = message.content.length * 2
        if (sizeBytes > InferenceLimits.MAX_PAYLOAD_BYTES) {
            _userError.postValue(
                app.getString(
                    com.druk.lmplayground.R.string.message_too_large,
                    sizeBytes / 1024,
                    InferenceLimits.MAX_PAYLOAD_BYTES / 1024,
                )
            )
            return
        }

        Snapshot.withMutableSnapshot {
            uiState.addMessage(userMessage)
            val now = System.currentTimeMillis()
            uiState.addMessage(
                Message(
                    "Assistant",
                    "",
                    thinkingStartTimeMs = if (enableThinking) now else 0L,
                    responseStartTimeMs = now
                )
            )
        }

        _isGenerating.postValue(true)
        // Marker on the assistant placeholder we just added — used by the
        // cleanup path below to confirm the still-active message in
        // uiState is OURS and not a placeholder for some later turn the
        // user added after a crash + reload. Must be a field that
        // *every* phase of the placeholder preserves: previously this
        // was responseStartTimeMs, but addToolCallsToLastMessage resets
        // it to start the post-tool timer, so after the first tool call
        // the cleanup would always bail out and leave _isGenerating
        // stuck at true. Message.id is auto-incremented and never
        // changes through .copy() updates — the right identity field.
        val ourMessageId = uiState.messages.lastOrNull()?.id ?: -1L
        runtime.generatingJob = viewModelScope.launch {
            val ourJob = coroutineContext[Job]

            // Persist user message (with the attached image path, if any)
            val sessionId = ensureSession(message)
            sessionStore.persistMessage(sessionId, userMessage, persistedImageFile?.absolutePath)

            generation.runTurn(
                content = buildWireContent(sessionId, message.content),
                enableThinking = enableThinking,
                persistedImageFile = persistedImageFile,
                modelFilename = _loadedModel.value?.filename,
                supportsToolCalling = _supportsToolCalling.value == true,
                supportsThinking = _supportsThinking.value == true,
                systemPrompt = _systemPrompt.value.orEmpty(),
                messageId = ourMessageId,
                sessionId = sessionId,
                ourJob = ourJob,
            )
        }
    }

    private suspend fun ensureSession(firstUserMessage: Message): String =
        ensureSessionId(firstUserMessage.content.take(50))

    /**
     * Return the current session id, creating (and persisting) the session
     * row first if this chat is brand-new. Mutex-guarded because both the
     * send path and the document-attach path can race here, and each
     * would otherwise create its own session.
     */
    private suspend fun ensureSessionId(title: String): String = sessionCreateMutex.withLock {
        val existing = _currentSessionId.value
        if (existing != null) return existing

        val id = sessionStore.createSession(
            title,
            _loadedModel.value,
            _generationParams.value ?: GenerationParams(),
            _systemPrompt.value.orEmpty(),
        )
        // Synchronous main-thread set (not postValue): a follow-up
        // ensureSessionId must observe the id as soon as the lock drops.
        withContext(Dispatchers.Main.immediate) { _currentSessionId.value = id }
        return id
    }

    private val sessionCreateMutex = Mutex()

    /**
     * Retrieval injection (RAG): when this chat has READY documents, wrap
     * the outgoing message with the top-scoring excerpts. Wire-only —
     * uiState and Room keep the original text, and history replay resends
     * originals (retrieval re-runs for every new turn instead). Any
     * failure degrades to the plain message.
     */
    private suspend fun buildWireContent(sessionId: String, userText: String): String {
        val rag = ragRepository ?: return userText
        return try {
            if (!rag.hasReadyDocuments(sessionId)) return userText
            val retrieved = rag.retrieve(sessionId, userText)
            if (retrieved.isEmpty()) return userText
            val contextSize = _generationParams.value?.contextSize ?: 4096
            // Excerpt budget: ~25% of the session context, and never past
            // the binder payload ceiling (the pre-flight check in
            // addMessage only covered the original text).
            val budget = minOf(
                RagPromptBuilder.budgetChars(contextSize),
                InferenceLimits.MAX_PAYLOAD_BYTES / 2 - userText.length - 500,
            )
            RagPromptBuilder.build(userText, retrieved, budget)
        } catch (t: Throwable) {
            android.util.Log.w("ConversationViewModel", "Retrieval failed; sending plain message", t)
            userText
        }
    }

    private suspend fun persistConversationMetadata(sessionId: String) {
        sessionStore.persistWebLinks(sessionId, toolRegistry.webLinkStore.snapshot())
    }

    @MainThread
    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val uiMessages = sessionStore.loadSessionMessages(sessionId) ?: return@launch
            val sessionEntity = sessionStore.getSession(sessionId)

            // Pre-flight the saved chat against the AIDL payload cap
            // BEFORE switching any UI state. If a persisted message
            // (or the session's saved system prompt) is too large for
            // the binder, refuse the swap entirely — keep the user on
            // their current chat instead of half-loading a session
            // whose generated output would silently come from the
            // OLD session's KV cache.
            val newSystemPrompt = sessionEntity?.systemPrompt ?: ""
            if (!validateReplaySize(newSystemPrompt, uiMessages)) {
                return@launch
            }

            _currentSessionId.value = sessionId

            // Drop the previous conversation's web_search references; the loaded
            // session's own references (if any) are restored just below.
            toolRegistry.webLinkStore.clear()

            // Restore generation params from session
            if (sessionEntity != null) {
                val params = GenerationParams(
                    contextSize = sessionEntity.contextSize,
                    temperature = sessionEntity.temperature,
                    topP = sessionEntity.topP,
                    repetitionPenalty = sessionEntity.repetitionPenalty,
                    topK = sessionEntity.topK,
                    minP = sessionEntity.minP,
                    seed = sessionEntity.seed,
                    thinkingBudget = sessionEntity.thinkingBudget
                )
                _generationParams.value = params
                _systemPrompt.value = sessionEntity.systemPrompt
                // Try to rehydrate the library id from the stored text so that
                // "Update prompt" in the Generation Params sheet can target the
                // same library entry when it still matches.
                val stored = sessionEntity.systemPrompt
                if (stored.isEmpty()) {
                    _systemPromptId.value = null
                } else {
                    val entity = sessionStore.findSystemPromptByText(stored)
                    _systemPromptId.value = entity?.id
                }

                // Restore web_search link references saved with this conversation
                // so the model can still web_fetch a previously-returned ref.
                toolRegistry.webLinkStore.restore(
                    ConversationMetadata.parse(sessionEntity.metadata)
                        .getStringMap(ConversationMetadata.KEY_WEB_LINKS)
                )
            }

            // Show model hint if session used a different model
            if (sessionEntity != null &&
                sessionEntity.modelFilename.isNotEmpty() &&
                sessionEntity.modelFilename != _loadedModel.value?.filename
            ) {
                _sessionModelHint.value = Pair(sessionEntity.modelName, sessionEntity.modelFilename)
            } else {
                _sessionModelHint.value = null
            }

            uiState.setMessages(uiMessages)

            // Recreate native session with restored params and replay history.
            // Pre-validation already happened at the top of this function
            // (before any UI state mutation).
            if (runtime.model != null) {
                val systemPrompt = _systemPrompt.value.orEmpty()
                val params = _generationParams.value ?: GenerationParams()
                runtime.recreateSessionWithReplay(params, systemPrompt, uiMessages)
            }
        }
    }

    @MainThread
    fun newConversation() {
        viewModelScope.launch {
            runtime.cancelAndJoinGeneration()

            _currentSessionId.value = null
            _sessionModelHint.value = null
            uiState.resetMessages()
            // Fresh conversation starts with no web_search references.
            toolRegistry.webLinkStore.clear()

            // Recreate native session with clean KV cache
            runtime.resetSessionDestroyFirst(
                _generationParams.value ?: GenerationParams(),
                _systemPrompt.value.orEmpty(),
            )
        }
    }

    /**
     * Apply a system prompt to the current session. Recreates the native session
     * so the new prompt takes effect, replays any existing messages, and bumps
     * the library entry's `lastUsedAt` when [promptId] is non-null.
     *
     * The intended caller is the picker row on an empty conversation, but the
     * method also supports mid-chat swaps (history replay handles it).
     */
    @MainThread
    fun applySystemPrompt(promptId: String?, text: String) {
        val current = _systemPrompt.value.orEmpty()
        val currentId = _systemPromptId.value
        if (current == text && currentId == promptId) return

        // Pre-validate BEFORE mutating any state. Without this, an
        // oversized prompt would set _systemPrompt (UI shows the new
        // prompt), destroy the old session, then throw inside
        // createSession — leaving the user with an in-flight UI but
        // a null llamaSession. The next Send would hit the early-
        // return inside addMessage and the placeholder would never
        // get cleaned up.
        val messages = uiState.messages.toList()
        if (!validateReplaySize(text, messages)) return

        _systemPrompt.value = text
        _systemPromptId.value = promptId

        // Persist on the active session row if one exists.
        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                sessionStore.updateSessionSystemPrompt(sessionId, text)
            }
        }

        // Bump per-model MRU for library-sourced picks.
        if (promptId != null) {
            val modelFilename = _loadedModel.value?.filename
            if (!modelFilename.isNullOrEmpty()) {
                viewModelScope.launch {
                    sessionStore.touchSystemPromptUsage(promptId, modelFilename)
                }
            }
        }

        // Recreate the native session so the prompt lands as message[0].
        // Create-then-destroy with a defense-in-depth catch around create
        // (on top of the validateReplaySize pre-check).
        if (runtime.model == null) return
        val params = _generationParams.value ?: GenerationParams()
        viewModelScope.launch {
            runtime.recreateSessionWithReplay(
                params, text, messages,
                catchPayloadTooLargeOnCreate = true,
            )
        }
    }

    @MainThread
    fun clearSystemPrompt() = applySystemPrompt(null, "")

    /**
     * Overwrite the text of the library entry currently backing this session
     * (if any) and apply the new text to the session. Used by the
     * Generation Params "Update prompt" button.
     */
    @MainThread
    fun updateLinkedSystemPrompt(text: String) {
        val trimmed = text.trim()
        val id = _systemPromptId.value
        if (id == null || !sessionStore.systemPromptsAvailable) {
            applySystemPrompt(null, trimmed)
            return
        }
        viewModelScope.launch {
            if (!sessionStore.updateSystemPromptText(id, trimmed)) return@launch
            applySystemPrompt(id, trimmed)
        }
    }

    /**
     * Persist a brand-new system prompt to the library and apply it to the
     * current session.
     */
    @MainThread
    fun createAndApplySystemPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!sessionStore.systemPromptsAvailable) {
            applySystemPrompt(null, trimmed)
            return
        }
        viewModelScope.launch {
            val entity = sessionStore.createSystemPrompt(trimmed) ?: return@launch
            applySystemPrompt(entity.id, entity.text)
        }
    }

    @MainThread
    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            sessionStore.renameSession(sessionId, newTitle)
        }
    }

    @MainThread
    fun pinSession(sessionId: String, pinned: Boolean) {
        viewModelScope.launch {
            sessionStore.pinSession(sessionId, pinned)
        }
    }

    @MainThread
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionStore.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                uiState.resetMessages()
            }
        }
    }

    @MainThread
    fun cancelGeneration() {
        runtime.generatingJob?.cancel()
    }

    fun dismissSessionModelHint() {
        _sessionModelHint.value = null
    }

    fun dismissVisionModuleHint() {
        _visionModuleHint.value = null
    }

    /**
     * Start downloading the image module (mmproj) for the currently-hinted
     * vision model. Vision activates the next time this model is loaded (the
     * projector binds at load). No-op if storage isn't configured.
     */
    @MainThread
    fun downloadVisionModule() {
        val model = _visionModuleHint.value ?: return
        _visionModuleHint.value = null
        val storageUri = storageRepository.getStorageUri()
        if (storageUri == null) {
            android.util.Log.w("ConversationViewModel", "downloadVisionModule: storage not configured")
            return
        }
        com.druk.lmplayground.download.DownloadRepository(app)
            .startMmprojDownload(model, storageUri)
    }

    /**
     * Entry point from the attachment menu. Resolves the picked document's
     * metadata, then either starts indexing right away or — when the
     * embedding model isn't on disk yet — raises the download prompt with
     * the document kept pending.
     */
    fun attachDocument(uri: Uri) {
        val rag = ragRepository ?: return
        viewModelScope.launch {
            val doc = withContext(Dispatchers.IO) {
                // Keep read access beyond this picker grant — indexing may
                // start later (after the model download). Best-effort:
                // some providers don't offer persistable grants.
                try {
                    app.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }
                resolveDocumentMeta(uri)
            }
            if (doc == null) {
                _userError.value =
                    app.getString(com.druk.lmplayground.R.string.document_open_failed)
                return@launch
            }
            if (DocumentTextExtractors.forDocument(doc.mimeType, doc.displayName) == null) {
                _userError.value =
                    app.getString(com.druk.lmplayground.R.string.document_unsupported_format)
                return@launch
            }
            if (doc.sizeBytes > DocumentTextExtractor.MAX_FILE_BYTES) {
                _userError.value =
                    app.getString(com.druk.lmplayground.R.string.document_too_large)
                return@launch
            }
            val modelOnDisk = withContext(Dispatchers.IO) { rag.isEmbeddingModelAvailable() }
            if (modelOnDisk) {
                startIndexing(doc)
            } else {
                _embeddingModelPrompt.value = doc
            }
        }
    }

    /** Confirm on the embedding-model dialog: download, then auto-index. */
    @MainThread
    fun confirmEmbeddingModelDownload() {
        val pending = _embeddingModelPrompt.value ?: return
        _embeddingModelPrompt.value = null
        val storageUri = storageRepository.getStorageUri()
        if (storageUri == null) {
            _userError.value =
                app.getString(com.druk.lmplayground.R.string.document_storage_missing)
            return
        }
        pendingDocument = pending
        val downloads = DownloadRepository(app)
        downloads.startDownload(ModelInfoProvider.embeddingModel, storageUri)
        stopObservingEmbeddingDownload()
        embeddingDownloadLiveData = downloads
            .observeModelDownload(ModelInfoProvider.embeddingModel)
            .also { it.observeForever(embeddingDownloadObserver) }
    }

    @MainThread
    fun dismissEmbeddingModelPrompt() {
        _embeddingModelPrompt.value = null
    }

    fun removeDocument(documentId: String) {
        val rag = ragRepository ?: return
        viewModelScope.launch { rag.deleteDocument(documentId) }
    }

    private suspend fun startIndexing(doc: PendingDocument) {
        val rag = ragRepository ?: return
        // The rag_documents row references the session (FK), so a
        // brand-new chat needs its session row created first.
        val sessionId = ensureSessionId(doc.displayName.take(50))
        rag.attachDocument(sessionId, doc.uri, doc.displayName, doc.mimeType, doc.sizeBytes)
    }

    private fun stopObservingEmbeddingDownload() {
        embeddingDownloadLiveData?.removeObserver(embeddingDownloadObserver)
        embeddingDownloadLiveData = null
    }

    private fun resolveDocumentMeta(uri: Uri): PendingDocument? {
        return try {
            val mimeType = app.contentResolver.getType(uri)
            var name: String? = null
            var size = -1L
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
            val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/')
            if (displayName.isNullOrBlank()) null
            else PendingDocument(uri, displayName, mimeType, size)
        } catch (e: Exception) {
            android.util.Log.w("ConversationViewModel", "resolveDocumentMeta failed", e)
            null
        }
    }

    @MainThread
    fun loadModelByFilename(filename: String) {
        _sessionModelHint.value = null
        val modelInfo = ModelInfoProvider.getByFilename(filename)
            ?: ModelInfoProvider.createCustomModelInfo(filename, filename.removeSuffix(".gguf"), 0)
        loadModel(modelInfo)
    }

    fun getReport(): String? = runtime.getReport()

    fun unloadModel() {
        viewModelScope.launch {
            // Tear down native handles only when something is actually
            // loaded — but always clear the user-visible LiveData state
            // below. The failed-load case (e.g. RAM gate refused) leaves
            // _loadedModel + _loadedModelStatus set with null native
            // handles; without this, tapping Unload was a no-op for that
            // path.
            if (runtime.hasNativeHandles()) {
                runtime.unloadNative()
            }

            _loadedModel.postValue(null)
            _loadedModelStatus.postValue(null)
            _isModelReady.postValue(false)
            _supportsThinking.postValue(false)
            _supportsVision.postValue(false)
            _supportsToolCalling.postValue(false)
            _toolEnabledStates.postValue(emptyMap())
        }
    }

    /**
     * Toggle a tool for the currently loaded model. This records a per-model
     * override (which takes precedence over the global default set in
     * Settings → Tools) so changing a tool here only affects this model.
     */
    @MainThread
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        toolRegistry.setToolEnabled(toolName, enabled)
        _loadedModel.value?.filename?.let { filename ->
            storagePreferences.setToolOverride(filename, toolName, enabled)
        }
        val states = _toolEnabledStates.value.orEmpty().toMutableMap()
        states[toolName] = enabled
        _toolEnabledStates.value = states
    }


    fun resetModelList() {
        _models.postValue(emptyList())
    }

    data class RamWarning(
        val modelInfo: ModelInfo,
        val neededRam: String,
        val totalRam: String,
    )

}
