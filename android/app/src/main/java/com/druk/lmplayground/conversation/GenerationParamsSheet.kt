package com.druk.lmplayground.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.settings.SystemPromptEditorSheet
import com.druk.lmplayground.settings.ToolsContent
import com.druk.llamacpp.tools.Tool
import kotlin.math.roundToInt

/**
 * Bottom sheet for per-session generation settings, organised into three tabs:
 *   - Params: generation parameters (context size, sampling, advanced).
 *   - Prompt: the current system prompt plus the per-model MRU list of saved
 *     prompts, tap-to-apply.
 *   - Tools: the tool toggles (or a note when the model can't call tools).
 *
 * Edited params accumulate in [editedParams] across tab switches and commit
 * once when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationParamsSheet(
    params: GenerationParams,
    maxContextSize: Int,
    supportsThinking: Boolean = false,
    supportsToolCalling: Boolean = false,
    tools: List<Tool> = emptyList(),
    toolEnabledStates: Map<String, Boolean> = emptyMap(),
    onToolEnabledChanged: (String, Boolean) -> Unit = { _, _ -> },
    systemPrompt: String = "",
    systemPromptId: String? = null,
    recentSystemPrompts: List<SystemPromptEntity> = emptyList(),
    canUpdateLinkedPrompt: Boolean = false,
    onParamsChanged: (GenerationParams) -> Unit,
    onUpdateLinkedPrompt: (String) -> Unit = {},
    onSaveAsNewPrompt: (String) -> Unit = {},
    onClearSystemPrompt: () -> Unit = {},
    onApplyPrompt: (SystemPromptEntity) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editedParams by remember(params) { mutableStateOf(params) }
    // Hoisted so the expanded state survives a tab round-trip (the tab body is
    // disposed when another tab is selected).
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showPromptReviser by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    // The sheet wraps its content and grows naturally — e.g. expanding the
    // Advanced params pushes the sheet up like a default bottom sheet.
    // [maxTabHeight] only caps very tall content so it scrolls instead of
    // clipping (rare — small screens / large fonts).
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxTabHeight = (screenHeightDp * 0.82f).dp
    // Params is the default tab and the tallest at rest; measure its content
    // height and use it as the floor for the shorter Prompt/Tools tabs so all
    // three open at the same height (adapts to the model — e.g. Params is
    // shorter without a Thinking Budget slider).
    var paramsContentHeight by remember { mutableStateOf(0.dp) }

    ModalBottomSheet(
        onDismissRequest = {
            if (editedParams != params) {
                onParamsChanged(editedParams)
            }
            onDismiss()
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Transparent so the sheet's (lighter) container colour shows
            // through the tab row instead of the tab row painting its own
            // darker surface — keeps the tabs and content the same tone as
            // the sheet header/drag handle.
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_params)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_prompt)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(R.string.tools)) }
                )
            }

            when (selectedTab) {
                0 -> ParamsTab(
                    params = editedParams,
                    baselineParams = params,
                    maxContextSize = maxContextSize,
                    supportsThinking = supportsThinking,
                    showAdvanced = showAdvanced,
                    onShowAdvancedChange = { showAdvanced = it },
                    onParamsChange = { editedParams = it },
                    maxHeight = maxTabHeight,
                    onHeightChange = { paramsContentHeight = it }
                )
                1 -> PromptTab(
                    systemPrompt = systemPrompt,
                    systemPromptId = systemPromptId,
                    recentSystemPrompts = recentSystemPrompts,
                    onEditCurrent = { showPromptReviser = true },
                    onClearSystemPrompt = onClearSystemPrompt,
                    onApplyPrompt = onApplyPrompt,
                    minHeight = paramsContentHeight,
                    maxHeight = maxTabHeight
                )
                2 -> ToolsTab(
                    supportsToolCalling = supportsToolCalling,
                    tools = tools,
                    toolEnabledStates = toolEnabledStates,
                    onToolEnabledChanged = onToolEnabledChanged,
                    minHeight = paramsContentHeight,
                    maxHeight = maxTabHeight
                )
            }
        }
    }

    if (showPromptReviser) {
        SystemPromptEditorSheet(
            initialText = systemPrompt,
            title = stringResource(
                if (systemPrompt.isEmpty()) R.string.system_prompt_new
                else R.string.system_prompt_edit
            ),
            primaryLabel = stringResource(R.string.system_prompt_update),
            onPrimary = { text ->
                // If the current session prompt is backed by a library entry,
                // update that entry in place; otherwise create a new library
                // entry and link it to the session.
                if (canUpdateLinkedPrompt && systemPrompt.isNotEmpty()) {
                    onUpdateLinkedPrompt(text)
                } else {
                    onSaveAsNewPrompt(text)
                }
            },
            onDismiss = { showPromptReviser = false }
        )
    }
}

@Composable
private fun ParamsTab(
    params: GenerationParams,
    baselineParams: GenerationParams,
    maxContextSize: Int,
    supportsThinking: Boolean,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    onParamsChange: (GenerationParams) -> Unit,
    maxHeight: Dp,
    onHeightChange: (Dp) -> Unit
) {
    val contextMin = 512
    val contextMax = maxContextSize.coerceAtLeast(512)
    val contextStep = 512
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .onSizeChanged { onHeightChange(with(density) { it.height.toDp() }) }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Context Size
        val contextWarning = params.contextSize != baselineParams.contextSize
        ParamSlider(
            label = stringResource(R.string.context_size),
            value = params.contextSize.toFloat(),
            valueRange = contextMin.toFloat()..contextMax.toFloat(),
            steps = (((contextMax - contextMin) / contextStep) - 1).coerceAtLeast(0),
            valueDisplay = "${params.contextSize}",
            warning = if (contextWarning) stringResource(R.string.will_reset_conversation) else null,
            onValueChange = {
                val snapped = (it / contextStep).roundToInt() * contextStep
                val newContextSize = snapped.coerceIn(contextMin, contextMax)
                val oldContextSize = params.contextSize
                // Auto-scale thinking budget proportionally when context size changes
                val newBudget = if (oldContextSize > 0) {
                    (params.thinkingBudget.toLong() * newContextSize / oldContextSize).toInt()
                        .coerceIn(64, newContextSize)
                } else {
                    newContextSize / 4
                }
                onParamsChange(params.copy(contextSize = newContextSize, thinkingBudget = newBudget))
            }
        )

        // Thinking Budget (only for thinking-capable models)
        if (supportsThinking) {
            val budgetMin = 64
            val budgetMax = params.contextSize
            ParamSlider(
                label = stringResource(R.string.thinking_budget),
                value = params.thinkingBudget.toFloat(),
                valueRange = budgetMin.toFloat()..budgetMax.toFloat(),
                steps = 0,
                valueDisplay = stringResource(R.string.tokens_value, params.thinkingBudget),
                onValueChange = {
                    val snapped = (it / 64).roundToInt() * 64
                    onParamsChange(
                        params.copy(thinkingBudget = snapped.coerceIn(budgetMin, budgetMax))
                    )
                }
            )
        }

        // Temperature
        ParamSlider(
            label = stringResource(R.string.temperature),
            value = params.temperature,
            valueRange = 0f..2f,
            steps = 0,
            valueDisplay = "%.2f".format(params.temperature),
            onValueChange = {
                onParamsChange(params.copy(temperature = (it * 100).roundToInt() / 100f))
            }
        )

        // Top-P
        ParamSlider(
            label = stringResource(R.string.top_p),
            value = params.topP,
            valueRange = 0f..1f,
            steps = 0,
            valueDisplay = "%.2f".format(params.topP),
            subtitle = if (params.topP >= 1f) stringResource(R.string.disabled_label) else null,
            onValueChange = {
                onParamsChange(params.copy(topP = (it * 100).roundToInt() / 100f))
            }
        )

        // Repetition Penalty
        ParamSlider(
            label = stringResource(R.string.repetition_penalty),
            value = params.repetitionPenalty,
            valueRange = 1f..2f,
            steps = 0,
            valueDisplay = "%.2f".format(params.repetitionPenalty),
            subtitle = if (params.repetitionPenalty <= 1f) stringResource(R.string.disabled_label) else null,
            onValueChange = {
                onParamsChange(params.copy(repetitionPenalty = (it * 100).roundToInt() / 100f))
            }
        )

        // Advanced section
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShowAdvancedChange(!showAdvanced) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.advanced),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (showAdvanced) stringResource(R.string.collapse) else stringResource(R.string.expand)
            )
        }

        AnimatedVisibility(visible = showAdvanced) {
            Column {
                // Top-K
                ParamSlider(
                    label = stringResource(R.string.top_k),
                    value = params.topK.toFloat(),
                    valueRange = 0f..200f,
                    steps = 0,
                    valueDisplay = "${params.topK}",
                    subtitle = if (params.topK == 0) stringResource(R.string.disabled_label) else null,
                    onValueChange = {
                        onParamsChange(params.copy(topK = it.roundToInt()))
                    }
                )

                // Min-P
                ParamSlider(
                    label = stringResource(R.string.min_p),
                    value = params.minP,
                    valueRange = 0f..0.5f,
                    steps = 0,
                    valueDisplay = "%.3f".format(params.minP),
                    subtitle = if (params.minP <= 0f) stringResource(R.string.disabled_label) else null,
                    onValueChange = {
                        onParamsChange(params.copy(minP = (it * 1000).roundToInt() / 1000f))
                    }
                )

                // Seed
                var seedText by remember(params.seed) {
                    mutableStateOf(if (params.seed < 0) "" else params.seed.toString())
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.seed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = seedText,
                        onValueChange = { text ->
                            seedText = text
                            val value = text.toIntOrNull() ?: -1
                            onParamsChange(params.copy(seed = value))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        placeholder = { Text(stringResource(R.string.random)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { onParamsChange(GenerationParams()) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.reset))
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PromptTab(
    systemPrompt: String,
    systemPromptId: String?,
    recentSystemPrompts: List<SystemPromptEntity>,
    onEditCurrent: () -> Unit,
    onClearSystemPrompt: () -> Unit,
    onApplyPrompt: (SystemPromptEntity) -> Unit,
    minHeight: Dp,
    maxHeight: Dp
) {
    val hasPrompt = systemPrompt.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight, max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Current system prompt — tap the card to author or edit it for this
        // session. The Clear button is always rendered (alpha-hidden when no
        // prompt) so the header height stays stable.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.system_prompt_current),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onClearSystemPrompt,
                enabled = hasPrompt,
                modifier = Modifier.alpha(if (hasPrompt) 1f else 0f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(stringResource(R.string.clear))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEditCurrent() },
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = if (hasPrompt) systemPrompt
                       else stringResource(R.string.system_prompt_current_empty),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (hasPrompt) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Saved prompts, per-model MRU order. Tapping one applies it without
        // closing the sheet; the current card and the selection highlight
        // update live, and the list re-orders on the next open.
        if (recentSystemPrompts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.saved_prompts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            for (prompt in recentSystemPrompts) {
                SavedPromptCard(
                    prompt = prompt,
                    selected = prompt.id == systemPromptId,
                    onClick = { onApplyPrompt(prompt) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SavedPromptCard(
    prompt: SystemPromptEntity,
    selected: Boolean,
    onClick: () -> Unit
) {
    val border: BorderStroke = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        CardDefaults.outlinedCardBorder()
    }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = border,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = prompt.text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ToolsTab(
    supportsToolCalling: Boolean,
    tools: List<Tool>,
    toolEnabledStates: Map<String, Boolean>,
    onToolEnabledChanged: (String, Boolean) -> Unit,
    minHeight: Dp,
    maxHeight: Dp
) {
    if (supportsToolCalling && tools.isNotEmpty()) {
        // Reuse the Settings → Tools rows (icon, title, description, example,
        // Switch). ToolsContent scrolls internally, so it gets a bounded
        // modifier and must not be wrapped in another verticalScroll.
        ToolsContent(
            tools = tools,
            enabledStates = toolEnabledStates,
            onToolEnabledChanged = onToolEnabledChanged,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight, max = maxHeight)
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight, max = maxHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.tools_unsupported),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueDisplay: String,
    subtitle: String? = null,
    warning: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(
                        text = " ($subtitle)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
