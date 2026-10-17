@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme
import java.util.Locale

/**
 * Sub-screens that the tablet detail pane can render inline. Models and
 * SystemPrompts now render inline too via the [modelsDetailContent] /
 * [promptsDetailContent] slots — the caller owns the ViewModels and passes
 * the appropriate Composable when on tablet.
 */
private enum class SettingsDetail { Models, Prompts, Language, Tools, SoundHaptic, Advanced, PrivacyPolicy, Faq }

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onModelsClick: () -> Unit,
    onSystemPromptsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    onFaqClick: () -> Unit,
    onToolsClick: () -> Unit,
    onSoundHapticClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onSendFeedbackClick: () -> Unit,
    appVersion: String,
    /**
     * Tablet-only: when non-null, tapping Models in the master pane opens
     * this composable inline in the detail pane instead of navigating to
     * the full-screen ModelsFragment. Phones leave it null and use
     * [onModelsClick] navigation.
     */
    modelsDetailContent: (@Composable () -> Unit)? = null,
    /**
     * Tablet-only mirror of [modelsDetailContent] for System Prompts.
     */
    promptsDetailContent: (@Composable () -> Unit)? = null,
    /**
     * Tablet-only mirror of [modelsDetailContent] for Language. The
     * picker is just a list — no ViewModel — so the caller can simply
     * pass `{ LanguageContent() }`.
     */
    languageDetailContent: (@Composable () -> Unit)? = null,
    /**
     * Tablet-only mirror of [modelsDetailContent] for the Tools section.
     */
    toolsDetailContent: (@Composable () -> Unit)? = null,
    /**
     * Tablet-only mirror of [modelsDetailContent] for the Sound and Haptic
     * section.
     */
    soundHapticDetailContent: (@Composable () -> Unit)? = null,
    /**
     * Tablet-only mirror of [modelsDetailContent] for the Advanced section.
     */
    advancedDetailContent: (@Composable () -> Unit)? = null,
    /**
     * Optional deep-link target: when "tools", the screen opens directly on the
     * Tools detail (tablet). Used by the What's New "Set up tools" button.
     */
    initialDetailKey: String? = null,
    /**
     * Width of the master pane on tablet. Defaults to 320dp to match the
     * chat sidebar — callers can pass a different value to align the
     * master/detail boundary with a foldable's hinge.
     */
    masterWidth: androidx.compose.ui.unit.Dp = 320.dp,
    /**
     * Debug-only: if non-null, renders a "Crash inference engine" row that
     * triggers a SIGSEGV-equivalent in the `:llama` process. Used to
     * exercise the crash-isolation + recovery flow on a real device. The
     * settings fragment only wires this on debug builds.
     */
    onCrashEngineClick: (() -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    // Use the current window width (not smallestScreenWidthDp) so 7" tablet
    // portrait (600dp) stays single-pane while landscape (1024dp) goes
    // two-pane. 840dp matches the M3 "Expanded" breakpoint where a 360dp
    // master + detail combo actually has room to breathe.
    val twoPane = configuration.screenWidthDp >= 840

    // Default to Models on tablet (when the detail slot is provided) so the
    // user lands on a useful pane instead of an empty placeholder. On phone
    // the detail pane doesn't render at all so the initial value is moot.
    var detail by remember {
        mutableStateOf<SettingsDetail?>(
            when (initialDetailKey) {
                "tools" -> SettingsDetail.Tools
                else -> if (modelsDetailContent != null) SettingsDetail.Models else null
            }
        )
    }

    if (twoPane) {
        // Two-pane layout: each pane owns its own TopAppBar (Scaffold) so the
        // "Settings" bar lives only over the master card on the left and the
        // detail pane gets a title bar matching whatever is selected. The
        // outer Box just stretches across the window and lets each pane's
        // Scaffold consume status-bar insets independently.
        Row(modifier = Modifier.fillMaxSize()) {
            // Master pane — Scaffold gives us a TopAppBar with the right
            // status-bar inset, and the floating card hangs below.
            Scaffold(
                modifier = Modifier.width(masterWidth),
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.settings)) },
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
            ) { masterPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(masterPadding)
                        // 12dp left inset only — the top edge meets the
                        // TopAppBar's bottom so the card visually extends up
                        // to the bar. Content scrolls inside the card; the
                        // rounded top corners stay visible against the bar.
                        .padding(start = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 2.dp,
                ) {
                    SettingsList(
                        appVersion = appVersion,
                        selectedDetail = detail,
                        // On tablet, Models/Prompts taps swap the detail pane
                        // instead of navigating. If the caller didn't supply a
                        // detail content (e.g. embedded environment without
                        // ViewModels), fall back to the navigation callback.
                        onModelsClick = {
                            if (modelsDetailContent != null) {
                                detail = SettingsDetail.Models
                            } else {
                                onModelsClick()
                            }
                        },
                        onSystemPromptsClick = {
                            if (promptsDetailContent != null) {
                                detail = SettingsDetail.Prompts
                            } else {
                                onSystemPromptsClick()
                            }
                        },
                        onLanguageClick = {
                            if (languageDetailContent != null) {
                                detail = SettingsDetail.Language
                            } else {
                                onLanguageClick()
                            }
                        },
                        onToolsClick = {
                            if (toolsDetailContent != null) {
                                detail = SettingsDetail.Tools
                            } else {
                                onToolsClick()
                            }
                        },
                        onSoundHapticClick = {
                            if (soundHapticDetailContent != null) {
                                detail = SettingsDetail.SoundHaptic
                            } else {
                                onSoundHapticClick()
                            }
                        },
                        onAdvancedClick = {
                            if (advancedDetailContent != null) {
                                detail = SettingsDetail.Advanced
                            } else {
                                onAdvancedClick()
                            }
                        },
                        onPrivacyPolicyClick = { detail = SettingsDetail.PrivacyPolicy },
                        onFaqClick = { detail = SettingsDetail.Faq },
                        onCrashEngineClick = onCrashEngineClick
                    )
                }
            }
            // Detail pane — its own Scaffold + TopAppBar reflects the selected
            // detail's title. When nothing is selected we still render a bar
            // (without a title) so the master/detail headers stay vertically
            // aligned.
            val detailTitle = when (detail) {
                SettingsDetail.Models -> stringResource(R.string.models)
                SettingsDetail.Prompts -> stringResource(R.string.system_prompts)
                SettingsDetail.Language -> stringResource(R.string.language)
                SettingsDetail.Tools -> stringResource(R.string.tools)
                SettingsDetail.SoundHaptic -> stringResource(R.string.sound_and_haptic)
                SettingsDetail.Advanced -> stringResource(R.string.advanced)
                SettingsDetail.PrivacyPolicy -> stringResource(R.string.privacy_policy)
                SettingsDetail.Faq -> stringResource(R.string.faq)
                null -> ""
            }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(title = { Text(detailTitle) })
                }
            ) { detailPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(detailPadding)
                ) {
                    when (detail) {
                        SettingsDetail.Models -> modelsDetailContent?.invoke()
                        SettingsDetail.Prompts -> promptsDetailContent?.invoke()
                        SettingsDetail.Language -> languageDetailContent?.invoke()
                        SettingsDetail.Tools -> toolsDetailContent?.invoke()
                        SettingsDetail.SoundHaptic -> soundHapticDetailContent?.invoke()
                        SettingsDetail.Advanced -> advancedDetailContent?.invoke()
                        SettingsDetail.PrivacyPolicy ->
                            PrivacyPolicyContent(onSendFeedbackClick = onSendFeedbackClick)
                        SettingsDetail.Faq -> FaqContent()
                        // detail defaults to Models on tablet, so null only
                        // happens transiently / on degenerate configs — render
                        // nothing instead of a placeholder hint.
                        null -> Unit
                    }
                }
            }
        }
    } else {
        // Phone path — single Scaffold + single-column SettingsList. Same as
        // before the two-pane split.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings)) },
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
            SettingsList(
                appVersion = appVersion,
                selectedDetail = null,
                onModelsClick = onModelsClick,
                onSystemPromptsClick = onSystemPromptsClick,
                onLanguageClick = onLanguageClick,
                onToolsClick = onToolsClick,
                onSoundHapticClick = onSoundHapticClick,
                onAdvancedClick = onAdvancedClick,
                onPrivacyPolicyClick = onPrivacyPolicyClick,
                onFaqClick = onFaqClick,
                onCrashEngineClick = onCrashEngineClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }

    // (Language picker no longer renders here — it's a Fragment on phone and
    // a detail-pane slot on tablet, mirroring Models / Prompts.)
}

