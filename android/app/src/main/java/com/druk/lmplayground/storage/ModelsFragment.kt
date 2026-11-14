package com.druk.lmplayground.storage

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme

class ModelsFragment : Fragment() {

    private val viewModel: StorageViewModel by viewModels()

    // Action deferred across the notification-permission prompt (a download
    // kicked off only after the user answers the permission dialog).
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
            viewModel.requestStorageFolderChange(uri)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadStorageInfo()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            val storageInfo by viewModel.storageInfo.observeAsState()
            val allModels by viewModel.allModels.observeAsState(emptyList())
            val embeddingModel by viewModel.embeddingModel.observeAsState()
            val dictationModel by viewModel.dictationModel.observeAsState()
            val downloadingProgress by viewModel.downloadingModels.observeAsState(emptyMap())
            val snackbarMessage by viewModel.snackbarMessage.observeAsState()
            val pendingMigration by viewModel.pendingMigration.observeAsState()
            val migrationProgress by viewModel.migrationProgress.observeAsState()

            val prevDownloadCount = remember { androidx.compose.runtime.mutableIntStateOf(downloadingProgress.size) }
            LaunchedEffect(downloadingProgress.size) {
                if (downloadingProgress.size < prevDownloadCount.intValue) {
                    viewModel.loadStorageInfo()
                }
                prevDownloadCount.intValue = downloadingProgress.size
            }

            PlaygroundTheme {
                ModelsScreen(
                    storageInfo = storageInfo,
                    allModels = allModels,
                    downloadingModels = downloadingProgress,
                    snackbarMessage = snackbarMessage,
                    pendingMigration = pendingMigration,
                    migrationProgress = migrationProgress,
                    deviceLanguage = viewModel.deviceLanguage,
                    onBackClick = { findNavController().popBackStack() },
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
                                viewModel.requestStorageFolderChange(fallbackUri)
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.folder_picker_unavailable,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    onDeleteModel = { model ->
                        viewModel.deleteModel(model)
                    },
                    onDownloadModel = { model, includeMmproj ->
                        withNotificationPermission { viewModel.downloadModel(model, includeMmproj) }
                    },
                    onDownloadMmproj = { model ->
                        withNotificationPermission { viewModel.downloadMmprojOnly(model) }
                    },
                    onCancelDownload = { model ->
                        viewModel.cancelDownload(model)
                    },
                    onSnackbarDismiss = {
                        viewModel.clearSnackbar()
                    },
                    onConfirmMigration = {
                        viewModel.confirmMigration()
                    },
                    onSkipMigration = {
                        viewModel.skipMigration()
                    },
                    onCancelMigration = {
                        viewModel.cancelMigration()
                    },
                    embeddingModel = embeddingModel,
                    dictationModel = dictationModel
                )
            }
        }
    }
}
