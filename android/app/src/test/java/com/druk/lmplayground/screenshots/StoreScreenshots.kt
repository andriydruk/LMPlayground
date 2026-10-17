package com.druk.lmplayground.screenshots

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SignalCellular4Bar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density as ComposeDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.ConversationBar
import com.druk.lmplayground.conversation.DocumentChipsRow
import com.druk.lmplayground.conversation.Message
import com.druk.lmplayground.conversation.UserInput
import com.druk.lmplayground.data.RagDocumentEntity
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.Model
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.settings.ToolsContent
import com.druk.lmplayground.theme.PlaygroundTheme
import com.druk.llamacpp.tools.Tool
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate

// ── Device frame using Pixel 9 Pro skin ────────────────────────────
// Layout from skin: frame 1408x2974, display at (60,61) size 1280x2856, corner_radius 109
private const val FRAME_WIDTH = 1408f
private const val FRAME_HEIGHT = 2974f
private const val DISPLAY_X = 60f
private const val DISPLAY_Y = 61f
private const val DISPLAY_W = 1280f
private const val DISPLAY_H = 2856f
private const val DISPLAY_CORNER_RADIUS = 109f

// Padding fractions relative to frame size
private const val PAD_LEFT = DISPLAY_X / FRAME_WIDTH
private const val PAD_TOP = DISPLAY_Y / FRAME_HEIGHT
private const val PAD_RIGHT = (FRAME_WIDTH - DISPLAY_X - DISPLAY_W) / FRAME_WIDTH
private const val PAD_BOTTOM = (FRAME_HEIGHT - DISPLAY_Y - DISPLAY_H) / FRAME_HEIGHT

// ── Fake status bar (matches camera hole height, covers that area) ─
@Composable
private fun FakeStatusBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "10:10",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Wifi, null, Modifier.size(14.dp), tint = Color.White)
            Icon(Icons.Outlined.SignalCellular4Bar, null, Modifier.size(14.dp), tint = Color.White)
            Icon(Icons.Outlined.BatteryFull, null, Modifier.size(14.dp), tint = Color.White)
        }
    }
}

// ── 2x-tall status bar (used by the OG banner) ─────────────────────
@Composable
private fun BigStatusBar(wifiOff: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // No bottom padding — keeps the status bar flush against the
            // toolbar that follows.
            .padding(start = 24.dp, end = 24.dp, top = 16.dp)
            .height(48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "10:10",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (wifiOff) Icons.Outlined.WifiOff else Icons.Outlined.Wifi,
                null,
                Modifier.size(22.dp),
                tint = Color.White
            )
            Icon(Icons.Outlined.SignalCellular4Bar, null, Modifier.size(22.dp), tint = Color.White)
            Icon(Icons.Outlined.BatteryFull, null, Modifier.size(22.dp), tint = Color.White)
        }
    }
}

@Composable
private fun DeviceFrame(
    modifier: Modifier = Modifier,
    statusBar: @Composable () -> Unit = { FakeStatusBar() },
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        val frameW = maxWidth
        val fullFrameH = frameW * (FRAME_HEIGHT / FRAME_WIDTH)

        // Full-size phone. If it overflows the parent, the bottom gets cut naturally.
        Box(modifier = Modifier.requiredSize(frameW, fullFrameH)) {
            // App content behind the frame - status bar covers camera hole area
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = frameW * PAD_LEFT,
                        top = fullFrameH * PAD_TOP,
                        end = frameW * PAD_RIGHT,
                        bottom = fullFrameH * PAD_BOTTOM
                    )
                    .background(MaterialTheme.colorScheme.background)
            ) {
                statusBar()
                content()
            }
            // Device frame on top
            Image(
                painter = painterResource(R.drawable.device_frame_pixel9pro),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

// ── Marketing screenshot frame ─────────────────────────────────────
@Composable
private fun StoreScreenshotFrame(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .clipToBounds()
    ) {
        // Phone positioned below header; extends past bottom edge - gets clipped naturally.
        DeviceFrame(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 560.dp)
        ) {
            content()
        }
        // Header on top, drawn after so it's above the phone if they overlap
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                fontSize = 44.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.Serif,
                color = Color.White,
                textAlign = TextAlign.Center,
                lineHeight = 52.sp,
                letterSpacing = (-0.5).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = subtitle,
                fontSize = 22.sp,
                color = Color(0xFF888888),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
        }
    }
}

