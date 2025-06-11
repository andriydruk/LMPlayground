package com.druk.lmplayground.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.models.GenerationParams

@Composable
fun ParametersPanel(
    visible: Boolean,
    params: GenerationParams,
    onApply: (GenerationParams) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(), initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(animationSpec = tween(), targetOffsetX = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .padding(16.dp)
        ) {
            val ctx = remember { mutableStateOf(params.contextSize.toString()) }
            val temp = remember { mutableStateOf(params.temperature.toString()) }
            val topP = remember { mutableStateOf(params.topP.toString()) }
            val topK = remember { mutableStateOf(params.topK.toString()) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Icon(imageVector = Icons.Outlined.Close, contentDescription = null)
                }
                OutlinedTextField(value = ctx.value, onValueChange = { ctx.value = it }, label = { Text("Context size") })
                OutlinedTextField(value = temp.value, onValueChange = { temp.value = it }, label = { Text("Temperature") })
                OutlinedTextField(value = topP.value, onValueChange = { topP.value = it }, label = { Text("Top P") })
                OutlinedTextField(value = topK.value, onValueChange = { topK.value = it }, label = { Text("Top K") })
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = {
                    onApply(
                        GenerationParams(
                            contextSize = ctx.value.toIntOrNull() ?: params.contextSize,
                            temperature = temp.value.toFloatOrNull() ?: params.temperature,
                            topP = topP.value.toFloatOrNull() ?: params.topP,
                            topK = topK.value.toIntOrNull() ?: params.topK
                        )
                    )
                }, modifier = Modifier.align(Alignment.End)) {
                    Text("Apply")
                }
            }
        }
    }
}
