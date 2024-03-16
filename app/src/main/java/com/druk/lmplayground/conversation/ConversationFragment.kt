package com.druk.lmplayground.conversation

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.ui.platform.ComposeView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.findNavController
import com.druk.lmplayground.MainViewModel
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme

class ConversationFragment : Fragment() {

    private val activityViewModel: MainViewModel by activityViewModels()
    private val viewModel: ConversationViewModel by viewModels()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        viewModel.setLlama(activityViewModel.llamaCpp)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)

        setContent {
            PlaygroundTheme {
                ConversationContent(
                    viewModel = viewModel,
                    navigateToProfile = { user ->
                        val bundle = bundleOf("userId" to user)
                        findNavController().navigate(
                            R.id.nav_profile,
                            bundle
                        )
                    },
                    onNavIconPressed = {
                        // Do nothing for now
                    }
                )
            }
        }
    }
}
