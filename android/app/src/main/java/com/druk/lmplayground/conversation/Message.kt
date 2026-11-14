package com.druk.lmplayground.conversation

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.druk.lmplayground.R

private val UserBubbleShape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)

@Composable
fun Message(
    msg: Message,
    isUserMe: Boolean,
    showActions: Boolean = true,
    onTokenCountClicked: (() -> Unit)? = null,
    onImageClick: (Uri) -> Unit = {},
    // User messages: re-send with new text. Null hides Edit (e.g. mid-generation).
    onEdit: ((String) -> Unit)? = null,
    // Assistant replies: regenerate it / continue up to it in a new chat.
    onRegenerate: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
) {
    if (isUserMe) {
        var editing by rememberSaveable(msg.id) { mutableStateOf(false) }
        // Leave edit mode if editing became unavailable (a turn started).
        if (editing && onEdit == null) editing = false
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (editing) 16.dp else 60.dp,
                    end = 16.dp, top = 8.dp, bottom = 8.dp
                ),
            horizontalArrangement = Arrangement.End
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (msg.imageUri != null) {
                    AsyncImage(
                        model = msg.imageUri,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(msg.imageUri!!) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (editing && onEdit != null) {
                    UserMessageEditor(
                        initialText = msg.content,
                        allowEmpty = msg.imageUri != null,
                        onCancel = { editing = false },
                        onSend = { text ->
                            editing = false
                            onEdit(text)
                        }
                    )
                } else {
                    UserBubble(
                        content = msg.content,
                        menuEnabled = showActions,
                        onEdit = onEdit?.let { { editing = true } }
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
        ) {
            ChatItemBubble(
                message = msg,
                showActions = showActions,
                onTokenCountClicked = onTokenCountClicked,
                onRegenerate = onRegenerate,
                onBranch = onBranch,
            )
        }
    }
}

/**
 * A user message bubble. Its actions (Copy, Edit) live in a menu opened by a
 * long press on the bubble, keeping the chat free of per-message buttons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(content: String, menuEnabled: Boolean, onEdit: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    Box {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = UserBubbleShape,
            modifier = Modifier
                .clip(UserBubbleShape)
                .combinedClickable(
                    enabled = menuEnabled,
                    onClickLabel = null,
                    onLongClickLabel = stringResource(R.string.more_options),
                    onClick = {},
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
                Text(
                    text = messageFormatter(text = content, primary = true),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            // Clear of the bubble, rather than touching its edge.
            offset = DpOffset(0.dp, 6.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    clipboardManager.setText(AnnotatedString(content))
                }
            )
            if (onEdit != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit_message)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    }
                )
            }
        }
    }
}

/**
 * In-place editor replacing a user bubble: Send re-sends the edited text
 * from this point of the chat (later messages are replaced).
 */
@Composable
private fun UserMessageEditor(
    initialText: String,
    allowEmpty: Boolean,
    onCancel: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = UserBubbleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .focusRequester(focusRequester)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = { onSend(text.text) },
                enabled = allowEmpty || text.text.isNotBlank()
            ) { Text(stringResource(R.string.send)) }
        }
    }
}
