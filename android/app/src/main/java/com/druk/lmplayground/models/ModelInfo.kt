package com.druk.lmplayground.models

import android.net.Uri
import androidx.annotation.DrawableRes
import com.druk.lmplayground.storage.StoragePreferences
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Static model definition - does not contain download status.
 * Chat template parameters (prefix, suffix, stop sequences) are read
 * from the GGUF file's embedded Jinja template at load time.
 */
/**
 * How a model behaves when asked to think, as measured rather than as its chat
 * template advertises. The two disagree often enough to matter: templates
 * declare a thinking mode the weights never use, and reasoning-tuned models
 * keep reasoning whatever the flag says. Gating the UI on the template alone
 * gives the user a switch that does nothing, in one direction or the other.
 */
enum class ThinkingMode {
    /** Never produces a thinking block, whatever the template claims. */
    NONE,
    /** The toggle works: thinking on produces a block, off suppresses it. */
    OPTIONAL,
    /** Always reasons; "off" cannot be honoured. */
    ALWAYS,
    /** Not measured — fall back to the template's own capability flag. */
    UNKNOWN,
}

data class ModelInfo(
    val name: String,
    val filename: String,
    val remoteUri: Uri? = null,
    val releaseDate: LocalDate? = null,
    val description: String,
    @param:DrawableRes val logoRes: Int = 0,
    val supportedLanguages: List<String> = emptyList(),
    // Capability hints shown as badges in the model list. These are best-effort
    // static values for the catalog; the authoritative source is the GGUF's
    // chat template, read only after the model loads. Once a model has been
    // loaded, its detected capabilities are cached and override these via
    // [resolveCapabilities].
    val supportsTools: Boolean = false,
    val supportsThinking: Boolean = false,
    // Measured behaviour, which overrides supportsThinking for UI purposes.
    val thinkingMode: ThinkingMode = ThinkingMode.UNKNOWN,
    // Legacy catalog entry kept only so an already-downloaded file is still
    // recognized (name/logo/description) instead of falling back to a nameless
    // custom model. Never offered for download: hidden from the list unless the
    // file is present on disk.
    val deprecated: Boolean = false,
    // Vision (image input) support: when a model ships a separate multimodal
    // projector (mmproj) GGUF, these point at it. Presence of [mmprojFilename]
    // marks the model as vision-capable ([isVision]).
    val mmprojFilename: String? = null,
    val mmprojUri: Uri? = null,
) {
    val isCustom: Boolean get() = remoteUri == null
    val isVision: Boolean get() = mmprojFilename != null
}

fun ModelInfo.supportsLanguage(lang: String): Boolean =
    supportedLanguages.isEmpty() || lang in supportedLanguages

/**
 * Overlay this model's static capability flags with the real capabilities
 * detected from its chat template the first time it was loaded (cached in
 * [StoragePreferences]). Returns the model unchanged if it has never been
 * loaded. This lets badges self-correct and lets custom/imported models gain
 * badges after their first run.
 */
fun ModelInfo.resolveCapabilities(prefs: StoragePreferences): ModelInfo {
    val detected = prefs.getDetectedCaps(filename) ?: return this
    return copy(supportsTools = detected.first, supportsThinking = detected.second)
}

data class ModelWithStatus(
    val model: ModelInfo,
    val isDownloaded: Boolean,
    // Whether the vision projector (mmproj) is present on disk. Only meaningful
    // for vision models; always false for non-vision and custom models.
    val isMmprojDownloaded: Boolean = false,
) {
    /**
     * A vision model whose main file is installed but whose image module is
     * missing — usable as text-only now, with the module available to add.
     */
    val needsVisionModule: Boolean
        get() = model.isVision && isDownloaded && !isMmprojDownloaded
}

private val RELEASE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

fun ModelInfo.releaseDateLabel(): String = releaseDate?.format(RELEASE_DATE_FORMATTER) ?: ""
