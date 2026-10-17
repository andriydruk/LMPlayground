package com.druk.lmplayground.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/**
 * Trailing capability icons for a model row: an image glyph for vision (image
 * input), a hammer for tool calling and a lightbulb for thinking. Renders
 * nothing when the model has none. The tool/thinking flags come from
 * [ModelInfo] — static catalog values corrected by real, template-detected
 * capabilities once a model has been loaded (see [resolveCapabilities]). The
 * [vision] flag is passed explicitly so callers can reflect whether the image
 * module is actually present (e.g. hide it when the mmproj is missing).
 */
@Composable
fun ModelCapabilityIcons(
    model: ModelInfo,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 18.dp,
    vision: Boolean = model.isVision,
) {
    if (!vision && !model.supportsTools && !model.supportsThinking) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vision) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = stringResource(R.string.cd_supports_vision),
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        if (model.supportsTools) {
            Icon(
                imageVector = Icons.Outlined.Build,
                contentDescription = stringResource(R.string.cd_supports_tools),
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        if (model.supportsThinking) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = stringResource(R.string.cd_supports_thinking),
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
