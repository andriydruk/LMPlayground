@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.theme.PlaygroundTheme

@Composable
fun AppBar(
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors? = null,
    showNavIcon: Boolean = true,
    /**
     * Lets the call-site override the Material 3 default of 64dp. Passed as
     * `expandedHeight` to [CenterAlignedTopAppBar] so the bar actually shrinks
     * (not just its inner title slot). [Dp.Unspecified] means "use the M3
     * default", since `TopAppBarDefaults.TopAppBarExpandedHeight` isn't a
     * compile-time constant.
     */
    expandedHeight: Dp = Dp.Unspecified,
    onNavIconPressed: () -> Unit = { },
    title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        actions = actions,
        title = title,
        scrollBehavior = scrollBehavior,
        colors = colors ?: TopAppBarDefaults.centerAlignedTopAppBarColors(),
        expandedHeight = if (expandedHeight != Dp.Unspecified) {
            expandedHeight
        } else {
            TopAppBarDefaults.TopAppBarExpandedHeight
        },
        navigationIcon = {
            if (showNavIcon) {
                IconButton(onClick = onNavIconPressed) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource(id = R.string.navigation_drawer_open),
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun AppBarPreview() {
    PlaygroundTheme {
        AppBar(title = { Text("Preview!") })
    }
}

@Preview
@Composable
fun AppBarPreviewDark() {
    PlaygroundTheme(isDarkTheme = true) {
        AppBar(title = { Text("Preview!") })
    }
}
