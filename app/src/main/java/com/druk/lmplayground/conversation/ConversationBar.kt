@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.AppBar
import com.druk.lmplayground.R
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.theme.PlaygroundTheme

@Composable
fun ConversationBar(
    modelInfo: ModelInfo?,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onSelectModelPressed: () -> Unit = { },
    onUnloadModelPressed: () -> Unit = { },
    onNavIconPressed: () -> Unit = { }
) {
    var functionalityNotAvailablePopupShown by remember { mutableStateOf(false) }
    if (functionalityNotAvailablePopupShown) {
        FunctionalityNotAvailablePopup { functionalityNotAvailablePopupShown = false }
    }
    AppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        onNavIconPressed = onNavIconPressed,
        title = {
            if (modelInfo != null) {
                Button(onClick = onUnloadModelPressed,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Eject,
                            tint = Color.Transparent,
                            contentDescription = null
                        )
                        Column(
                            modifier = Modifier.weight(1f, fill = true),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = modelInfo.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                            Text(
                                text = modelInfo.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.Eject,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = null
                        )
                    }
                }
            }
            else {
                Button(onClick = onSelectModelPressed,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        tint = Color.Transparent,
                        contentDescription = null
                    )
                    Text(
                        text = "Select Model",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = null
                    )
                }
            }
        },
        actions = {
            if (modelInfo != null) {
                // Info icon
                Icon(
                    imageVector = Icons.Outlined.Info,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onNavIconPressed)
                        .padding(horizontal = 12.dp, vertical = 16.dp)
                        .height(24.dp),
                    contentDescription = stringResource(id = R.string.info)
                )
            }
            else {
                // Info icon
                Icon(
                    imageVector = Icons.Outlined.Info,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 16.dp)
                        .height(24.dp),
                    contentDescription = stringResource(id = R.string.info)
                )
            }
        }
    )
}

@Preview
@Composable
fun ChannelBarPreview() {
    PlaygroundTheme {
        ConversationBar(modelInfo = null)
    }
}

@Preview("Bar with model info")
@Composable
fun ChannelBarWithModelInfoPreview() {
    PlaygroundTheme {
        ConversationBar(modelInfo =
            ModelInfo(
                name = "Model Name Model Name Model Name Model Name Model Name",
                description = "Model Description"
            )
        )
    }
}