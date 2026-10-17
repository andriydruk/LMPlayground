package com.druk.lmplayground.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.ChatSessionEntity

@Composable
fun SessionListDrawer(
    sessions: List<ChatSessionEntity>,
    currentSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onSettingsClicked: () -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        SessionListContent(
            sessions = sessions,
            currentSessionId = currentSessionId,
            onSessionSelected = onSessionSelected,
            onDeleteSession = onDeleteSession,
            onRenameSession = onRenameSession,
            onPinSession = onPinSession,
            onSettingsClicked = onSettingsClicked
        )
    }
}

@Composable
fun PermanentSessionList(
    sessions: List<ChatSessionEntity>,
    currentSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onSettingsClicked: () -> Unit,
    width: Dp = 320.dp
) {
    // "Floating card" sidebar (Apple Maps / Liquid Glass style): the drawer
    // sheet itself is transparent so the chat surface bleeds through around
    // the card, and the actual sessions list sits inside an inset Surface
    // with rounded corners and a small tonal step. The card is padded from
    // every edge (status bar at top picked up by safeDrawing insets), so it
    // visibly floats rather than meeting any window edge.
    PermanentDrawerSheet(
        modifier = Modifier.width(width),
        drawerContainerColor = Color.Transparent,
        drawerTonalElevation = 0.dp,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                // Only inset from the left edge — the status bar above and
                // navigation bar below already provide vertical breathing
                // room, and the chat pane handles the right side itself.
                // Small 4dp top breathing room so the card doesn't kiss the
                // status bar. The inner header spacer is dropped to 0 to
                // compensate so "Conversations" / gear stay at the same
                // absolute Y as the chat title / new-chat icon.
                .padding(start = 12.dp, top = 4.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            SessionListContent(
                sessions = sessions,
                currentSessionId = currentSessionId,
                onSessionSelected = onSessionSelected,
                onDeleteSession = onDeleteSession,
                onRenameSession = onRenameSession,
                onPinSession = onPinSession,
                onSettingsClicked = onSettingsClicked,
                // Tablet path runs alongside the compact (40dp) top bar, so
                // the sidebar header shrinks to match — "Conversations" lines
                // up with "Select Model" and the gear icon with the new-chat
                // icon on the right.
                compact = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListContent(
    sessions: List<ChatSessionEntity>,
    currentSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onSettingsClicked: () -> Unit,
    /**
     * When true, the header row matches the 40dp compact top bar height so
     * the "Conversations" title aligns horizontally with the chat title and
     * the gear icon aligns with the new-chat icon. Defaults to the 64dp
     * phone layout used by the modal drawer.
     */
    compact: Boolean = false
) {
    var contextMenuSessionId by remember { mutableStateOf<String?>(null) }
    var renameDialogSession by remember { mutableStateOf<ChatSessionEntity?>(null) }

    Column(
        modifier = Modifier.fillMaxHeight()
    ) {
            // Header alignment with the chat top bar is now handled by the
            // sidebar Surface's 4dp top padding (see PermanentSessionList).
            // No inner spacer needed.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 40.dp else 64.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.conversations),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSettingsClicked) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                // .imePadding() so the list shrinks when the soft keyboard
                // opens — otherwise the LazyColumn extends behind the IME and
                // its bottom items are unreachable (you can't scroll because
                // it thinks everything already fits).
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
            ) {
                items(sessions, key = { it.id }) { session ->
                    val isSelected = session.id == currentSessionId
                    val showMenu = contextMenuSessionId == session.id

                    Surface(
                        // Unselected rows are transparent so they inherit the
                        // sidebar's surfaceContainer tint; only the selected
                        // pill draws contrast.
                        color = if (isSelected)
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            Color.Transparent,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            // Clip BEFORE combinedClickable so the ripple /
                            // long-press indication is bounded to the pill
                            // shape — otherwise it draws on the full row
                            // rectangle and looks square against the rounded
                            // selected state.
                            .clip(RoundedCornerShape(24.dp))
                            .combinedClickable(
                                onClick = { onSessionSelected(session.id) },
                                onLongClick = { contextMenuSessionId = session.id }
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (session.pinned) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Filled.PushPin,
                                    contentDescription = stringResource(R.string.pinned_label),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { contextMenuSessionId = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (session.pinned) stringResource(R.string.unpin) else stringResource(R.string.pin)) },
                                onClick = {
                                    onPinSession(session.id, !session.pinned)
                                    contextMenuSessionId = null
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.PushPin,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                onClick = {
                                    renameDialogSession = session
                                    contextMenuSessionId = null
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    onDeleteSession(session.id)
                                    contextMenuSessionId = null
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    // Rename dialog
    renameDialogSession?.let { session ->
        var text by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renameDialogSession = null },
            title = { Text(stringResource(R.string.rename_conversation)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(session.id, text.trim())
                        renameDialogSession = null
                    },
                    enabled = text.isNotBlank()
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogSession = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