// ── Inline param slider (since ParamSlider is private) ─────────────
@Composable
private fun InlineParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    subtitle: String? = null
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
            onValueChange = { },
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Fake tool for the Tools settings screenshot ────────────────────
// ToolsScreen keys its user-facing copy off Tool.name, so the mock only
// needs the three real names; description/schema/execute are unused here.
private class FakeTool(override val name: String) : Tool {
    override val description: String = ""
    override val parametersSchema: String = ""
    override fun execute(arguments: String): String = ""
}

// ── System notification mock ───────────────────────────────────────
// Mirrors the real InferenceNotification (app icon + label, "Response
// ready" title, "<model> · <tokens>" line, Copy/Share actions) so the
// background-generation screenshot looks like the OS heads-up card.
@Composable
private fun NotificationCard(
    appName: String,
    title: String,
    body: String,
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.penrose_triangle),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = appName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.TextButton(onClick = { }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy))
                }
                androidx.compose.material3.TextButton(onClick = { }) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
            }
        }
    }
}

// ── User message with an attached image ────────────────────────────
// Production renders the attachment via Coil AsyncImage(imageUri), which
// can't decode inside Paparazzi. We mirror the same bubble layout (image
// thumbnail above a primary-coloured text bubble, right-aligned) with a
// painterResource so the vision shot renders the real photo.
private val UserBubbleShape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)

