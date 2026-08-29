@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.storage

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.druk.lmplayground.R
import com.druk.lmplayground.models.ModelCapabilityIcons
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.models.releaseDateLabel
import com.druk.lmplayground.models.supportsLanguage
import com.druk.lmplayground.theme.PlaygroundTheme

/**
 * Standalone Models screen used by [ModelsFragment] when reached via
 * navigation on phone. Wraps [ModelsContent] in a Scaffold + back button. The
 * Content composable on its own is what the tablet Settings detail pane
 * embeds.
 */
@Composable
fun ModelsScreen(
    storageInfo: StorageInfo?,
    allModels: List<ModelWithStatus>,
    downloadingModels: Map<String, DownloadProgress>,
    snackbarMessage: String?,
    pendingMigration: MigrationState?,
    migrationProgress: MigrationProgress?,
    deviceLanguage: String,
    onBackClick: () -> Unit,
    onChangeFolderClick: () -> Unit,
    onDeleteModel: (ModelInfo) -> Unit,
    onDownloadModel: (ModelInfo, Boolean) -> Unit,
    onDownloadMmproj: (ModelInfo) -> Unit,
    onCancelDownload: (ModelInfo) -> Unit,
    onSnackbarDismiss: () -> Unit,
    onConfirmMigration: () -> Unit,
    onSkipMigration: () -> Unit,
    onCancelMigration: () -> Unit,
    embeddingModel: ModelWithStatus? = null,
    dictationModel: ModelWithStatus? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        ModelsContent(
            storageInfo = storageInfo,
            allModels = allModels,
            downloadingModels = downloadingModels,
            snackbarMessage = snackbarMessage,
            pendingMigration = pendingMigration,
            migrationProgress = migrationProgress,
            deviceLanguage = deviceLanguage,
            snackbarHostState = snackbarHostState,
            onChangeFolderClick = onChangeFolderClick,
            onDeleteModel = onDeleteModel,
            onDownloadModel = onDownloadModel,
            onDownloadMmproj = onDownloadMmproj,
            onCancelDownload = onCancelDownload,
            onSnackbarDismiss = onSnackbarDismiss,
            onConfirmMigration = onConfirmMigration,
            onSkipMigration = onSkipMigration,
            onCancelMigration = onCancelMigration,
            embeddingModel = embeddingModel,
            dictationModel = dictationModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

/**
 * Headless Models content (grid + storage card + dialogs) for embedding into
 * either [ModelsScreen]'s Scaffold or the tablet Settings detail pane. The
 * caller supplies a [snackbarHostState] so snackbars hosted by an outer
 * Scaffold get the messages from here.
 */
@Composable
fun ModelsContent(
    storageInfo: StorageInfo?,
    allModels: List<ModelWithStatus>,
    downloadingModels: Map<String, DownloadProgress>,
    snackbarMessage: String?,
    pendingMigration: MigrationState?,
    migrationProgress: MigrationProgress?,
    deviceLanguage: String,
    snackbarHostState: SnackbarHostState,
    onChangeFolderClick: () -> Unit,
    onDeleteModel: (ModelInfo) -> Unit,
    onDownloadModel: (ModelInfo, Boolean) -> Unit,
    onDownloadMmproj: (ModelInfo) -> Unit,
    onCancelDownload: (ModelInfo) -> Unit,
    onSnackbarDismiss: () -> Unit,
    onConfirmMigration: () -> Unit,
    onSkipMigration: () -> Unit,
    onCancelMigration: () -> Unit,
    modifier: Modifier = Modifier,
    // Maximum content width — defaults to 960dp so the grid feels
    // anchored on tablets / Chromebook freeform windows. Callers
    // (e.g. tablet marketing screenshots) can override with
    // [Dp.Infinity] to fill the full pane width.
    maxContentWidth: Dp = 960.dp,
    // Document-Q&A embedding model status. Storage management only: shown
    // under Downloaded when on disk (delete) or while its download is in
    // flight (progress + cancel); never offered under Available.
    embeddingModel: ModelWithStatus? = null,
    dictationModel: ModelWithStatus? = null,
) {
    var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }
    // Vision model awaiting the "with images / text only" download choice.
    var downloadChoiceModel by remember { mutableStateOf<ModelInfo?>(null) }

    // Show snackbar when message changes
    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage)
            onSnackbarDismiss()
        }
    }

    // Split models into downloaded and available. A vision model whose main
    // file is present but whose image module (mmproj) is missing appears in
    // BOTH: under Downloaded as a (text-only) model, and under Available as a
    // downloadable image module.
    val downloadedModels = allModels.filter { it.isDownloaded }
    val availableModels = allModels.filter { !it.isDownloaded || it.needsVisionModule }

    // The embedding model (document Q&A) surfaces under Downloaded when its
    // file is on disk or while its download runs — it can't be chatted with,
    // so it's never offered for download here (the chat's attach flow owns
    // that) and never leaks into Available.
    val embeddingDownloadProgress = embeddingModel?.let { downloadingModels[it.model.name] }
    val showEmbeddingRow = embeddingModel != null &&
        (embeddingModel.isDownloaded || embeddingDownloadProgress != null)

    // Same treatment for voice dictation: it can't be chatted with, the mic
    // button owns its download, and it only appears once it is on disk (or is
    // arriving) so it never sits in Available as a chat model.
    val dictationDownloadProgress = dictationModel?.let { downloadingModels[it.model.name] }
    val showDictationRow = dictationModel != null &&
        (dictationModel.isDownloaded || dictationDownloadProgress != null)

    // When the device language is non-English, separate models that support the user's
    // language from those that don't so users see relevant models first.
    val splitByLanguage = deviceLanguage != "en"
    val supportedModels = if (splitByLanguage) {
        availableModels.filter { it.model.supportsLanguage(deviceLanguage) }
    } else {
        availableModels
    }
    val otherModels = if (splitByLanguage) {
        availableModels.filterNot { it.model.supportsLanguage(deviceLanguage) }
    } else {
        emptyList()
    }

    // Routing for an Available-row download tap:
    //  - a full vision model not yet downloaded → ask (with images / text only)
    //  - main present but module missing → fetch just the module, no dialog
    //  - everything else → plain download (mmproj bundled if the model has one)
    val onAvailableDownload: (ModelWithStatus) -> Unit = { mws ->
        val model = mws.model
        when {
            model.isVision && !mws.isDownloaded -> downloadChoiceModel = model
            mws.needsVisionModule -> onDownloadMmproj(model)
            else -> onDownloadModel(model, true)
        }
    }

    // Adaptive grid: GridCells.Adaptive lays out as many ~340dp columns as
    // fit. At sw>=600dp the content is also capped to ~960dp wide so on a
    // 7" tablet landscape we get 2 columns, on a Chromebook freeform window
    // we get 2-3 depending on width, and on phones we get a single column.
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Scroll-edge divider: shows a HorizontalDivider just below the topbar
    // once the user has scrolled past the first item, signalling that
    // content is now hidden behind the bar (same pattern as the chat scaffold).
    val isScrolled by remember {
        androidx.compose.runtime.derivedStateOf {
            gridState.firstVisibleItemIndex > 0 ||
                gridState.firstVisibleItemScrollOffset > 0
        }
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = maxContentWidth)
        ) {
            if (isScrolled) {
                // Symmetric 12dp inset (same as the chat scaffold dividers)
                // so the line doesn't run all the way to the pane edges.
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Storage info card spans the full grid width.
                if (storageInfo != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        StorageInfoCard(
                            storageInfo = storageInfo,
                            onChangeFolderClick = onChangeFolderClick,
                            // No top padding — the card's top edge sits flush
                            // with the divider / topbar bottom so scrolling
                            // visually slides it under the bar.
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp,
                            )
                        )
                    }
                }

                // Downloaded section header
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.downloaded_models),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (downloadedModels.isEmpty() && !showEmbeddingRow && !showDictationRow) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(R.string.no_downloaded_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(downloadedModels, key = { it.model.filename }) { modelWithStatus ->
                        DownloadedModelItem(
                            model = modelWithStatus.model,
                            // Only show the vision icon once the image module is
                            // actually present; a text-only-present vision model
                            // shows no icon here and offers the module below.
                            vision = modelWithStatus.isMmprojDownloaded,
                            onDeleteClick = { modelToDelete = modelWithStatus.model }
                        )
                    }
                }

                if (showEmbeddingRow && embeddingModel != null) {
                    item(key = "embedding_" + embeddingModel.model.filename) {
                        if (embeddingModel.isDownloaded) {
                            DownloadedModelItem(
                                model = embeddingModel.model,
                                vision = false,
                                onDeleteClick = { modelToDelete = embeddingModel.model }
                            )
                        } else {
                            // Download in flight (started from the chat's attach
                            // flow): progress + cancel. The row only exists while
                            // downloadProgress is non-null, so the download button
                            // inside AvailableModelItem never shows.
                            AvailableModelItem(
                                modelWithStatus = embeddingModel,
                                moduleOnly = false,
                                downloadProgress = embeddingDownloadProgress,
                                onDownloadClick = {},
                                onCancelClick = { onCancelDownload(embeddingModel.model) }
                            )
                        }
                    }
                }

                if (showDictationRow && dictationModel != null) {
                    item(key = "dictation_" + dictationModel.model.filename) {
                        if (dictationModel.isDownloaded) {
                            DownloadedModelItem(
                                model = dictationModel.model,
                                vision = false,
                                onDeleteClick = { modelToDelete = dictationModel.model }
                            )
                        } else {
                            // Download in flight (started from the chat's mic
                            // button): progress + cancel, same as the embedding row.
                            AvailableModelItem(
                                modelWithStatus = dictationModel,
                                moduleOnly = false,
                                downloadProgress = dictationDownloadProgress,
                                onDownloadClick = {},
                                onCancelClick = { onCancelDownload(dictationModel.model) }
                            )
                        }
                    }
                }

                // Available models — either a single section (English) or split into
                // language-supported and other models (non-English locales).
                if (supportedModels.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(
                                if (splitByLanguage) R.string.supports_your_language
                                else R.string.available_models
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(supportedModels, key = { "avail_" + it.model.filename }) { modelWithStatus ->
                        val moduleOnly = modelWithStatus.needsVisionModule
                        val progressKey = if (moduleOnly)
                            modelWithStatus.model.name + " (vision)"
                        else
                            modelWithStatus.model.name
                        AvailableModelItem(
                            modelWithStatus = modelWithStatus,
                            moduleOnly = moduleOnly,
                            downloadProgress = downloadingModels[progressKey],
                            onDownloadClick = { onAvailableDownload(modelWithStatus) },
                            onCancelClick = { onCancelDownload(modelWithStatus.model) }
                        )
                    }
                }

                if (otherModels.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.other_models),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(otherModels, key = { "avail_" + it.model.filename }) { modelWithStatus ->
                        val moduleOnly = modelWithStatus.needsVisionModule
                        val progressKey = if (moduleOnly)
                            modelWithStatus.model.name + " (vision)"
                        else
                            modelWithStatus.model.name
                        AvailableModelItem(
                            modelWithStatus = modelWithStatus,
                            moduleOnly = moduleOnly,
                            downloadProgress = downloadingModels[progressKey],
                            onDownloadClick = { onAvailableDownload(modelWithStatus) },
                            onCancelClick = { onCancelDownload(modelWithStatus.model) }
                        )
                    }
                }
            }
        }
    }
    // (one less `}` here than before — the Scaffold lambda now lives in
    // ModelsScreen above and this function ends after the dialogs below.)

    // Delete confirmation dialog
    modelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text(stringResource(R.string.delete_model_confirm, model.name)) },
            text = { Text(stringResource(R.string.delete_model_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteModel(model)
                        modelToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete_model))
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Vision download choice: bundle the image module or get the text-only model.
    downloadChoiceModel?.let { model ->
        AlertDialog(
            onDismissRequest = { downloadChoiceModel = null },
            title = { Text(stringResource(R.string.download_with_vision_title)) },
            text = { Text(stringResource(R.string.download_with_vision_message, model.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDownloadModel(model, true)
                        downloadChoiceModel = null
                    }
                ) {
                    Text(stringResource(R.string.download_with_images))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            onDownloadModel(model, false)
                            downloadChoiceModel = null
                        }
                    ) {
                        Text(stringResource(R.string.download_text_only))
                    }
                    TextButton(onClick = { downloadChoiceModel = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }

    // Migration confirmation dialog
    pendingMigration?.let { migration ->
        val context = LocalContext.current
        val totalSize = migration.modelsToMigrate.sumOf { it.sizeBytes }
        val sizeFormatted = Formatter.formatFileSize(context, totalSize)
        
        AlertDialog(
            onDismissRequest = onCancelMigration,
            title = { Text(stringResource(R.string.migrate_models_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (migration.isFromDownloads) {
                                R.string.migrate_models_from_downloads
                            } else {
                                R.string.migrate_models_message
                            },
                            migration.modelsToMigrate.size,
                            sizeFormatted
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.models_to_migrate),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    migration.modelsToMigrate.forEach { model ->
                        Text(
                            text = "• ${model.displayName}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmMigration) {
                    Text(stringResource(R.string.migrate))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onSkipMigration) {
                        Text(stringResource(R.string.skip))
                    }
                    TextButton(onClick = onCancelMigration) {
                        Text(stringResource(R.string.cancel))
                    }
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
                    Text(
                        text = stringResource(
                            R.string.migration_progress,
                            progress.currentIndex,
                            progress.totalCount
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
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
}

@Composable
private fun StorageInfoCard(
    storageInfo: StorageInfo,
    onChangeFolderClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val usedFormatted = Formatter.formatFileSize(context, storageInfo.usedBytes)
    val availableFormatted = Formatter.formatFileSize(context, storageInfo.availableBytes)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        // Compact layout: folder info on the left, change-folder button trailing
        // on the right; storage usage row directly below with progress bar
        // inline with available-bytes label. Trims roughly 40dp of vertical
        // space vs the previous stacked layout.
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (storageInfo.isCustomFolder)
                            stringResource(R.string.custom_folder)
                        else
                            stringResource(R.string.downloads_folder),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = storageInfo.path,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onChangeFolderClick) {
                    Text(stringResource(R.string.change_folder))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (storageInfo.totalBytes > 0) {
                val progressValue = storageInfo.usedBytes.toFloat() / storageInfo.totalBytes.toFloat()
                LinearProgressIndicator(
                    progress = { progressValue.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.storage_used_models, usedFormatted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.storage_available, availableFormatted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.storage_used_models, usedFormatted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DownloadedModelItem(
    model: ModelInfo,
    vision: Boolean,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (model.logoRes != 0) {
            Image(
                painter = painterResource(id = model.logoRes),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (model.releaseDate != null) {
                Text(
                    text = model.releaseDateLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ModelCapabilityIcons(
            model = model,
            vision = vision,
            modifier = Modifier.padding(end = 4.dp)
        )
        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.delete_model),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun AvailableModelItem(
    modelWithStatus: ModelWithStatus,
    moduleOnly: Boolean,
    downloadProgress: DownloadProgress?,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val model = modelWithStatus.model
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (model.logoRes != 0) {
            Image(
                painter = painterResource(id = model.logoRes),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (downloadProgress != null) {
                Text(
                    text = formatDownloadStats(context, downloadProgress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                // For a module-only offer the main model is already installed,
                // so describe just the image module rather than the full model.
                Text(
                    text = if (moduleOnly)
                        stringResource(R.string.image_module_description)
                    else
                        model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (downloadProgress == null && !moduleOnly && model.releaseDate != null) {
                Text(
                    text = model.releaseDateLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (downloadProgress == null) {
            ModelCapabilityIcons(
                model = model,
                vision = model.isVision,
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        if (downloadProgress != null) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                if (downloadProgress.progress >= 0f) {
                    CircularProgressIndicator(
                        progress = { downloadProgress.progress },
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                }
                IconButton(
                    onClick = onCancelClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cancel),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            IconButton(onClick = onDownloadClick) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = stringResource(
                        if (moduleOnly) R.string.cd_download_image_module
                        else R.string.download_model
                    ),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun formatDownloadStats(context: Context, progress: DownloadProgress): String {
    if (progress.progress < 0f) return progress.status

    val parts = mutableListOf<String>()

    if (progress.totalBytes > 0) {
        val downloaded = Formatter.formatFileSize(context, progress.bytesDownloaded)
        val total = Formatter.formatFileSize(context, progress.totalBytes)
        parts.add("$downloaded / $total")
    } else if (progress.bytesDownloaded > 0) {
        parts.add(Formatter.formatFileSize(context, progress.bytesDownloaded))
    } else {
        return progress.status
    }

    if (progress.speedBytesPerSec > 0) {
        parts.add("${Formatter.formatFileSize(context, progress.speedBytesPerSec)}/s")
    }

    if (progress.etaSeconds > 0) {
        parts.add(formatEta(progress.etaSeconds))
    }

    return parts.joinToString(" \u2022 ")
}

private fun formatEta(seconds: Long): String {
    return when {
        seconds < 60 -> "${seconds}s left"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
        else -> {
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            "${hours}h ${mins}m left"
        }
    }
}

@Preview
@Composable
private fun ModelsScreenPreview() {
    PlaygroundTheme {
        ModelsScreen(
            storageInfo = StorageInfo(
                path = "/storage/emulated/0/Download",
                usedBytes = 5_000_000_000,
                totalBytes = 64_000_000_000,
                availableBytes = 30_000_000_000,
                isCustomFolder = false
            ),
            allModels = ModelInfoProvider.allModels.take(5).mapIndexed { index, model ->
                ModelWithStatus(model = model, isDownloaded = index < 2)
            },
            downloadingModels = mapOf(
                "Gemma 3 4B" to DownloadProgress(
                    modelName = "Gemma 3 4B",
                    progress = 0.45f,
                    status = "Downloading…",
                    bytesDownloaded = 1_200_000_000L,
                    totalBytes = 2_700_000_000L,
                    speedBytesPerSec = 15_000_000L,
                    etaSeconds = 100L
                )
            ),
            snackbarMessage = null,
            pendingMigration = null,
            migrationProgress = null,
            deviceLanguage = "en",
            onBackClick = {},
            onChangeFolderClick = {},
            onDeleteModel = {},
            onDownloadModel = { _, _ -> },
            onDownloadMmproj = {},
            onCancelDownload = {},
            onSnackbarDismiss = {},
            onConfirmMigration = {},
            onSkipMigration = {},
            onCancelMigration = {}
        )
    }
}
