@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.druk.lmplayground.R

/**
 * Standalone Language picker page used by [LanguageFragment] when reached via
 * navigation on phone. Wraps [LanguageContent] in a Scaffold + back button.
 * The Content composable on its own is what the tablet Settings detail pane
 * embeds.
 */
@Composable
fun LanguageScreen(
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.language)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LanguageContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

/**
 * Headless language list (radio rows) for embedding either in
 * [LanguageScreen] or the tablet Settings detail pane. Manages its own scroll
 * state so the currently-selected language scrolls into view, and renders a
 * scroll-edge HorizontalDivider when the user has scrolled past the first
 * row — same pattern as Models / Prompts.
 */
@Composable
fun LanguageContent(
    modifier: Modifier = Modifier,
) {
    val options = remember { buildLanguageOptions() }
    val currentTag = currentLanguageTag()
    val listState = rememberLazyListState()

    LaunchedEffect(currentTag) {
        val idx = options.indexOfFirst { it.tag == currentTag }.coerceAtLeast(0)
        listState.scrollToItem(idx)
    }

    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
        }
    }

    Column(modifier = modifier) {
        if (isScrolled) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight(),
            // No top padding so the first language row sits flush against the
            // topbar / scroll divider, matching the master card on the left.
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            items(options, key = { it.tag ?: "__system__" }) { option ->
                val label = option.displayName.ifEmpty {
                    stringResource(R.string.language_system_default)
                }
                LanguageRow(
                    label = label,
                    selected = option.tag == currentTag,
                    onClick = { applyLanguage(option.tag) }
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun applyLanguage(tag: String?) {
    val locales = if (tag.isNullOrEmpty()) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(tag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}
