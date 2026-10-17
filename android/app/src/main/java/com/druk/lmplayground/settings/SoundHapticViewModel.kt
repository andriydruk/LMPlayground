package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Backs Settings → Sound and Haptic. Two independent toggles, both ON by
 * default: the background-completion chime and the per-token generation
 * haptic. Values are read by [com.druk.lmplayground.conversation.ConversationViewModel]
 * at generation time straight from [StoragePreferences].
 */
class SoundHapticViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    private val _soundEnabled = MutableStateFlow(prefs.soundOnCompletion)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled

    private val _hapticEnabled = MutableStateFlow(prefs.hapticOnGeneration)
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled

    fun setSoundEnabled(value: Boolean) {
        prefs.soundOnCompletion = value
        _soundEnabled.value = value
    }

    fun setHapticEnabled(value: Boolean) {
        prefs.hapticOnGeneration = value
        _hapticEnabled.value = value
    }
}
