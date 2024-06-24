package com.druk.lmplayground.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.druk.lmplayground.ModelInfo

@Composable
fun SelectModelDialog(
    models: List<ModelInfo>,
    onDownloadModel: (ModelInfo) -> Unit,
    onLoadModel: (ModelInfo) -> Unit,
    onDismissRequest: () -> Unit
) {
    Dialog(onDismissRequest = { onDismissRequest() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            LazyColumn {
                for (index in models.indices) {
                    val model = models[index]
                    item {
                        Model(model = model) {
                            onDismissRequest()
                            if (model.file != null) {
                                onLoadModel(model)
                            }
                            else {
                                onDownloadModel(model)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Model(
    model: ModelInfo,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        )
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 1
            )
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                maxLines = 2
            )
        }
        if (model.file != null) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                modifier = Modifier.padding(4.dp),
                tint = MaterialTheme.colorScheme.onSurface,
                contentDescription = null
            )
        }
        else {
            Icon(
                imageVector = Icons.Outlined.FileDownload,
                modifier = Modifier.padding(4.dp),
                tint = MaterialTheme.colorScheme.onSurface,
                contentDescription = null
            )
        }

    }
}

@Preview
@Composable
fun SelectModelDialogPreview() {
    SelectModelDialog(
        models = listOf(
            ModelInfo(
                name = "Gemma 2b it q4 k m",
                description = "645Mb"
            ),
            ModelInfo(
                name = "Gemma 2b it q4 k m",
                description = "645Mb"
            ),
            ModelInfo(
                name = "Gemma 2b it q4 k m",
                description = "645Mb"
            ),
        ),
        onDownloadModel = { },
        onLoadModel = { },
        onDismissRequest = { }
    )
}
