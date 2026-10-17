package com.druk.lmplayground.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/**
 * Bottom-sheet editor for a system prompt. The sheet expands to near-full
 * height so the user has plenty of room to type.
 *
 * The caller supplies the primary action label — Save for library CRUD,
 * "Update" for the Generation Params reviser. An optional [onDelete] lambda
 * adds a Delete button on the far left (used when editing an existing library
 * entry).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptEditorSheet(
    initialText: String,
    title: String,
    primaryLabel: String,
    onPrimary: (text: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        SystemPromptEditorBody(
            initialText = initialText,
            title = title,
            primaryLabel = primaryLabel,
            onPrimary = onPrimary,
            onDelete = onDelete,
            onDismiss = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .imePadding()
        )
    }
}

/**
 * Editor body without any sheet/dialog wrapping. Used directly by the tablet
 * master-detail layout (right pane) and wrapped by [SystemPromptEditorSheet]
 * for the phone bottom-sheet flow. The caller controls outer sizing via
 * [modifier].
 */
@Composable
fun SystemPromptEditorBody(
    initialText: String,
    title: String,
    primaryLabel: String,
    onPrimary: (text: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }

    Column(modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            placeholder = { Text(stringResource(R.string.system_prompt_hint)) },
            textStyle = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onDelete != null) {
                OutlinedButton(onClick = {
                    onDelete()
                    onDismiss()
                }) { Text(stringResource(R.string.delete)) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = text.trim().isNotEmpty(),
                onClick = {
                    onPrimary(text.trim())
                    onDismiss()
                }
            ) { Text(primaryLabel) }
        }
    }
}
