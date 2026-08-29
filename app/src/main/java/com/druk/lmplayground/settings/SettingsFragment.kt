package com.druk.lmplayground.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.druk.llamacpp.InferenceState
import com.druk.lmplayground.App
import com.druk.lmplayground.BuildConfig
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.ModelsContent
import com.druk.lmplayground.storage.StorageDocuments
import com.druk.lmplayground.storage.StorageViewModel
import com.druk.lmplayground.util.rememberHingeWidthDp
import com.druk.lmplayground.theme.PlaygroundTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    // Lazily created via Compose property delegate — only allocated on tablet
    // when the user actually opens a Models/Prompts detail pane, since the
    // ViewModel hits storage as soon as it's constructed.
    private val storageViewModel: StorageViewModel by viewModels()
    private val systemPromptsViewModel: SystemPromptsViewModel by viewModels()
    private val toolsViewModel: ToolsViewModel by viewModels()
    private val soundHapticViewModel: SoundHapticViewModel by viewModels()
    private val advancedViewModel: AdvancedViewModel by viewModels()

    // Action deferred across the notification-permission request (a download
    // started only after the user answers the permission dialog).
    private var pendingDownload: (() -> Unit)? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            storageViewModel.requestStorageFolderChange(uri)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingDownload?.invoke()
        pendingDownload = null
    }

    /** Run [action] (a download) after ensuring the notification permission has been asked for. */
    private fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            action()
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).not()
            && hasAskedNotificationPermission()
        ) {
            action()
        } else {
            pendingDownload = action
            setAskedNotificationPermission()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasAskedNotificationPermission(): Boolean {
        return requireContext().getSharedPreferences("download_prefs", 0)
            .getBoolean("asked_notification_permission", false)
    }

    private fun setAskedNotificationPermission() {
        requireContext().getSharedPreferences("download_prefs", 0)
            .edit().putBoolean("asked_notification_permission", true).apply()
    }

    /**
     * Rapid double-taps on a settings row can fire the second click after the
     * first has already navigated away from nav_settings, at which point the
     * action ID is no longer known to the NavController and navigate() throws
     * IllegalArgumentException. Guard every navigation by verifying the
     * NavController is still at nav_settings before firing the action.
     */
    private fun NavController.navigateFromSettings(actionId: Int) {
        if (currentDestination?.id == R.id.nav_settings) {
            navigate(actionId)
        }
    }

    /**
     * Debug-only: trigger a Process.killProcess(myPid) inside `:llama` so we
     * can verify that crashes don't take the UI down and that the
     * acknowledge → reload recovery path works.
     */
    private fun crashInferenceEngine() {
        val app = activity?.application as? App ?: return
        val client = app.inferenceClient
        val service = (client.state.value as? InferenceState.Connected)?.service
        if (service == null) {
            Toast.makeText(
                requireContext(),
                R.string.debug_crash_engine_not_connected,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        // Run off the main thread — the binder call will throw DeadObject
        // because the process dies mid-transaction; that's expected.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    service.crashForTest()
                } catch (t: Throwable) {
                    Log.d(TAG, "crashForTest returned with expected error: ${t.javaClass.simpleName}")
                }
            }
            Toast.makeText(
                requireContext(),
                R.string.debug_crash_engine_done,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        private const val TAG = "SettingsFragment"
        /** Optional nav argument: which detail to open on entry (e.g. "tools"). */
        const val ARG_OPEN_DETAIL = "open_detail"
        const val DETAIL_TOOLS = "tools"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val configuration = LocalConfiguration.current
                // Mirror the SettingsScreen.twoPane threshold so we only build
                // the inline detail content (and instantiate ViewModels) when
                // it'll actually render.
                val twoPane = configuration.screenWidthDp >= 840

                // Foldable book posture: line the master/detail boundary up
                // with the vertical hinge. Falls back to 360dp on flat
                // devices / horizontal hinges / folded outer display.
                val hingeWidth = rememberHingeWidthDp(requireActivity())
                // 320dp matches the chat sidebar default so both screens have
                // the same pane width on tablets without a hinge.
                val masterWidth = if (hingeWidth != androidx.compose.ui.unit.Dp.Unspecified) {
                    maxOf(320.dp, hingeWidth)
                } else {
                    320.dp
                }

                // Deep link from "What's New": on phone, forward straight to the
                // Tools screen; on tablet, the Tools detail pane opens via
                // initialDetailKey below. Consume the argument so returning to
                // Settings doesn't re-trigger the jump.
                val openDetail = arguments?.getString(ARG_OPEN_DETAIL)
                LaunchedEffect(Unit) {
                    if (openDetail == DETAIL_TOOLS) {
                        arguments?.remove(ARG_OPEN_DETAIL)
                        if (!twoPane) {
                            findNavController().navigateFromSettings(R.id.action_settings_to_tools)
                        }
                    }
                }

                SettingsScreen(
                    onBackClick = { findNavController().popBackStack() },
                    onModelsClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_models)
                    },
                    onSystemPromptsClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_system_prompts)
                    },
                    onLanguageClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_language)
                    },
                    onPrivacyPolicyClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_privacy_policy)
                    },
                    onFaqClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_faq)
                    },
                    onToolsClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_tools)
                    },
                    onSoundHapticClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_sound_haptic)
                    },
                    onAdvancedClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_advanced)
                    },
                    onSendFeedbackClick = {
                        val email = getString(R.string.privacy_policy_contact_email)
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:$email")
                            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
                        }
                        startActivity(intent)
                    },
                    modelsDetailContent = if (twoPane) {
                        { ModelsDetailPane() }
                    } else null,
                    promptsDetailContent = if (twoPane) {
                        { PromptsDetailPane() }
                    } else null,
                    languageDetailContent = if (twoPane) {
                        { LanguageContent() }
                    } else null,
                    toolsDetailContent = if (twoPane) {
                        { ToolsDetailPane() }
                    } else null,
                    soundHapticDetailContent = if (twoPane) {
                        { SoundHapticDetailPane() }
                    } else null,
                    advancedDetailContent = if (twoPane) {
                        { AdvancedDetailPane() }
                    } else null,
                    initialDetailKey = openDetail,
                    masterWidth = masterWidth,
                    appVersion = BuildConfig.VERSION_NAME,
                    // Debug builds expose a button to crash the inference
                    // process so we can verify the recovery flow without
                    // waiting for an organic SIGSEGV.
                    onCrashEngineClick = if (BuildConfig.DEBUG) {
                        { crashInferenceEngine() }
                    } else null,
                )
            }
        }
    }

    /**
     * Embedded Models pane — same observable surface that [ModelsFragment]
     * binds, just rendered without the Scaffold/topBar wrapper.
     */
    @androidx.compose.runtime.Composable
    private fun ModelsDetailPane() {
        val storageInfo by storageViewModel.storageInfo.observeAsState()
        val allModels by storageViewModel.allModels.observeAsState(emptyList())
        val embeddingModel by storageViewModel.embeddingModel.observeAsState()
            val dictationModel by storageViewModel.dictationModel.observeAsState()
        val downloadingProgress by storageViewModel.downloadingModels.observeAsState(emptyMap())
        val snackbarMessage by storageViewModel.snackbarMessage.observeAsState()
        val pendingMigration by storageViewModel.pendingMigration.observeAsState()
        val migrationProgress by storageViewModel.migrationProgress.observeAsState()

        // Refresh storage info when a download completes (mirrors the
        // LaunchedEffect that lives in ModelsFragment).
        val prevDownloadCount = remember { mutableIntStateOf(downloadingProgress.size) }
        LaunchedEffect(downloadingProgress.size) {
            if (downloadingProgress.size < prevDownloadCount.intValue) {
                storageViewModel.loadStorageInfo()
            }
            prevDownloadCount.intValue = downloadingProgress.size
        }
        // Settings doesn't call loadStorageInfo() at fragment-create time
        // (unlike ModelsFragment) because we don't know if Models will be
        // opened. Trigger a fetch when this pane first composes.
        LaunchedEffect(Unit) {
            storageViewModel.loadStorageInfo()
        }

        val snackbarHostState = remember { SnackbarHostState() }
        ModelsContent(
            storageInfo = storageInfo,
            allModels = allModels,
            downloadingModels = downloadingProgress,
            snackbarMessage = snackbarMessage,
            pendingMigration = pendingMigration,
            migrationProgress = migrationProgress,
            deviceLanguage = storageViewModel.deviceLanguage,
            snackbarHostState = snackbarHostState,
            onChangeFolderClick = {
                try {
                    folderPickerLauncher.launch(null)
                } catch (_: ActivityNotFoundException) {
                    // No DocumentsUI on this device — offer the
                    // app-private fallback folder instead.
                    val fallbackUri = StorageDocuments.appStorageFallbackUri(requireContext())
                    if (fallbackUri != null) {
                        Toast.makeText(
                            requireContext(),
                            R.string.storage_fallback_used,
                            Toast.LENGTH_LONG,
                        ).show()
                        storageViewModel.requestStorageFolderChange(fallbackUri)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            R.string.folder_picker_unavailable,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onDeleteModel = { model -> storageViewModel.deleteModel(model) },
            onDownloadModel = { model, includeMmproj ->
                withNotificationPermission { storageViewModel.downloadModel(model, includeMmproj) }
            },
            onDownloadMmproj = { model ->
                withNotificationPermission { storageViewModel.downloadMmprojOnly(model) }
            },
            onCancelDownload = { model -> storageViewModel.cancelDownload(model) },
            onSnackbarDismiss = { storageViewModel.clearSnackbar() },
            onConfirmMigration = { storageViewModel.confirmMigration() },
            onSkipMigration = { storageViewModel.skipMigration() },
            onCancelMigration = { storageViewModel.cancelMigration() },
            embeddingModel = embeddingModel,
            dictationModel = dictationModel,
        )
    }

    /**
     * Embedded Tools pane — per-tool global default toggles, rendered without
     * the Scaffold/topBar wrapper that [ToolsFragment] adds on phone.
     */
    @androidx.compose.runtime.Composable
    private fun ToolsDetailPane() {
        val enabled by toolsViewModel.enabled.collectAsStateWithLifecycle()
        ToolsContent(
            tools = toolsViewModel.tools,
            enabledStates = enabled,
            onToolEnabledChanged = { name, value -> toolsViewModel.setEnabled(name, value) },
        )
    }

    /**
     * Embedded Sound and Haptic pane — completion-sound + generation-haptic
     * toggles, rendered without the Scaffold/topBar that [SoundHapticFragment]
     * adds on phone.
     */
    @androidx.compose.runtime.Composable
    private fun SoundHapticDetailPane() {
        val soundEnabled by soundHapticViewModel.soundEnabled.collectAsStateWithLifecycle()
        val hapticEnabled by soundHapticViewModel.hapticEnabled.collectAsStateWithLifecycle()
        SoundHapticContent(
            soundEnabled = soundEnabled,
            hapticEnabled = hapticEnabled,
            onSoundChanged = { soundHapticViewModel.setSoundEnabled(it) },
            onHapticChanged = { soundHapticViewModel.setHapticEnabled(it) },
        )
    }

    /**
     * Embedded Advanced pane — memory/performance toggles, rendered without
     * the Scaffold/topBar that [AdvancedFragment] adds on phone.
     */
    @androidx.compose.runtime.Composable
    private fun AdvancedDetailPane() {
        val repackEnabled by advancedViewModel.repackEnabled.collectAsStateWithLifecycle()
        AdvancedContent(
            repackEnabled = repackEnabled,
            onRepackEnabledChanged = { advancedViewModel.setRepackEnabled(it) },
        )
    }

    /**
     * Embedded System Prompts pane — list/grid + bottom-sheet editor.
     */
    @androidx.compose.runtime.Composable
    private fun PromptsDetailPane() {
        val prompts by systemPromptsViewModel.prompts.observeAsState(emptyList())
        var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }

        SystemPromptsContent(
            prompts = prompts,
            onSelect = { editorTarget = EditorTarget.Edit(it) },
            onNewPrompt = { editorTarget = EditorTarget.New },
        )

        EditorBottomSheet(
            target = editorTarget,
            onAdd = { text ->
                systemPromptsViewModel.addPrompt(text)
                editorTarget = null
            },
            onUpdate = { id, text ->
                systemPromptsViewModel.updatePrompt(id, text)
                editorTarget = null
            },
            onDelete = { id ->
                systemPromptsViewModel.deletePrompt(id)
                editorTarget = null
            },
            onDismiss = { editorTarget = null },
        )
    }
}
