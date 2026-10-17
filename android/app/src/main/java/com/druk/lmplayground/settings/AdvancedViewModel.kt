package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Backs Settings → Advanced. Currently a single toggle: "Enable repack",
 * ON by default. The value is read by
 * [com.druk.lmplayground.conversation.ConversationViewModel] at model-load
 * time straight from [StoragePreferences], so a change takes effect on the
 * next load.
 */
class AdvancedViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    private val _repackEnabled = MutableStateFlow(prefs.repackEnabled)
    val repackEnabled: StateFlow<Boolean> = _repackEnabled

    fun setRepackEnabled(value: Boolean) {
        prefs.repackEnabled = value
        _repackEnabled.value = value
    }
}
