package com.druk.lmplayground.settings

import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * One entry in the language picker. `tag` is null for "System default".
 */
data class LanguageOption(
    val tag: String?,
    val displayName: String
)

/**
 * BCP-47 language tags supported by the app. Must stay in sync with
 * `app/src/main/res/xml/locales_config.xml`.
 */
private val SUPPORTED_LANGUAGE_TAGS = listOf(
    "en", "ar", "bn", "cs", "da", "de", "es", "fi", "fil", "fr",
    "he", "hi", "id", "it", "ja", "ko", "ms", "nb", "nl", "pl",
    "pt-BR", "ro", "sv", "th", "tr", "uk", "vi", "zh-CN"
)

/**
 * Build the list of choices shown in the picker. Each language is labeled in its own
 * locale (e.g. "Deutsch", "日本語") so users can find their language without reading
 * the app's current UI language. The list is sorted alphabetically by display name.
 */
fun buildLanguageOptions(): List<LanguageOption> {
    val systemDefault = LanguageOption(tag = null, displayName = "")
    val languages = SUPPORTED_LANGUAGE_TAGS
        .map { tag ->
            val locale = Locale.forLanguageTag(tag)
            val name = locale.getDisplayName(locale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            LanguageOption(tag = tag, displayName = name)
        }
        .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
    return listOf(systemDefault) + languages
}

/**
 * Return the BCP-47 tag currently applied by AppCompat, or null if the app follows the
 * system default.
 */
fun currentLanguageTag(): String? {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return null
    return locales.toLanguageTags().split(",").firstOrNull()?.takeIf { it.isNotBlank() }
}