@Composable
private fun UserImageMessage(@DrawableRes imageRes: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(220.dp)
                    .aspectRatio(3f / 2f)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Surface(color = MaterialTheme.colorScheme.primary, shape = UserBubbleShape) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

// ── Tabbed generation/prompt/tools sheet (marketing stand-in) ───────
// Production's GenerationParamsSheet is a ModalBottomSheet, which
// Paparazzi can't drive into the expanded state. We mirror its content
// in a Surface that fills the visible display: a dimmed ConversationBar
// peeks at the top, then a rounded-top sheet with the drag handle, the
// three-tab PrimaryTabRow (with [selectedTab] active) and the tab body.
private const val TAB_PARAMS = 0
private const val TAB_PROMPT = 1
private const val TAB_TOOLS = 2

@Composable
private fun SheetScene(
    modelInfo: ModelInfo,
    icon: ImageVector,
    title: String,
    subtitle: String,
    selectedTab: Int,
    body: @Composable ColumnScope.() -> Unit,
) {
    StoreScreenshotFrame(icon = icon, title = title, subtitle = subtitle) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Dimmed chat peek behind the sheet.
                Box {
                    ConversationBar(modelInfo = modelInfo, modelStatus = null)
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(width = 32.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PrimaryTabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = Color.Transparent,
                        ) {
                            Tab(selected = selectedTab == TAB_PARAMS, onClick = { }, text = { Text(stringResource(R.string.tab_params)) })
                            Tab(selected = selectedTab == TAB_PROMPT, onClick = { }, text = { Text(stringResource(R.string.tab_prompt)) })
                            Tab(selected = selectedTab == TAB_TOOLS, onClick = { }, text = { Text(stringResource(R.string.tools)) })
                        }
                        body()
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PhoneParamsTabBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        InlineParamSlider(label = stringResource(R.string.context_size), value = 4096f, valueRange = 512f..8192f, valueDisplay = "4096")
        InlineParamSlider(label = stringResource(R.string.thinking_budget), value = 1024f, valueRange = 64f..4096f, valueDisplay = stringResource(R.string.tokens_value, 1024))
        InlineParamSlider(label = stringResource(R.string.temperature), value = 0.7f, valueRange = 0f..2f, valueDisplay = "0.70")
        InlineParamSlider(label = stringResource(R.string.top_p), value = 0.9f, valueRange = 0f..1f, valueDisplay = "0.90")
        InlineParamSlider(label = stringResource(R.string.repetition_penalty), value = 1.1f, valueRange = 1f..2f, valueDisplay = "1.10")
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.advanced), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(imageVector = Icons.Default.ExpandMore, contentDescription = null)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reset))
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ColumnScope.PhonePromptTabBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.system_prompt_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = stringResource(R.string.clear), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text(
                text = stringResource(R.string.screenshot_system_prompt_sample),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.saved_prompts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        PhoneSavedPromptCard(stringResource(R.string.screenshot_system_prompt_sample), selected = true)
        Spacer(modifier = Modifier.height(8.dp))
        PhoneSavedPromptCard(stringResource(R.string.screenshot_prompt_sample_code), selected = false)
        Spacer(modifier = Modifier.height(8.dp))
        PhoneSavedPromptCard(stringResource(R.string.screenshot_prompt_sample_translator), selected = false)
        Spacer(modifier = Modifier.height(8.dp))
        PhoneSavedPromptCard(stringResource(R.string.screenshot_prompt_sample_brainstorm), selected = false)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PhoneSavedPromptCard(text: String, selected: Boolean) {
    val border: BorderStroke = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        CardDefaults.outlinedCardBorder()
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth(), border = border, shape = RoundedCornerShape(12.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ColumnScope.PhoneToolsTabBody() {
    val tools = listOf(FakeTool("web_search"), FakeTool("web_fetch"), FakeTool("run_javascript"))
    val enabled = mapOf("web_search" to true, "web_fetch" to true, "run_javascript" to true)
    ToolsContent(
        tools = tools,
        enabledStates = enabled,
        onToolEnabledChanged = { _, _ -> },
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    )
}

// ── Test class ──────────────────────────────────────────────────────
@RunWith(Parameterized::class)
class StoreScreenshots(
    private val locale: String,
    private val localeName: String
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun locales() = listOf(
            arrayOf("en", "English"),
            arrayOf("es", "Spanish"),
            arrayOf("pt", "Portuguese"),
            arrayOf("fr", "French"),
            arrayOf("de", "German"),
            arrayOf("it", "Italian"),
            arrayOf("pl", "Polish"),
            arrayOf("uk", "Ukrainian"),
            arrayOf("ro", "Romanian"),
            arrayOf("tr", "Turkish"),
            arrayOf("ar", "Arabic"),
            arrayOf("zh", "Chinese"),
            arrayOf("ja", "Japanese"),
            arrayOf("ko", "Korean"),
            arrayOf("in", "Indonesian"),
            arrayOf("hi", "Hindi"),
            arrayOf("vi", "Vietnamese"),
            arrayOf("th", "Thai"),
            arrayOf("nl", "Dutch"),
            arrayOf("iw", "Hebrew"),
            arrayOf("cs", "Czech"),
            arrayOf("sv", "Swedish"),
            arrayOf("bn", "Bengali"),
            arrayOf("ms", "Malay"),
            arrayOf("fil", "Filipino"),
            arrayOf("nb", "Norwegian"),
            arrayOf("da", "Danish"),
            arrayOf("fi", "Finnish")
        )
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_9_PRO.copy(locale = locale),
        renderingMode = SessionParams.RenderingMode.NORMAL,
        showSystemUi = false,
        useDeviceResolution = true
    )

    // On real Arabic-locale devices, Android flips LayoutDirection automatically.
    // Paparazzi's locale config doesn't — so we flip it here to match runtime behavior.
    @Composable
    private fun LocaleLayout(content: @Composable () -> Unit) {
        val direction = if (locale == "ar" || locale == "iw") LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            content()
        }
    }

    @Composable
    private fun gemma4Model(): ModelInfo {
        // Description in chat/params shots stays minimal (just size) \u2014
        // ConversationBar renders "<name>  \u00B7  <description>", so this
        // gives the production-style "Gemma 4 E2B  \u00B7  3.11Gb" line
        // without the noisy provider/category prefix.
        return ModelInfo(
            name = "Gemma 4 E2B",
            filename = "gemma-4-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/model.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "3.11Gb",
            logoRes = R.drawable.logo_google
        )
    }

    // READY document chip for the documents (RAG) scene. Filename stays
    // English in every locale — realistic, and it needs no translation.
    private fun sampleDocument() = RagDocumentEntity(
        id = "doc",
        sessionId = "session",
        displayName = "rental-agreement.pdf",
        mimeType = "application/pdf",
        sizeBytes = 245_000L,
        status = RagDocumentEntity.STATUS_READY,
        chunkCount = 18,
        embeddingDim = 768,
        embeddingModel = "embeddinggemma-300m-Q4_0.gguf",
        createdAt = 0L,
    )

    // Scene 0: Hero / marketing slide
    @Test
    fun scene0_hero() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val titleText = buildAnnotatedString {
                    append(stringResource(R.string.screenshot_hero_title_prefix))
                    append("\n")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(stringResource(R.string.screenshot_hero_title_emphasis))
                    }
                    append(" ")
                    append(stringResource(R.string.screenshot_hero_title_suffix))
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0A))
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(140.dp))
                    Image(
                        painter = painterResource(R.drawable.penrose_triangle),
                        contentDescription = null,
                        modifier = Modifier.size(140.dp)
                    )
                    Spacer(modifier = Modifier.height(56.dp))
                    // Title with serif font - lighter weight, more stretched
                    Text(
                        text = titleText,
                        fontSize = 68.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily.Serif,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 78.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    // Subtitle with sans-serif font
                    Text(
                        text = stringResource(R.string.screenshot_hero_subtitle),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFBBBBBB),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.screenshot_hero_providers_label),
                        fontSize = 16.sp,
                        color = Color(0xFF888888),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    // Stacked provider avatars - overlap each other with black border for separation
                    val logos = listOf(
                        R.drawable.logo_google,
                        R.drawable.logo_meta,
                        R.drawable.logo_qwen,
                        R.drawable.logo_microsoft,
                        R.drawable.logo_mistral,
                        R.drawable.logo_nvidia,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((-24).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        logos.forEach { logo ->
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0A0A0A))
                            ) {
                                Image(
                                    painter = painterResource(logo),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(140.dp))
                }
            } }
        }
    }

    // Scene 1: Choose model
    @Test
    fun scene1_chooseModel() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val efficient = stringResource(R.string.model_category_efficient)
                val lightweight = stringResource(R.string.model_category_lightweight)
                val general = stringResource(R.string.model_category_general)
                val reasoning = stringResource(R.string.model_category_reasoning)
                val compactReasoning = stringResource(R.string.model_category_compact_reasoning)
                val enterprise = stringResource(R.string.model_category_enterprise)

                val models = listOf(
                    ModelWithStatus(ModelInfo("Gemma 4 E2B", "g4e2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-03-25"), "Google \u00B7 $efficient \u00B7 3.11Gb", R.drawable.logo_google), true),
                    ModelWithStatus(ModelInfo("Llama 3.2 3B", "llama32.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-09-25"), "Meta \u00B7 $general \u00B7 1.88Gb", R.drawable.logo_meta), true),
                    ModelWithStatus(ModelInfo("Qwen 3.5 2B", "q35-2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-02-27"), "Alibaba \u00B7 $general \u00B7 1.07Gb", R.drawable.logo_qwen), true),
                    ModelWithStatus(ModelInfo("Phi-4 mini", "phi4.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-01-15"), "Microsoft \u00B7 $lightweight \u00B7 2.49Gb", R.drawable.logo_microsoft), true),
                    ModelWithStatus(ModelInfo("Ministral 3B", "min3b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-10-16"), "Mistral \u00B7 $enterprise \u00B7 1.85Gb", R.drawable.logo_mistral), true),
                    ModelWithStatus(ModelInfo("Nemotron 3 Nano", "nem4b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-12-15"), "NVIDIA \u00B7 $compactReasoning \u00B7 2.84Gb", R.drawable.logo_nvidia), true),
                )

                StoreScreenshotFrame(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.screenshot_choose_model),
                    subtitle = stringResource(R.string.screenshot_choose_model_sub)
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            ConversationBar(modelInfo = null, modelStatus = null)
                            androidx.compose.material3.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    models.filter { it.isDownloaded }.forEach { m ->
                                        Model(model = m.model) { }
                                    }
                                    androidx.compose.material3.HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    androidx.compose.material3.TextButton(
                                        onClick = { },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                        Text(stringResource(R.string.browse_more_models))
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            } }
        }
    }

    // Scene 2: Chat with thinking
    @Test
    fun scene2_chat() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val question = stringResource(R.string.screenshot_chat_question)
                val response = stringResource(R.string.screenshot_chat_response)

                val gemma4 = gemma4Model()
                StoreScreenshotFrame(
                    icon = Icons.Outlined.WifiOff,
                    title = stringResource(R.string.screenshot_chat),
                    subtitle = stringResource(R.string.screenshot_chat_sub)
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column {
                            ConversationBar(modelInfo = gemma4, modelStatus = null)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                com.druk.lmplayground.conversation.Message(
                                    msg = Message("User", question),
                                    isUserMe = true,
                                    showActions = false
                                )
                                com.druk.lmplayground.conversation.Message(
                                    msg = Message(
                                        author = "Assistant",
                                        content = "<think>Let me explain machine learning clearly and concisely.</think>\n$response",
                                        thinkingDurationSeconds = 3,
                                        thinkingTokens = 42,
                                        responseTokens = 156,
                                        responseDurationSeconds = 4.2f
                                    ),
                                    isUserMe = false,
                                    showActions = true
                                )
                            }
                            UserInput(onMessageSent = {})
                        }
                    }
                }
            } }
        }
    }

    // Scene 3: Chat about a document (RAG)
    @Test
    fun scene3_documents() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val question = stringResource(R.string.screenshot_documents_question)
                val response = stringResource(R.string.screenshot_documents_response)
                val gemma4 = gemma4Model()
                StoreScreenshotFrame(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.screenshot_documents),
                    subtitle = stringResource(R.string.screenshot_documents_sub)
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column {
                            ConversationBar(modelInfo = gemma4, modelStatus = null)
                            // In the app the chips dock above the input, but
                            // the device frame crops the bottom of the screen
                            // — so for the store shot the chip sits under the
                            // bar, where it can actually be seen.
                            DocumentChipsRow(
                                documents = listOf(sampleDocument()),
                                onRemove = {},
                                onOpen = {},
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                com.druk.lmplayground.conversation.Message(
                                    msg = Message("User", question),
                                    isUserMe = true,
                                    showActions = false
                                )
                                com.druk.lmplayground.conversation.Message(
                                    msg = Message(
                                        author = "Assistant",
                                        content = response,
                                        responseTokens = 52,
                                        responseDurationSeconds = 1.8f
                                    ),
                                    isUserMe = false,
                                    showActions = true
                                )
                            }
                            UserInput(onMessageSent = {})
                        }
                    }
                }
            } }
        }
    }

    // Scene 4: Chat about an image (vision)
    @Test
    fun scene4_chatWithImage() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val question = stringResource(R.string.screenshot_vision_question)
                val response = stringResource(R.string.screenshot_vision_response)
                val gemma4 = gemma4Model()
                StoreScreenshotFrame(
                    icon = Icons.Outlined.AddPhotoAlternate,
                    title = stringResource(R.string.screenshot_vision),
                    subtitle = stringResource(R.string.screenshot_vision_sub)
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column {
                            ConversationBar(modelInfo = gemma4, modelStatus = null)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                UserImageMessage(R.drawable.sample_vision_sheep, question)
                                com.druk.lmplayground.conversation.Message(
                                    msg = Message(
                                        author = "Assistant",
                                        content = response,
                                        responseTokens = 38,
                                        responseDurationSeconds = 1.4f
                                    ),
                                    isUserMe = false,
                                    showActions = true
                                )
                            }
                            UserInput(onMessageSent = {})
                        }
                    }
                }
            } }
        }
    }

    // Scene 7: Generation parameters (chat sheet, Params tab)
    @Test
    fun scene7_generationParams() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                SheetScene(
                    modelInfo = gemma4Model(),
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.screenshot_fine_tune),
                    subtitle = stringResource(R.string.screenshot_fine_tune_sub),
                    selectedTab = TAB_PARAMS,
                ) {
                    PhoneParamsTabBody()
                }
            } }
        }
    }

    // Scene 5: Reusable system prompts (chat sheet, Prompt tab)
    @Test
    fun scene5_systemPrompts() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                SheetScene(
                    modelInfo = gemma4Model(),
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.screenshot_prompts),
                    subtitle = stringResource(R.string.screenshot_prompts_sub),
                    selectedTab = TAB_PROMPT,
                ) {
                    PhonePromptTabBody()
                }
            } }
        }
    }

    // Scene 6: Tools — per-tool toggles (chat sheet, Tools tab)
    @Test
    fun scene6_tools() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                SheetScene(
                    modelInfo = gemma4Model(),
                    icon = Icons.Outlined.Build,
                    title = stringResource(R.string.screenshot_tools),
                    subtitle = stringResource(R.string.screenshot_tools_sub),
                    selectedTab = TAB_TOOLS,
                ) {
                    PhoneToolsTabBody()
                }
            } }
        }
    }

}

