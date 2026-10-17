package com.druk.lmplayground.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.RagDocumentEntity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * One chip per document attached to the current chat, shown above the
 * input dock: a spinner while indexing, a document icon when ready (tap
 * reopens the original via [onOpen]). Failed documents never appear here
 * — their row is deleted and the reason surfaces as a toast. The X asks
 * for removal via [onRemove] — confirmation lives with the caller.
 *
 * The chips float over the message list, so like the rest of the bottom
 * chrome they frost the content behind them ([hazeState]/[hazeStyle],
 * same pattern as JumpToBottom); without a haze source they fall back to
 * an opaque container.
 */
@Composable
fun DocumentChipsRow(
    documents: List<RagDocumentEntity>,
    onRemove: (RagDocumentEntity) -> Unit,
    onOpen: (RagDocumentEntity) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    hazeStyle: HazeStyle = HazeStyle.Unspecified,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(documents, key = { it.id }) { document ->
            // Match InputChip's own shape so the frost fill and the chip
            // outline stay perfectly aligned. The min-touch-target
            // enforcement must be off for that: it inflates the chip's
            // outer bounds to 48dp while the visible surface stays 32dp,
            // and the frost (applied to the outer bounds) would bleed
            // past the border.
            val chipShape = RoundedCornerShape(8.dp)
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified
            ) {
                InputChip(
                    selected = false,
                    onClick = {
                        // INDEXING: no action — the spinner says it all.
                        if (document.status == RagDocumentEntity.STATUS_READY) onOpen(document)
                    },
                    shape = chipShape,
                    modifier = if (hazeState != null) {
                        Modifier
                            .clip(chipShape)
                            .hazeEffect(hazeState, hazeStyle)
                    } else {
                        Modifier
                    },
                    colors = if (hazeState != null) {
                        // Transparent container: the frost behind provides
                        // the fill.
                        InputChipDefaults.inputChipColors()
                    } else {
                        InputChipDefaults.inputChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                    },
                    label = {
                        Text(
                            text = document.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        if (document.status == RagDocumentEntity.STATUS_INDEXING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = stringResource(R.string.attached_document),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { onRemove(document) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.remove_document),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}
