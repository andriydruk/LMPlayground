package com.druk.lmplayground.conversation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.settings.SettingsFragment
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.SelectModelDialog
import com.druk.lmplayground.storage.StorageViewModel
import com.druk.lmplayground.theme.PlaygroundTheme
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.druk.lmplayground.dictation.DictationState
import kotlinx.coroutines.launch

class ConversationFragment : Fragment() {

    private val viewModel: ConversationViewModel by viewModels()
    private val storageViewModel: StorageViewModel by viewModels()

    private var attachedImageUri = mutableStateOf<Uri?>(null)

    // Destination of an in-flight camera capture. Kept as both a Uri (handed to
    // the camera app / set as the attachment) and a File (so we can check
    // length() / delete() the temp without re-parsing the FileProvider Uri).
    private var pendingCaptureUri: Uri? = null
    private var pendingCaptureFile: File? = null

    private val cameraAvailable by lazy {
        requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // The finger left the button while the system dialog was up, so
            // there is nothing to record for — the next press dictates.
            Toast.makeText(
                requireContext(),
                R.string.dictation_permission_granted,
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            Toast.makeText(
                requireContext(),
                R.string.dictation_permission_denied,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // Picking a library image supersedes any unsent camera capture — drop its
        // temp so the cache never holds more than the current attachment's file.
        if (uri != null) clearPendingCapture()
        attachedImageUri.value = uri
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCaptureFile
        // Gate on a non-empty file: some OEM camera apps report success but write
        // nothing, and a 0-byte JPEG would silently degrade the turn to text-only.
        if (success && file != null && file.length() > 0L) {
            // Keep pendingCaptureFile referenced so it can be pruned after the
            // turn is sent / cleared / superseded (see clearPendingCapture).
            attachedImageUri.value = pendingCaptureUri
        } else {
            clearPendingCapture()
        }
    }

    /** Delete the in-flight/last camera temp (if any) and forget it. */
    private fun clearPendingCapture() {
        runCatching { pendingCaptureFile?.delete() }
        pendingCaptureFile = null
        pendingCaptureUri = null
    }

    // Document (RAG) attachment: OpenDocument (not GetContent) so the read
    // grant can be persisted — indexing may start after a model download,
    // and the chip reopens the original across app restarts.
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.attachDocument(uri)
    }

    /**
     * Reopen an attached document's original file in the user's viewer.
     * The stored grant is persistable but the file may be gone (deleted,
     * provider removed) — every failure funnels into one toast.
     */
    private fun openAttachedDocument(doc: com.druk.lmplayground.data.RagDocumentEntity) {
        val uri = doc.sourceUri?.let(Uri::parse)
        if (uri == null) {
            Toast.makeText(requireContext(), R.string.document_source_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, doc.mimeType ?: "*/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // ActivityNotFound (no viewer) or SecurityException (grant/file
            // revoked) — same user-facing outcome.
            Toast.makeText(requireContext(), R.string.document_source_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Capture a photo via the system camera app into a temp file under
     * cacheDir/chat_images, then route the result through the same attachment
     * path as the photo picker. No CAMERA permission is needed — the camera app
     * does the capture and we only receive the written image.
     */
    override fun onPause() {
        super.onPause()
        // Backgrounding mid-hold means no release will arrive, and Android mutes
        // the microphone for a background app anyway — end dictation rather than
        // leave it recording silence until the duration cap.
        viewModel.dictation.onMicReleased()
    }

    /**
     * Begin dictation, asking for the microphone first if we don't have it.
     * The permission callback starts the recording on grant.
     */
    private fun startDictation() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.dictation.startListening()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchCamera() {
        // Drop any temp from a prior capture that was never sent.
        clearPendingCapture()
        val uri = try {
            val dir = File(requireContext().cacheDir, "chat_images").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file
            )
            pendingCaptureFile = file
            pendingCaptureUri = uri
            uri
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            takePictureLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            clearPendingCapture()
            Toast.makeText(requireContext(), R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The camera app is memory-heavy and frequently triggers our process
        // death while it is foregrounded. Persist the pending capture destination
        // so the result callback can still map back to the temp file on return.
        pendingCaptureFile?.let { outState.putString(KEY_PENDING_CAPTURE_FILE, it.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        // After returning from the Tools screen (which marks the flag), hide the
        // "Set up tools" button — re-checking here instead of on tap avoids the
        // button visibly disappearing under the user's finger.
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)

        // Recover a capture that was in flight when the process was killed.
        savedInstanceState?.getString(KEY_PENDING_CAPTURE_FILE)?.let { path ->
            val file = File(path)
            pendingCaptureFile = file
            pendingCaptureUri = runCatching {
                FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file
                )
            }.getOrNull()
        }

        setContent {

            val messages = viewModel.uiState.messages
            val isGenerating by viewModel.isGenerating.observeAsState()
            val progress by viewModel.modelLoadingProgress.observeAsState(0f)
            val modelInfo by viewModel.loadedModel.observeAsState()
            val modelStatus by viewModel.loadedModelStatus.observeAsState()
            val supportsThinking by viewModel.supportsThinking.observeAsState(false)
            // The chat switch follows measured behaviour: hidden for models that
            // always reason, since "off" would be ignored. The params sheet still
            // uses supportsThinking, because the thinking budget applies to them.
            val thinkingToggleable by viewModel.thinkingToggleable.observeAsState(false)
            val supportsToolCalling by viewModel.supportsToolCalling.observeAsState(false)
            val toolEnabledStates by viewModel.toolEnabledStates.observeAsState(emptyMap())
            val thinkingEnabled by viewModel.thinkingEnabled.observeAsState(false)
            val supportsVision by viewModel.supportsVision.observeAsState(false)
            val currentImageUri by attachedImageUri
            val isModelReady by viewModel.isModelReady.observeAsState(false)
            val models by viewModel.models.observeAsState(emptyList())
            val sessions by viewModel.sessions.observeAsState(emptyList())
            val currentSessionId by viewModel.currentSessionId.observeAsState()
            val generationParams by viewModel.generationParams.observeAsState(GenerationParams())
            val maxContextSize by viewModel.maxContextSize.observeAsState(4096)
            val sessionModelHint by viewModel.sessionModelHint.observeAsState()
            val visionModuleHint by viewModel.visionModuleHint.observeAsState()
            val systemPrompt by viewModel.systemPrompt.observeAsState("")
            val systemPromptId by viewModel.systemPromptId.observeAsState()
            val recentSystemPrompts by viewModel.recentSystemPrompts.observeAsState(emptyList())
            val userError by viewModel.userError.observeAsState()
            val pendingRamWarning by viewModel.pendingRamWarning.observeAsState()
            val modelLoadError by viewModel.modelLoadError.observeAsState()
            val sessionDocuments by viewModel.sessionDocuments.observeAsState(emptyList())
            val embeddingModelPrompt by viewModel.embeddingModelPrompt.observeAsState()
            var documentPendingRemoval by remember {
                mutableStateOf<com.druk.lmplayground.data.RagDocumentEntity?>(null)
            }
            var showParamsSheet by remember { mutableStateOf(false) }

            val dictationState by viewModel.dictation.state.observeAsState(DictationState.Idle)
            val dictationTranscript by viewModel.dictation.transcript.observeAsState()
            val dictationError by viewModel.dictation.error.observeAsState()
            val dictationModelReady by viewModel.dictation.isModelReady.observeAsState()
            val dictationDownload by viewModel.dictation.downloadProgress.observeAsState()
            var showDictationDownloadPrompt by remember { mutableStateOf(false) }

            // Surface transient ViewModel errors (e.g. message-too-large)
            // as Toasts. The ViewModel can't show UI directly, so we
            // observe a one-shot LiveData and clear it after consumption.
            val toastContext = LocalContext.current
            LaunchedEffect(userError) {
                val msg = userError ?: return@LaunchedEffect
                Toast.makeText(toastContext, msg, Toast.LENGTH_LONG).show()
                viewModel.consumeUserError()
            }

            LaunchedEffect(dictationError) {
                val msg = dictationError ?: return@LaunchedEffect
                Toast.makeText(toastContext, msg, Toast.LENGTH_SHORT).show()
                viewModel.dictation.consumeError()
            }

            // The model may have been downloaded (or deleted) since last time.
            LaunchedEffect(Unit) { viewModel.dictation.refreshModelAvailability() }

            // Storage configuration state
            val isStorageConfigured by storageViewModel.isStorageConfigured.observeAsState(true)
            var showStorageSetupDialog by remember { mutableStateOf(false) }

            // Migration state
            val pendingMigration by storageViewModel.pendingMigration.observeAsState()
            val migrationProgress by storageViewModel.migrationProgress.observeAsState()

            PlaygroundTheme {

                val scrollState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val drawerState = rememberDrawerState(DrawerValue.Closed)

                // "Follow the latest" flag for the message list. Hoisted here so
                // the jump-to-bottom button (rendered at the root, above all
                // chrome) can re-arm it. New messages re-enable following.
                var piningBottom by remember { mutableStateOf(false) }
                LaunchedEffect(messages.size) { piningBottom = true }
                val jumpEnabled by remember {
                    derivedStateOf { scrollState.canScrollForward }
                }

                // Restore the pre-frost behavior: the top bar is flush (no
                // tint/elevation) while the list is at the very top, and only
                // frosts once content scrolls underneath it. Drive the haze
                // effect's alpha from this so it fades in like the old M3
                // container-color transition.
                val isScrolled by remember {
                    derivedStateOf {
                        scrollState.firstVisibleItemIndex > 0 ||
                            scrollState.firstVisibleItemScrollOffset > 0
                    }
                }
                val topBarFrost by animateFloatAsState(
                    targetValue = if (isScrolled) 1f else 0f,
                    label = "topBarFrost"
                )

                val colorScheme = MaterialTheme.colorScheme

                // Frosted-glass chrome: the message list scrolls full-bleed
                // behind a translucent, blurred top bar and input dock. The
                // shared HazeState links the source (the list) to the overlays.
                // Real blur needs API 32+; on older devices Haze draws the
                // fallbackTint scrim instead — kept near-opaque so the bars
                // still read as solid chrome rather than washed-out content.
                val hazeState = rememberHazeState()
                val hazeStyle = HazeStyle(
                    // The color sitting behind the blurred content (the chat
                    // background painted on the root Box), so Haze composites the
                    // gaps between message bubbles correctly.
                    backgroundColor = colorScheme.background,
                    // Tint with a lighter "elevated surface" tone at fairly high
                    // opacity. A low-alpha surface tint let the dark chat bleed
                    // through and read darker than the old solid bars; this keeps
                    // the chrome clearly lighter than the content (so it reads as
                    // raised) while still showing the blur through it.
                    tints = listOf(HazeTint(colorScheme.surfaceContainerHigh.copy(alpha = 0.78f))),
                    blurRadius = 24.dp,
                    noiseFactor = 0f,
                    fallbackTint = HazeTint(colorScheme.surfaceContainerHigh.copy(alpha = 0.97f))
                )

                // Only show the permanent sidebar when there's enough actual
                // window width to comfortably fit master (320dp) + detail.
                // At Medium (600-839dp), e.g. 7" tablet portrait or split-screen,
                // the chat pane would be squeezed to ~280dp — worse than just
                // hiding the sidebar behind a hamburger.
                val widthSize = calculateWindowSizeClass(requireActivity()).widthSizeClass
                val showPermanent = widthSize == WindowWidthSizeClass.Expanded

                // Foldable book posture: if a vertical hinge is in the window,
                // align the sidebar boundary with it so the seam between
                // master/detail lands on the crease instead of arbitrarily
                // bisecting one display. Fall back to 320dp on flat devices.
                val hingeWidth = com.druk.lmplayground.util.rememberHingeWidthDp(requireActivity())
                val sidebarWidth = if (hingeWidth != androidx.compose.ui.unit.Dp.Unspecified) {
                    // Clamp the lower bound so the master pane doesn't shrink
                    // below the comfortable 320dp the rest of the UI expects.
                    maxOf(320.dp, hingeWidth)
                } else {
                    320.dp
                }

                // The bar paints no container of its own — the frosted blur
                // behind it provides the separation, so both the resting and
                // scrolled container colors are transparent.
                val topBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
                val inputFocusRequester = remember { FocusRequester() }

                // Measured heights of the floating top bar and bottom dock,
                // fed back into the list as content padding (so the first/last
                // messages clear the chrome) and as the float offset for the
                // jump-to-bottom / model-hint buttons. The bottom value tracks
                // the dock as it grows with multi-line text and the keyboard.
                val density = LocalDensity.current
                var topBarHeightPx by remember { mutableIntStateOf(0) }
                var bottomBarHeightPx by remember { mutableIntStateOf(0) }
                val topBarHeight = with(density) { topBarHeightPx.toDp() }
                val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
                var modelReport by remember { mutableStateOf<String?>(null) }

                // When model finishes loading, jump to bottom and open keyboard
                LaunchedEffect(isModelReady) {
                    if (isModelReady) {
                        val lastIndex = scrollState.layoutInfo.totalItemsCount - 1
                        if (lastIndex >= 0) {
                            scrollState.animateScrollToItem(lastIndex)
                        }
                        inputFocusRequester.requestFocus()
                    }
                }

                // Check if storage is configured on first launch
                LaunchedEffect(Unit) {
                    storageViewModel.checkStorageConfigured()
                }

                // Show setup dialog if storage not configured
                LaunchedEffect(isStorageConfigured) {
                    if (!isStorageConfigured) {
                        showStorageSetupDialog = true
                    }
                }

                // Storage Setup Dialog
                if (showStorageSetupDialog && !isStorageConfigured && pendingMigration == null) {
                    AlertDialog(
                        onDismissRequest = { /* Cannot dismiss - must choose folder */ },
                        title = { Text(stringResource(R.string.choose_storage_folder)) },
                        text = {
                            Text(stringResource(R.string.choose_storage_folder_message))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    (activity as? MainActivity)?.launchFolderPicker { uri ->
                                        if (uri != null) {
                                            storageViewModel.requestStorageFolderChange(uri)
                                            showStorageSetupDialog = false
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.choose_folder))
                            }
                        }
                    )
                }

                // Migration confirmation dialog
                pendingMigration?.let { migration ->
                    val totalSize = migration.modelsToMigrate.sumOf { it.sizeBytes }
                    val sizeFormatted = android.text.format.Formatter.formatFileSize(context, totalSize)

                    AlertDialog(
                        onDismissRequest = { storageViewModel.cancelMigration() },
                        title = { Text(stringResource(R.string.migrate_models_title)) },
                        text = {
                            Column {
                                Text(
                                    if (migration.isFromDownloads) {
                                        stringResource(R.string.migrate_models_from_downloads, migration.modelsToMigrate.size, sizeFormatted)
                                    } else {
                                        stringResource(R.string.migrate_models_message, migration.modelsToMigrate.size, sizeFormatted)
                                    }
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { storageViewModel.confirmMigration() }) {
                                Text(stringResource(R.string.migrate))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { storageViewModel.skipMigration() }) {
                                Text(stringResource(R.string.skip))
                            }
                        }
                    )
                }

                // Migration progress dialog
                migrationProgress?.let { progress ->
                    AlertDialog(
                        onDismissRequest = { /* Cannot dismiss while migrating */ },
                        title = { Text(stringResource(R.string.migrating_models)) },
                        text = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(stringResource(R.string.migration_progress, progress.currentIndex, progress.totalCount))
                                Text(
                                    text = progress.currentModel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = { }
                    )
                }

                // Document Q&A needs the embedding model on disk — offer the
                // one-time download; the picked document stays pending and
                // indexes automatically once the download lands.
                embeddingModelPrompt?.let {
                    val embeddingModel = ModelInfoProvider.embeddingModel
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissEmbeddingModelPrompt() },
                        title = { Text(stringResource(R.string.embedding_model_dialog_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.embedding_model_dialog_message,
                                    embeddingModel.name,
                                    embeddingModel.description.substringAfterLast("· "),
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmEmbeddingModelDownload() }) {
                                Text(stringResource(R.string.download_model))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissEmbeddingModelPrompt() }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                // Dictation needs its speech model on disk — offer the one-time
                // download the first time the microphone is tapped.
                if (showDictationDownloadPrompt) {
                    val dictationModel = viewModel.dictation.modelInfo
                    AlertDialog(
                        onDismissRequest = { showDictationDownloadPrompt = false },
                        title = { Text(stringResource(R.string.dictation_model_dialog_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.dictation_model_dialog_message,
                                    dictationModel.name,
                                    dictationModel.description.substringAfterLast("· "),
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDictationDownloadPrompt = false
                                viewModel.dictation.downloadModel()
                                Toast.makeText(
                                    requireContext(),
                                    R.string.dictation_model_downloading,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }) {
                                Text(stringResource(R.string.download_model))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDictationDownloadPrompt = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                documentPendingRemoval?.let { doc ->
                    AlertDialog(
                        onDismissRequest = { documentPendingRemoval = null },
                        title = { Text(stringResource(R.string.remove_document_title)) },
                        text = {
                            Text(stringResource(R.string.remove_document_message, doc.displayName))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.removeDocument(doc.id)
                                    documentPendingRemoval = null
                                }
                            ) {
                                Text(stringResource(R.string.remove))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { documentPendingRemoval = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                pendingRamWarning?.let { warning ->
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissRamWarning() },
                        title = { Text(stringResource(R.string.low_ram_warning_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.low_ram_warning_message,
                                    warning.neededRam,
                                    warning.totalRam,
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmLoadDespiteRamWarning() }) {
                                Text(stringResource(R.string.load_anyway))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissRamWarning() }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                modelLoadError?.let { message ->
                    AlertDialog(
                        onDismissRequest = { viewModel.consumeModelLoadError() },
                        title = { Text(stringResource(R.string.model_load_failed_title)) },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = { viewModel.consumeModelLoadError() }) {
                                Text(stringResource(R.string.model_load_failed_dismiss))
                            }
                        }
                    )
                }

                if (showParamsSheet) {
                    GenerationParamsSheet(
                        params = generationParams,
                        maxContextSize = maxContextSize,
                        supportsThinking = supportsThinking,
                        supportsToolCalling = supportsToolCalling,
                        tools = viewModel.toolRegistry.getAllTools(),
                        toolEnabledStates = toolEnabledStates,
                        onToolEnabledChanged = { name, enabled -> viewModel.setToolEnabled(name, enabled) },
                        systemPrompt = systemPrompt,
                        systemPromptId = systemPromptId,
                        recentSystemPrompts = recentSystemPrompts,
                        canUpdateLinkedPrompt = systemPromptId != null,
                        onParamsChanged = { viewModel.updateGenerationParams(it) },
                        onUpdateLinkedPrompt = { viewModel.updateLinkedSystemPrompt(it) },
                        onSaveAsNewPrompt = { viewModel.createAndApplySystemPrompt(it) },
                        onClearSystemPrompt = { viewModel.clearSystemPrompt() },
                        onApplyPrompt = { viewModel.applySystemPrompt(it.id, it.text) },
                        onDismiss = { showParamsSheet = false }
                    )
                }

                val mainContent: @Composable () -> Unit = {
                    // Picker sits just above the composer. Visible only when:
                    //   - model is ready
                    //   - chat is empty
                    //   - library has entries
                    //   - the session has no prompt selected yet
                    val pickerVisible = isModelReady &&
                        messages.isEmpty() &&
                        recentSystemPrompts.isNotEmpty() &&
                        systemPrompt.isEmpty()

                    // Surface restores what the old Scaffold provided: it paints
                    // the chat background AND propagates the correct content
                    // color (onBackground) to descendants. A bare Box leaves text
                    // at the default LocalContentColor (black). It's also the base
                    // the frosted bars blur over.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = colorScheme.background
                    ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // ---- Background (haze source): full-bleed content the
                        // frosted bars blur. Content padding keeps the first and
                        // last messages clear of the floating chrome.
                        if (modelInfo == null && messages.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .hazeSource(hazeState)
                                    .padding(top = topBarHeight, bottom = bottomBarHeight),
                                contentAlignment = Alignment.Center
                            ) {
                                WhatsNewText()
                            }
                        } else {
                            Messages(
                                messages = messages,
                                modifier = Modifier.fillMaxSize(),
                                scrollState = scrollState,
                                contentPadding = PaddingValues(
                                    top = topBarHeight,
                                    bottom = bottomBarHeight
                                ),
                                bottomInset = bottomBarHeight,
                                hazeState = hazeState,
                                hazeStyle = hazeStyle,
                                isGenerating = isGenerating == true,
                                piningBottom = piningBottom,
                                onPiningBottomChange = { piningBottom = it },
                                sessionModelHint = sessionModelHint,
                                onSessionModelHintClick = { filename ->
                                    viewModel.loadModelByFilename(filename)
                                },
                                onSessionModelHintDismiss = {
                                    viewModel.dismissSessionModelHint()
                                },
                                visionModuleHint = visionModuleHint?.name,
                                onVisionModuleHintClick = {
                                    viewModel.downloadVisionModule()
                                    Toast.makeText(
                                        requireContext(),
                                        R.string.vision_module_downloading,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                                onTokenCountClicked = {
                                    modelReport = viewModel.getReport()
                                },
                                onImageClick = { uri ->
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "image/*")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        startActivity(intent)
                                    } catch (_: Exception) { }
                                }
                            )
                        }

                        // ---- Frosted top bar, floating over the content ----
                        ConversationBar(
                            modelInfo = modelInfo,
                            modelStatus = modelStatus,
                            showNavIcon = !showPermanent,
                            compact = showPermanent,
                            onNavIconPressed = {
                                scope.launch { drawerState.open() }
                            },
                            colors = topBarColors,
                            onModelNamePressed = {
                                viewModel.loadModelList()
                            },
                            onNewSessionPressed = {
                                viewModel.newConversation()
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .onSizeChanged { topBarHeightPx = it.height }
                                // Fade the frost in as content scrolls under the
                                // bar; flush (no tint/elevation) at the top.
                                .hazeEffect(hazeState, hazeStyle) { alpha = topBarFrost }
                                .drawWithContent {
                                    drawContent()
                                    // Model-loading progress hairline along the
                                    // bar's bottom edge.
                                    val strokeWidth = 2.dp.toPx()
                                    val y = size.height - strokeWidth / 2f
                                    drawLine(
                                        colorScheme.primary,
                                        start = Offset(0f, y),
                                        end = Offset(size.width * progress, y),
                                        strokeWidth = strokeWidth
                                    )
                                }
                        )

                        // ---- Bottom dock (picker + frosted input) ----
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .onSizeChanged { bottomBarHeightPx = it.height }
                        ) {
                            // On pick: the picker row handles its own flight
                            // animation per-card, then fires onPick — which flips
                            // the visibility gate. Exit uses ExitTransition.None
                            // so the row disappears immediately after the card
                            // finishes flying (no double-animation).
                            androidx.compose.animation.AnimatedVisibility(
                                visible = pickerVisible,
                                enter = androidx.compose.animation.fadeIn(
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                ) + androidx.compose.animation.slideInVertically(
                                    animationSpec = androidx.compose.animation.core.tween(220),
                                    initialOffsetY = { it / 2 }
                                ),
                                exit = androidx.compose.animation.ExitTransition.None
                            ) {
                                SystemPromptPickerRow(
                                    prompts = recentSystemPrompts,
                                    selectedText = systemPrompt,
                                    onPick = { viewModel.applySystemPrompt(it.id, it.text) },
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            if (sessionDocuments.isNotEmpty()) {
                                DocumentChipsRow(
                                    documents = sessionDocuments,
                                    onRemove = { documentPendingRemoval = it },
                                    onOpen = { doc -> openAttachedDocument(doc) },
                                    modifier = Modifier.padding(bottom = 4.dp),
                                    hazeState = hazeState,
                                    hazeStyle = hazeStyle
                                )
                            }
                            UserInput(
                                integrateWithSurface = showPermanent,
                                hazeState = hazeState,
                                hazeStyle = hazeStyle,
                                modifier = Modifier
                                    .navigationBarsPadding()
                                    .imePadding(),
                                focusRequester = inputFocusRequester,
                                status = if (modelInfo == null || !isModelReady)
                                    UserInputStatus.NOT_LOADED
                                else if (isGenerating == true)
                                    UserInputStatus.GENERATING
                                else
                                    UserInputStatus.IDLE,
                                supportsThinking = thinkingToggleable,
                                thinkingEnabled = thinkingEnabled,
                                onThinkingToggle = { viewModel.toggleThinking() },
                                supportsVision = supportsVision,
                                cameraAvailable = cameraAvailable,
                                attachedImageUri = currentImageUri,
                                onAttachImage = {
                                    pickMediaLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onTakePhoto = { launchCamera() },
                                onAttachDocument = {
                                    try {
                                        openDocumentLauncher.launch(
                                            com.druk.lmplayground.rag.DocumentTextExtractors.OPENABLE_MIME_TYPES
                                        )
                                    } catch (e: ActivityNotFoundException) {
                                        Toast.makeText(
                                            requireContext(),
                                            R.string.document_open_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onClearImage = {
                                    attachedImageUri.value = null
                                    clearPendingCapture()
                                },
                                onSwipeUp = {
                                    if (isModelReady) showParamsSheet = true
                                },
                                dictationState = dictationState,
                                dictationDownloadProgress = dictationDownload,
                                onMicPressed = {
                                    // Already fetching: the ring around the mic
                                    // is the answer, not the dialog again.
                                    if (dictationDownload != null) return@UserInput
                                    viewModel.dictation.onMicPressed()
                                    // Check on every press rather than trusting
                                    // the cached flag: the model may have
                                    // finished downloading (or been deleted)
                                    // since the screen was composed.
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        if (viewModel.dictation.isModelAvailable()) {
                                            startDictation()
                                        } else {
                                            showDictationDownloadPrompt = true
                                        }
                                    }
                                },
                                // Releasing is the stop. Harmless when the press
                                // never started dictation (model missing, or
                                // permission just requested).
                                onMicReleased = { viewModel.dictation.onMicReleased() },
                                pendingTranscript = dictationTranscript,
                                onTranscriptConsumed = { viewModel.dictation.consumeTranscript() },
                                onMessageSent = { content ->
                                    val imageUri = attachedImageUri.value
                                    attachedImageUri.value = null
                                    viewModel.addMessage(
                                        Message("User", content),
                                        imageUri = imageUri
                                    )
                                    // addMessage has already copied the capture into
                                    // filesDir (synchronous persist); drop the cache temp.
                                    clearPendingCapture()
                                },
                                onCancelClicked = {
                                    viewModel.cancelGeneration()
                                },
                                // let this element handle the padding so that the
                                // frost extends behind the navigation bar
                                resetScroll = {
                                    scope.launch {
                                        val lastIndex = scrollState.layoutInfo.totalItemsCount - 1
                                        if (lastIndex >= 0) {
                                            scrollState.animateScrollToItem(lastIndex)
                                        }
                                    }
                                }
                            )
                        }

                        // Jump-to-bottom: rendered last so it sits above all the
                        // chrome with both a frosted background and a real
                        // elevation shadow. Hidden while the model hint is up.
                        JumpToBottom(
                            enabled = jumpEnabled &&
                                sessionModelHint == null &&
                                (modelInfo != null || messages.isNotEmpty()),
                            onClicked = {
                                scope.launch {
                                    val last = scrollState.layoutInfo.totalItemsCount - 1
                                    if (last >= 0) scrollState.animateScrollToItem(last)
                                }
                                piningBottom = true
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = bottomBarHeight),
                            hazeState = hazeState,
                            hazeStyle = hazeStyle
                        )

                        // Frosted model picker — kept inside the chat Box (and
                        // drawn last) so its glass blurs the message list behind
                        // it. A separate Dialog window couldn't be blurred by
                        // Haze. The overlay already fills the chat pane, so no
                        // chatPaneStartOffset is needed.
                        if (models.isNotEmpty() && models.any { it.isDownloaded }) {
                            SelectModelDialog(
                                models = models,
                                isModelLoaded = modelInfo != null,
                                hazeState = hazeState,
                                hazeStyle = hazeStyle,
                                onLoadModel = { model ->
                                    viewModel.loadModel(model)
                                },
                                onUnloadModel = {
                                    viewModel.unloadModel()
                                },
                                onGenerationParams = {
                                    showParamsSheet = true
                                },
                                onBrowseModels = {
                                    // Guard against NavController throwing IllegalArgumentException
                                    // when the user double-taps or another navigation moved us
                                    // off nav_home before this callback fired.
                                    val nav = findNavController()
                                    if (nav.currentDestination?.id == R.id.nav_home) {
                                        nav.navigate(R.id.action_home_to_models)
                                    }
                                },
                                onDismissRequest = {
                                    viewModel.resetModelList()
                                }
                            )
                        }
                    }
                    }

                    // Models exist but none are downloaded → jump straight to the
                    // Models screen. (When some are downloaded, the frosted picker
                    // overlay inside the chat Box above handles it.) The
                    // session-info dialog stays a plain platform dialog.
                    if (models.isNotEmpty()) {
                        if (models.none { it.isDownloaded }) {
                            // No downloaded models - go directly to Models screen
                            LaunchedEffect(Unit) {
                                viewModel.resetModelList()
                                if (findNavController().currentDestination?.id == R.id.nav_home) {
                                    findNavController().navigate(R.id.action_home_to_models)
                                }
                            }
                        }
                    } else if (modelReport != null) {
                        AlertDialog(
                            onDismissRequest = {
                                modelReport = null
                            },
                            title = {
                                Text(text = stringResource(R.string.session_info))
                            },
                            text = {
                                Text(
                                    text = modelReport!!,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { modelReport = null }) {
                                    Text(text = stringResource(R.string.close))
                                }
                            }
                        )
                    }
                }

                if (showPermanent) {
                    PermanentNavigationDrawer(
                        drawerContent = {
                            PermanentSessionList(
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                width = sidebarWidth,
                                onSessionSelected = { sessionId ->
                                    viewModel.loadSession(sessionId)
                                },
                                onDeleteSession = { sessionId ->
                                    viewModel.deleteSession(sessionId)
                                },
                                onRenameSession = { sessionId, newTitle ->
                                    viewModel.renameSession(sessionId, newTitle)
                                },
                                onPinSession = { sessionId, pinned ->
                                    viewModel.pinSession(sessionId, pinned)
                                },
                                onSettingsClicked = {
                                    if (findNavController().currentDestination?.id == R.id.nav_home) {
                                        findNavController().navigate(R.id.action_home_to_settings)
                                    }
                                }
                            )
                        }
                    ) { mainContent() }
                } else {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            SessionListDrawer(
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                onSessionSelected = { sessionId ->
                                    viewModel.loadSession(sessionId)
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteSession = { sessionId ->
                                    viewModel.deleteSession(sessionId)
                                },
                                onRenameSession = { sessionId, newTitle ->
                                    viewModel.renameSession(sessionId, newTitle)
                                },
                                onPinSession = { sessionId, pinned ->
                                    viewModel.pinSession(sessionId, pinned)
                                },
                                onSettingsClicked = {
                                    scope.launch {
                                        drawerState.close()
                                        if (findNavController().currentDestination?.id == R.id.nav_home) {
                                            findNavController().navigate(R.id.action_home_to_settings)
                                        }
                                    }
                                }
                            )
                        }
                    ) { mainContent() }
                }

            }
        }
    }

    companion object {
        private const val KEY_PENDING_CAPTURE_FILE = "pending_capture_file"
    }
}
