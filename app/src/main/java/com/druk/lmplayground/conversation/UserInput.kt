package com.druk.lmplayground.conversation

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.druk.lmplayground.R
import com.druk.lmplayground.dictation.DictationState

enum class UserInputStatus {
    IDLE,
    NOT_LOADED,
    GENERATING
}

@Preview
@Composable
fun UserInputPreview() {
    UserInput(onMessageSent = {})
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserInput(
    /**
     * On tablets / wide layouts, the parent renders a permanent sidebar so the
     * chat pane only occupies part of the screen width. The original
     * tonally-elevated bottom dock then looks like a half-coloured band that
     * stops at the sidebar. Setting this to true flattens the surface so the
     * input flows into the chat pane background.
     */
    integrateWithSurface: Boolean = false,
    /**
     * On the tablet (flat-surface) path the parent toggles this true only when
     * the message list actually has content scrolled behind the input dock —
     * so the divider acts as a scroll-edge indicator rather than always-on
     * chrome. Defaults to [integrateWithSurface] for the standalone preview.
     */
    showTopDivider: Boolean = integrateWithSurface,
    modifier: Modifier = Modifier,
    /**
     * When supplied, the input dock blurs the chat content scrolling behind it
     * (frosted glass) instead of painting an opaque tonal surface. The blur
     * region spans the full dock — including the area behind the navigation bar
     * and keyboard — since the nav/ime padding lives on the inner content.
     */
    hazeState: HazeState? = null,
    hazeStyle: HazeStyle = HazeStyle.Unspecified,
    status: UserInputStatus = UserInputStatus.IDLE,
    focusRequester: FocusRequester = remember { FocusRequester() },
    supportsThinking: Boolean = false,
    thinkingEnabled: Boolean = true,
    onThinkingToggle: () -> Unit = {},
    supportsVision: Boolean = false,
    cameraAvailable: Boolean = false,
    attachedImageUri: Uri? = null,
    onAttachImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onAttachDocument: () -> Unit = {},
    onClearImage: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
    onMessageSent: (String) -> Unit,
    onCancelClicked: () -> Unit = {},
    resetScroll: () -> Unit = {},
    dictationState: DictationState = DictationState.Idle,
    /** 0..1 while the dictation model downloads, negative if queued, null if not. */
    dictationDownloadProgress: Float? = null,
    onMicPressed: () -> Unit = {},
    onMicReleased: () -> Unit = {},
    /**
     * The user edited or sent the text while dictation was still writing into
     * it. The caller must stop dictation and deliver nothing more.
     */
    onDictationAbandoned: () -> Unit = {},
    /**
     * A finished transcript waiting to be inserted at the cursor. The composer
     * owns the text field, so the caller hands the text over and is told when
     * it landed via [onTranscriptConsumed].
     */
    pendingTranscript: String? = null,
    onTranscriptConsumed: () -> Unit = {},
) {

    var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    // Text the composer held before dictation began. Recognized words are
    // written on top of it as they arrive, so re-writing never compounds.
    // Null whenever dictation is not writing into the field.
    var dictationBase by remember { mutableStateOf<TextFieldValue?>(null) }

    LaunchedEffect(dictationState) {
        val listening = dictationState as? DictationState.Listening ?: return@LaunchedEffect
        val base = dictationBase ?: textState.also { dictationBase = it }
        textState = base.insertAtCursor(listening.text)
    }

    LaunchedEffect(pendingTranscript) {
        val transcript = pendingTranscript ?: return@LaunchedEffect
        // Terminal event: the final text replaces whatever was written live,
        // and an empty one (nothing heard) restores the base.
        textState = (dictationBase ?: textState).insertAtCursor(transcript)
        dictationBase = null
        onTranscriptConsumed()
    }

    // The field is the user's again the moment they change it: dictation
    // stops rather than rewriting their edit with a late tail of the phrase.
    fun releaseFromDictation() {
        if (dictationBase == null) return
        dictationBase = null
        onDictationAbandoned()
    }

    // Used to decide if the keyboard should be shown
    var textFieldFocusState by remember { mutableStateOf(false) }

    var dragAccumulator by remember { mutableStateOf(0f) }
    val swipeThreshold = -150f // negative = upward
    val draggableState = rememberDraggableState { delta ->
        dragAccumulator += delta
    }

    val frosted = hazeState != null
    Surface(
        // Frosted: paint nothing of our own and let hazeEffect blur the chat
        // behind the whole dock. Otherwise keep the original tonal surface.
        modifier = if (frosted) {
            Modifier.fillMaxWidth().hazeEffect(hazeState!!, hazeStyle)
        } else {
            Modifier
        },
        color = if (frosted) Color.Transparent else MaterialTheme.colorScheme.surface,
        tonalElevation = if (frosted || integrateWithSurface) 0.dp else 2.dp,
        contentColor = MaterialTheme.colorScheme.secondary
    ) {
        Column(
            modifier = modifier.draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStarted = { dragAccumulator = 0f },
                onDragStopped = {
                    if (dragAccumulator < swipeThreshold) {
                        onSwipeUp()
                    }
                    dragAccumulator = 0f
                }
            )
        ) {
            // Scroll-edge divider: drawn only when the parent says the message
            // list has content hidden behind the input. At the bottom of the
            // chat we let the input float without a line.
            // Left inset (matches the top-bar divider) keeps the line from
            // butting against the master card on tablet layouts.
            if (showTopDivider) {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            // Staged image preview (shown until the message is sent): a rounded
            // thumbnail with a circular remove button overlapping its top-right
            // corner, mirroring the Google AI Edge attachment style.
            if (attachedImageUri != null) {
                Box(
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
                ) {
                    AsyncImage(
                        model = attachedImageUri,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            // inset from the Box top/end so the button can overlap the corner
                            .padding(top = 6.dp, end = 6.dp)
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(12.dp)
                            )
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.inverseSurface)
                            .clickable(onClick = onClearImage),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove image",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            UserInputText(
                    status,
                    focusRequester = focusRequester,
                    supportsThinking = supportsThinking,
                    thinkingEnabled = thinkingEnabled,
                    onThinkingToggle = onThinkingToggle,
                    supportsVision = supportsVision,
                    cameraAvailable = cameraAvailable,
                    onAttachImage = onAttachImage,
                    onTakePhoto = onTakePhoto,
                    onAttachDocument = onAttachDocument,
                    onMicPressed = onMicPressed,
                    onMicReleased = onMicReleased,
                    dictationDownloadProgress = dictationDownloadProgress,
                    textFieldValue = textState,
                    onTextChanged = {
                        // Selection-only changes (moving the caret) keep
                        // dictation going; any change to the text ends it.
                        if (it.text != textState.text) releaseFromDictation()
                        textState = it
                    },
                    // Only show the keyboard if there's no input selector and text field has focus
                    keyboardShown = textFieldFocusState,
                    // Close extended selector if text field receives focus
                    onTextFieldFocused = { focused ->
                        if (focused) {
                            resetScroll()
                        }
                        textFieldFocusState = focused
                    },
                    sendMessageEnabled = textState.text.isNotBlank(),
                    onMessageSent = {
                        releaseFromDictation()
                        onMessageSent(textState.text)
                        // Reset text field and close keyboard
                        textState = TextFieldValue()
                        // Move scroll to bottom
                        resetScroll()
                    },
                    onCancelClicked = onCancelClicked,
                    // On tablet landscape (integrateWithSurface) reduce the row's
                    // vertical padding from 8dp → 2dp so the input dock saves ~12dp
                    // of message area — vertical space is the constrained dimension
                    // when the IME is open.
                    compact = integrateWithSurface
                )
        }
    }
}

/**
 * Splice [text] in at the caret, replacing any selection, and leave the caret
 * after the inserted text. Spacing is normalized so dictating twice in a row
 * doesn't run words together.
 */
private fun TextFieldValue.insertAtCursor(text: String): TextFieldValue {
    if (text.isEmpty()) return this
    val start = selection.min.coerceIn(0, this.text.length)
    val end = selection.max.coerceIn(0, this.text.length)
    val before = this.text.substring(0, start)
    val after = this.text.substring(end)
    val separator = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val inserted = separator + text
    return TextFieldValue(
        text = before + inserted + after,
        selection = TextRange(start + inserted.length),
    )
}

@OptIn(ExperimentalLayoutApi::class)
fun Modifier.clearFocusOnKeyboardDismiss(): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    var keyboardAppearedSinceLastFocused by remember { mutableStateOf(false) }
    if (isFocused) {
        val imeIsVisible = WindowInsets.isImeVisible
        val focusManager = LocalFocusManager.current
        LaunchedEffect(imeIsVisible) {
            if (imeIsVisible) {
                keyboardAppearedSinceLastFocused = true
            } else if (keyboardAppearedSinceLastFocused) {
                focusManager.clearFocus()
            }
        }
    }
    onFocusEvent {
        if (isFocused != it.isFocused) {
            isFocused = it.isFocused
            if (isFocused) {
                keyboardAppearedSinceLastFocused = false
            }
        }
    }
}

