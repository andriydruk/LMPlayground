package com.druk.lmplayground.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingBottomSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val options = remember { mutableStateListOf(false, false, false, false, false) }
    val labels = listOf(
        stringResource(id = R.string.offensive_unsafe),
        stringResource(id = R.string.not_correct),
        stringResource(id = R.string.didnt_follow_instructions),
        stringResource(id = R.string.wrong_language),
        stringResource(id = R.string.other)
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.rating_question),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.padding(4.dp))
            labels.forEachIndexed { index, label ->
                FilterChip(
                    selected = options[index],
                    onClick = { options[index] = !options[index] },
                    label = { Text(text = label) },
                    leadingIcon = if (options[index]) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null
                )
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(text = stringResource(id = R.string.send))
            }
        }
    }
}