// ── OG/Twitter banner ───────────────────────────────────────────────
// Renders a 2400×1260 snapshot of two phones (model picker + chat) on
// a dark canvas. After recording, downscale to 1200×630 and write to
// docs/banner.png — that single asset is referenced by both the website
// (og:image / twitter:image / JSON-LD) and the README. English-only.
class OgBanner {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_9_PRO.copy(
            screenWidth = 1260,
            screenHeight = 2400,
            orientation = ScreenOrientation.LANDSCAPE,
            density = Density.XHIGH,
            xdpi = 320,
            ydpi = 320,
            locale = "en"
        ),
        renderingMode = SessionParams.RenderingMode.NORMAL,
        showSystemUi = false,
        useDeviceResolution = true
    )

    @Composable
    private fun ModelPickerContent() {
        val efficient = stringResource(R.string.model_category_efficient)
        val lightweight = stringResource(R.string.model_category_lightweight)
        val general = stringResource(R.string.model_category_general)
        val compactReasoning = stringResource(R.string.model_category_compact_reasoning)
        val enterprise = stringResource(R.string.model_category_enterprise)

        val models = listOf(
            ModelWithStatus(ModelInfo("Gemma 4 E2B", "g4e2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-03-25"), "Google · $efficient · 3.11Gb", R.drawable.logo_google), true),
            ModelWithStatus(ModelInfo("Llama 3.2 3B", "llama32.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-09-25"), "Meta · $general · 1.88Gb", R.drawable.logo_meta), true),
            ModelWithStatus(ModelInfo("Qwen 3.5 2B", "q35-2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-02-27"), "Alibaba · $general · 1.07Gb", R.drawable.logo_qwen), true),
            ModelWithStatus(ModelInfo("Phi-4 mini", "phi4.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-01-15"), "Microsoft · $lightweight · 2.49Gb", R.drawable.logo_microsoft), true),
            ModelWithStatus(ModelInfo("Ministral 3B", "min3b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-10-16"), "Mistral · $enterprise · 1.85Gb", R.drawable.logo_mistral), true),
            ModelWithStatus(ModelInfo("Nemotron 3 Nano", "nem4b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-12-15"), "NVIDIA · $compactReasoning · 2.84Gb", R.drawable.logo_nvidia), true),
        )

        ScaledFonts {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ConversationBar(modelInfo = null, modelStatus = null)
                    androidx.compose.material3.Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            // ≥15% margin start/end of inner phone width (~508dp),
                            // makes the picker read like a centered dialog.
                            .padding(horizontal = 76.dp, vertical = 16.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column {
                            models.filter { it.isDownloaded }.forEach { m ->
                                Model(model = m.model) { }
                            }
                        }
                    }
                }
            }
        }
    }

    // Bumps fontScale by ~1.15x within the wrapped subtree — adds roughly
    // +2sp to all `sp`-sized text without touching individual composables.
    // Density (and therefore dp/px math) is preserved.
    @Composable
    private fun ScaledFonts(content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides ComposeDensity(
                density = base.density,
                fontScale = base.fontScale * 1.15f
            )
        ) {
            content()
        }
    }

    // ── Real app UserInput (with the filled-bulb thinking toggle) +
    //    Android gesture nav pill. The pill's Surface uses the same
    //    tonalElevation as UserInput so they share one elevated tone.
    @Composable
    private fun ChatInputArea() {
        Column {
            UserInput(
                supportsThinking = true,
                thinkingEnabled = true,
                onMessageSent = {}
            )
            Surface(tonalElevation = 2.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(108.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White)
                    )
                }
            }
        }
    }

    @Composable
    private fun ChatContent() {
        val efficient = stringResource(R.string.model_category_efficient)
        val gemma4 = ModelInfo(
            name = "Gemma 4 E2B",
            filename = "gemma-4-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/model.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google · $efficient · 3.11Gb",
            logoRes = R.drawable.logo_google
        )

        ScaledFonts {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column {
                    ConversationBar(modelInfo = gemma4, modelStatus = null)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        // Anchor messages to the bottom so the recent thread sits
                        // just above the input bar even when the phone frame is
                        // much taller than the chat content (OG banner case).
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        com.druk.lmplayground.conversation.Message(
                            msg = Message("User", "What is machine learning?"),
                            isUserMe = true,
                            showActions = false
                        )
                        com.druk.lmplayground.conversation.Message(
                            msg = Message(
                                author = "Assistant",
                                // Mirrors the `phone.a` example shown in the
                                // website hero (docs/i18n.js): an intro
                                // paragraph followed by a bulleted list of
                                // key concepts. Renderer parses markdown.
                                content = "<think>Let me explain machine learning clearly and concisely.</think>\n" +
                                    "Machine learning is a branch of artificial intelligence where computers **learn from data** instead of being explicitly programmed.\n\n" +
                                    "Key concepts:\n\n" +
                                    "• **Training**: feeding data to algorithms\n" +
                                    "• **Models**: learned patterns from data\n" +
                                    "• **Inference**: making predictions on new data",
                                thinkingDurationSeconds = 3,
                                thinkingTokens = 42,
                                responseTokens = 240,
                                responseDurationSeconds = 5.1f
                            ),
                            isUserMe = false,
                            showActions = true
                        )
                    }
                    ChatInputArea()
                }
            }
        }
    }

    @Test
    fun ogBanner() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0B10))
                ) {
                    // 50/50 horizontal split. Each phone outer Box is 540dp
                    // wide (≈1.7× the canvas-tall sizing — pushed close to
                    // the 600dp half-width limit), giving inner phone screen
                    // ≈508dp and total phone height ≈1072dp.
                    //
                    // Phone 1 (model picker, LEFT half): top anchored at
                    // 15% (y≈94.5), bottom extends well past canvas (~185%).
                    // Phone 2 (chat, RIGHT half): bottom anchored at 85%
                    // (y≈535.5), top extends well past canvas top (~-85%).
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = (-300).dp, y = 315.5.dp)
                            .width(540.dp)
                    ) {
                        DeviceFrame(statusBar = { BigStatusBar(wifiOff = true) }) {
                            ModelPickerContent()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = 300.dp, y = (-315.5).dp)
                            .width(540.dp)
                    ) {
                        DeviceFrame(statusBar = { BigStatusBar(wifiOff = true) }) {
                            ChatContent()
                        }
                    }
                }
            }
        }
    }
}