// DetailHeader removed — the detail pane now uses its own TopAppBar via a
// dedicated Scaffold, so the title bar style matches the rest of the app.

@Composable
private fun SettingsList(
    appVersion: String,
    selectedDetail: SettingsDetail?,
    onModelsClick: () -> Unit,
    onSystemPromptsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onToolsClick: () -> Unit,
    onSoundHapticClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    onFaqClick: () -> Unit,
    onCrashEngineClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    // verticalScroll so the master list scrolls when the rows overflow the
    // available height — e.g. landscape phone, short tablet windows, or when
    // debug build adds the Crash row.
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // Models row
        SettingsRow(
            icon = Icons.Outlined.Storage,
            title = stringResource(R.string.models),
            subtitle = stringResource(R.string.models_subtitle),
            selected = selectedDetail == SettingsDetail.Models,
            onClick = onModelsClick
        )

        // System Prompts row
        SettingsRow(
            icon = Icons.AutoMirrored.Outlined.Article,
            title = stringResource(R.string.system_prompts),
            subtitle = stringResource(R.string.system_prompts_subtitle),
            selected = selectedDetail == SettingsDetail.Prompts,
            onClick = onSystemPromptsClick
        )

        // Tools row
        SettingsRow(
            icon = Icons.Outlined.Build,
            title = stringResource(R.string.tools),
            subtitle = stringResource(R.string.tools_subtitle),
            selected = selectedDetail == SettingsDetail.Tools,
            onClick = onToolsClick
        )

        // Sound and Haptic row
        SettingsRow(
            icon = Icons.Outlined.VolumeUp,
            title = stringResource(R.string.sound_and_haptic),
            subtitle = stringResource(R.string.sound_and_haptic_subtitle),
            selected = selectedDetail == SettingsDetail.SoundHaptic,
            onClick = onSoundHapticClick
        )

        // Advanced row
        SettingsRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(R.string.advanced),
            subtitle = stringResource(R.string.advanced_subtitle),
            selected = selectedDetail == SettingsDetail.Advanced,
            onClick = onAdvancedClick
        )

        // Language row
        val currentTag = currentLanguageTag()
        val languageSubtitle = if (currentTag == null) {
            stringResource(R.string.language_system_default)
        } else {
            Locale.forLanguageTag(currentTag).let { locale ->
                locale.getDisplayName(locale)
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
        }
        SettingsRow(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.language),
            subtitle = languageSubtitle,
            selected = selectedDetail == SettingsDetail.Language,
            onClick = onLanguageClick
        )

        // Privacy Policy row
        SettingsRow(
            icon = Icons.Outlined.Policy,
            title = stringResource(R.string.privacy_policy),
            selected = selectedDetail == SettingsDetail.PrivacyPolicy,
            onClick = onPrivacyPolicyClick
        )

        // FAQ row
        SettingsRow(
            icon = Icons.Outlined.HelpOutline,
            title = stringResource(R.string.faq),
            subtitle = stringResource(R.string.faq_subtitle),
            selected = selectedDetail == SettingsDetail.Faq,
            onClick = onFaqClick
        )

        // Debug-only: crash the inference engine to verify isolation.
        if (onCrashEngineClick != null) {
            SettingsRow(
                icon = Icons.Outlined.BugReport,
                title = stringResource(R.string.debug_crash_engine_title),
                subtitle = stringResource(R.string.debug_crash_engine_subtitle),
                onClick = onCrashEngineClick,
            )
        }

        // Version row (static, not clickable)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.version, appVersion),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    // Unselected rows are transparent so the row picks up whatever surface
    // the parent provides — surface for phone, surfaceContainer for the
    // tablet floating card.
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    PlaygroundTheme {
        SettingsScreen(
            onBackClick = {},
            onModelsClick = {},
            onSystemPromptsClick = {},
            onLanguageClick = {},
            onToolsClick = {},
            onSoundHapticClick = {},
            onAdvancedClick = {},
            onPrivacyPolicyClick = {},
            onFaqClick = {},
            onSendFeedbackClick = {},
            appVersion = "1.0.0"
        )
    }
}
