package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.theme.PlaygroundTheme

class AdvancedFragment : Fragment() {

    private val viewModel: AdvancedViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val repackEnabled by viewModel.repackEnabled.collectAsStateWithLifecycle()
                AdvancedScreen(
                    repackEnabled = repackEnabled,
                    onRepackEnabledChanged = { viewModel.setRepackEnabled(it) },
                    onBackClick = { findNavController().popBackStack() },
                )
            }
        }
    }
}
