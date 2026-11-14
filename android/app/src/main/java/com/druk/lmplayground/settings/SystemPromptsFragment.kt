package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.theme.PlaygroundTheme

class SystemPromptsFragment : Fragment() {

    private val viewModel: SystemPromptsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val prompts by viewModel.prompts.observeAsState(emptyList())
                SystemPromptsScreen(
                    prompts = prompts,
                    onBackClick = { findNavController().popBackStack() },
                    onAdd = viewModel::addPrompt,
                    onUpdate = viewModel::updatePrompt,
                    onDelete = viewModel::deletePrompt
                )
            }
        }
    }
}
