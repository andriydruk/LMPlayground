@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.SystemPromptEntity

/**
 * Full-screen System Prompts page used by [SystemPromptsFragment] when reached
 * via navigation on phone. Wraps [SystemPromptsContent] in a Scaffold + back
 * button. The Content composable on its own is what the tablet Settings
 * detail pane embeds.
 */
@Composable
fun SystemPromptsScreen(
    prompts: List<SystemPromptEntity>,
    onBackClick: () -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (id: String, text: String) -> Unit,
    onDelete: (id: String) -> Unit
) {
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_prompts)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editorTarget = EditorTarget.New }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add))
            }
        }
    ) { padding ->
        SystemPromptsContent(
            prompts = prompts,
            onSelect = { editorTarget = EditorTarget.Edit(it) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }

    // Bottom-sheet editor — same instance used on phone and tablet, hence the
    // earlier split into separate phone/tablet layouts was removed.
    EditorBottomSheet(
        target = editorTarget,
        onAdd = onAdd,
        onUpdate = onUpdate,
        onDelete = onDelete,
        onDismiss = { editorTarget = null }
    )
}

/**
 * Headless body for both [SystemPromptsScreen] and the tablet
 * `SettingsScreen` detail pane. Picks LazyColumn (phone) vs LazyVerticalGrid
 * (>=840dp) automatically. Editing is delegated to [EditorBottomSheet] via
 * the [onAdd]/[onUpdate]/[onDelete] callbacks — the caller owns the editor
 * state so the bottom sheet renders at the right composition level.
 *
 * When embedded in Settings on tablet, [showAddRow] = true puts a "+ New
 * system prompt" pill at the top of the list, so we don't need the
 * floating-action button that the standalone Screen relies on.
 */
@Composable
fun SystemPromptsContent(
    prompts: List<SystemPromptEntity>,
    onSelect: (SystemPromptEntity) -> Unit,
    modifier: Modifier = Modifier,
    onNewPrompt: (() -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    // 2-column grid kicks in at the same width threshold as the chat sidebar
    // / Settings two-pane (840dp). Below that we render a single column list
    // so the cards keep enough horizontal room for the body preview.
    val isWide = configuration.screenWidthDp >= 840
    val columns = if (isWide) GridCells.Adaptive(minSize = 280.dp) else GridCells.Fixed(1)

    if (prompts.isEmpty() && onNewPrompt == null) {
        Box(
            modifier = modifier.padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.system_prompts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val gridState = rememberLazyGridState()
    val isScrolled by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 ||
                gridState.firstVisibleItemScrollOffset > 0
        }
    }

    Column(modifier = modifier) {
        if (isScrolled) {
            // Same 12dp inset as the Models / chat dividers — visual hint
            // that content has scrolled behind the topbar.
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
        }
        LazyVerticalGrid(
            state = gridState,
            columns = columns,
            modifier = Modifier.fillMaxHeight(),
            // No top padding — the first row of cards sits flush against the
            // topbar / scroll divider so it aligns with the Settings master
            // card on the left.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onNewPrompt != null) {
                item {
                    NewPromptCard(onClick = onNewPrompt)
                }
            }
            items(prompts, key = { it.id }) { prompt ->
                PromptCard(
                    prompt = prompt,
                    onClick = { onSelect(prompt) }
                )
            }
        }
    }
}

// Shared minimum card height so the "+ New" tile and prompt previews line
// up in the grid. PromptCard renders 3 lines of bodySmall + 20dp vertical
// padding (~70dp), so 84dp gives a small buffer that both cards can fill
// regardless of font scale.
private val PromptCardMinHeight = 84.dp

@Composable
private fun NewPromptCard(onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PromptCardMinHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.system_prompt_new),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun PromptCard(
    prompt: SystemPromptEntity,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PromptCardMinHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = prompt.text,
                style = MaterialTheme.typography.bodySmall,
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Bottom-sheet editor — extracted so [SystemPromptsScreen] and any embedded
 * use of [SystemPromptsContent] both render the editor at the right
 * composition level. Pass [target] = null to keep the sheet hidden.
 */
@Composable
fun EditorBottomSheet(
    target: EditorTarget?,
    onAdd: (String) -> Unit,
    onUpdate: (id: String, text: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (target) {
        EditorTarget.New -> SystemPromptEditorSheet(
            initialText = "",
            title = stringResource(R.string.system_prompt_new),
            primaryLabel = stringResource(R.string.save),
            onPrimary = { text -> onAdd(text) },
            onDismiss = onDismiss
        )
        is EditorTarget.Edit -> SystemPromptEditorSheet(
            initialText = target.prompt.text,
            title = stringResource(R.string.system_prompt_edit),
            primaryLabel = stringResource(R.string.save),
            onPrimary = { text -> onUpdate(target.prompt.id, text) },
            onDelete = { onDelete(target.prompt.id) },
            onDismiss = onDismiss
        )
        null -> Unit
    }
}

sealed class EditorTarget {
    object New : EditorTarget()
    data class Edit(val prompt: SystemPromptEntity) : EditorTarget()
}
