package com.druk.lmplayground.util

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/**
 * Returns the distance from the leading window edge to a vertical hinge, or
 * [Dp.Unspecified] when there isn't one (flat device, folded outer display,
 * non-foldable, or a horizontal hinge in tabletop posture).
 *
 * Used to align master/detail boundaries with the hinge on foldables —
 * e.g. set the chat sidebar width to this value so the seam between the
 * panes lands on the crease instead of arbitrarily in the middle of one
 * half of the display. Callers should treat the result as advisory:
 * fall back to a sensible fixed width (typically 320–360dp) when
 * unspecified, and probably cap the minimum so the master pane doesn't
 * collapse on devices where the hinge sits very close to the leading edge.
 */
@Composable
fun rememberHingeWidthDp(activity: Activity): Dp {
    val density = LocalDensity.current
    val state = produceState<Dp>(initialValue = Dp.Unspecified, key1 = activity) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .map { info ->
                info.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
            }
            .collect { feature ->
                value = if (feature != null) {
                    with(density) { feature.bounds.left.toDp() }
                } else {
                    Dp.Unspecified
                }
            }
    }
    return state.value
}