val KeyboardShownKey = SemanticsPropertyKey<Boolean>("KeyboardShownKey")
var SemanticsPropertyReceiver.keyboardShownProperty by KeyboardShownKey

@ExperimentalFoundationApi
@Composable
private fun UserInputText(
    status: UserInputStatus,
    focusRequester: FocusRequester,
    supportsThinking: Boolean = false,
    thinkingEnabled: Boolean = true,
    onThinkingToggle: () -> Unit = {},
    supportsVision: Boolean = false,
    cameraAvailable: Boolean = false,
    onAttachImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onAttachDocument: () -> Unit = {},
    onMicPressed: () -> Unit = {},
    onMicReleased: () -> Unit = {},
    dictationDownloadProgress: Float? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onTextChanged: (TextFieldValue) -> Unit,
    textFieldValue: TextFieldValue,
    keyboardShown: Boolean,
    onTextFieldFocused: (Boolean) -> Unit,
    sendMessageEnabled: Boolean,
    onMessageSent: () -> Unit,
    onCancelClicked: () -> Unit,
    compact: Boolean = false
) {
    val a11ylabel = stringResource(id = R.string.textfield_desc)
    // The attach (+) button shows for every loaded model (documents are
    // model-agnostic); the photo entries inside its menu stay vision-gated.
    val showAttach = status != UserInputStatus.NOT_LOADED
    val hasLeftButtons = supportsThinking || showAttach
    val textStartPadding = if (hasLeftButtons) 4.dp else 32.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 2.dp else 8.dp)
            .heightIn(min = if (compact) 40.dp else 48.dp, max = 320.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showAttach) {
            val isDisabled = status == UserInputStatus.GENERATING
            var menuExpanded by remember { mutableStateOf(false) }
            val density = LocalDensity.current
            Box {
                IconButton(
                    onClick = {
                        // Vision models get a menu (photos + document);
                        // text-only models go straight to the document picker.
                        if (supportsVision) menuExpanded = true else onAttachDocument()
                    },
                    enabled = !isDisabled,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Attach",
                        modifier = if (isDisabled) Modifier.alpha(0.8f) else Modifier,
                        tint = LocalContentColor.current
                    )
                }
                if (menuExpanded) {
                    // Material3 DropdownMenu's `offset` is ignored for the
                    // upward-opening case, so we drive a Popup with an explicit
                    // position: the menu's bottom sits a fixed gap above the top
                    // of the anchor row (the input block).
                    val gapPx = with(density) { 16.dp.roundToPx() }
                    val positionProvider = remember(gapPx) {
                        object : PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: IntRect,
                                windowSize: IntSize,
                                layoutDirection: LayoutDirection,
                                popupContentSize: IntSize
                            ): IntOffset {
                                val x = anchorBounds.left
                                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                val y = (anchorBounds.top - gapPx - popupContentSize.height)
                                    .coerceAtLeast(0)
                                return IntOffset(x, y)
                            }
                        }
                    }
                    Popup(
                        popupPositionProvider = positionProvider,
                        onDismissRequest = { menuExpanded = false },
                        properties = PopupProperties(focusable = true)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shadowElevation = 3.dp
                        ) {
                            Column(modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp)) {
                                if (cameraAvailable) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.take_photo)) },
                                        leadingIcon = {
                                            Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null)
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onTakePhoto()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.choose_from_library)) },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Outlined.Image, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onAttachImage()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attach_document)) },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Outlined.Description, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onAttachDocument()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        if (supportsThinking) {
            val isDisabled = status == UserInputStatus.GENERATING
            IconButton(
                onClick = onThinkingToggle,
                enabled = !isDisabled,
                // When the attach (+) button precedes it, pull the bulb left so the
                // two sit as a tight pair rather than a full icon-button gap apart.
                modifier = if (showAttach) {
                    Modifier.offset(x = (-12).dp)
                } else {
                    Modifier.padding(start = 4.dp)
                }
            ) {
                Icon(
                    imageVector = if (thinkingEnabled) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                    contentDescription = if (thinkingEnabled) "Disable thinking" else "Enable thinking",
                    modifier = if (isDisabled) Modifier.alpha(0.8f) else Modifier,
                    tint = LocalContentColor.current
                )
            }
        }

        Box(Modifier.weight(1f)) {
            UserInputTextField(
                true,
                focusRequester,
                textFieldValue,
                onTextChanged,
                onTextFieldFocused,
                keyboardType,
                textStartPadding,
                Modifier.semantics {
                    contentDescription = a11ylabel
                    keyboardShownProperty = keyboardShown
                }
            )
        }

        val border = if (!sendMessageEnabled) {
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        } else {
            null
        }

        val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

        val buttonColors = ButtonDefaults.buttonColors(
            disabledContainerColor = Color.Transparent,
            disabledContentColor = disabledContentColor
        )

        // Push to talk: dictation runs for exactly as long as the button is
        // held, so there is no separate stop to hunt for and no recording left
        // running by accident. Dictation works without a chat model loaded (it
        // runs its own engine), so the mic is live except while generating.
        val micEnabled = status != UserInputStatus.GENERATING
        // Driven by the finger, not by dictation state: the circle must appear
        // the instant the button goes down (loading the model takes a moment)
        // and clear the instant it comes up — even though the recognizer keeps
        // working through the audio already captured.
        var micPressed by remember { mutableStateOf(false) }
        val pulse = rememberInfiniteTransition(label = "mic")
        val pulseScale by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        )
        // Applied directly: routing it through animateFloatAsState would set a
        // spring chasing a moving target, which damps the oscillation flat.
        val restScale = if (micPressed) pulseScale else 1f
        val circleAlpha by animateFloatAsState(
            targetValue = if (micPressed) 1f else 0f,
            label = "micCircle",
        )

        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(48.dp)
                .then(
                    if (micEnabled) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    micPressed = true
                                    onMicPressed()
                                    // The result is ignored on purpose. A
                                    // cancelled gesture — the finger drifting,
                                    // or the input dock's swipe-up winning —
                                    // used to discard, which threw away words
                                    // the user had already said. Finishing is
                                    // always the safer answer: unwanted text can
                                    // be deleted, lost speech cannot.
                                    tryAwaitRelease()
                                    micPressed = false
                                    onMicReleased()
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            // The filled circle scales; the 48dp touch target never does, so
            // the finger keeps hitting the same place.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = restScale
                        scaleY = restScale
                        alpha = circleAlpha
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            // The model is ~700 MB, so the wait needs to be visible where the
            // user asked for it rather than only on the models screen.
            if (dictationDownloadProgress != null) {
                if (dictationDownloadProgress >= 0f) {
                    CircularProgressIndicator(
                        progress = { dictationDownloadProgress },
                        modifier = Modifier.size(44.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    // Queued with no byte count yet (waiting for network).
                    CircularProgressIndicator(
                        modifier = Modifier.size(44.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = stringResource(R.string.dictation_start),
                modifier = if (!micEnabled) Modifier.alpha(0.8f) else Modifier,
                tint = lerp(
                    LocalContentColor.current,
                    MaterialTheme.colorScheme.onPrimary,
                    circleAlpha,
                )
            )
        }

        // Send button
        Box {
            when (status) {
                UserInputStatus.IDLE, UserInputStatus.NOT_LOADED -> {
                    Button(
                        modifier = Modifier.align(Alignment.Center).padding(end = 8.dp),
                        enabled = sendMessageEnabled && status == UserInputStatus.IDLE,
                        onClick = onMessageSent,
                        colors = buttonColors,
                        border = border,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            stringResource(id = R.string.send),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
                UserInputStatus.GENERATING -> {
                    Button(
                        modifier = Modifier.align(Alignment.Center).padding(end = 8.dp),
                        onClick = onCancelClicked,
                        colors = buttonColors,
                        border = border,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            stringResource(id = R.string.stop),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.UserInputTextField(
    isEnabled: Boolean,
    focusRequester: FocusRequester,
    textFieldValue: TextFieldValue,
    onTextChanged: (TextFieldValue) -> Unit,
    onTextFieldFocused: (Boolean) -> Unit,
    keyboardType: KeyboardType,
    startPadding: Dp,
    modifier: Modifier = Modifier
) {
    var lastFocusState by remember { mutableStateOf(false) }
    BasicTextField(
        value = textFieldValue,
        onValueChange = { onTextChanged(it) },
        modifier = modifier
            .padding(start = startPadding)
            .align(Alignment.CenterStart)
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .clearFocusOnKeyboardDismiss()
            .onFocusChanged { state ->
                if (lastFocusState != state.isFocused) {
                    onTextFieldFocused(state.isFocused)
                }
                lastFocusState = state.isFocused
            },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.None
        ),
        enabled = isEnabled,
        maxLines = 4,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurfaceVariant),
        textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current)
    )

    val disableContentColor =
        MaterialTheme.colorScheme.onSurfaceVariant
    if (textFieldValue.text.isEmpty()) {
        Text(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = startPadding),
            text = stringResource(R.string.textfield_hint),
            style = MaterialTheme.typography.bodyLarge.copy(color = disableContentColor)
        )
    }
}
