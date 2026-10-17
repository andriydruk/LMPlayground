@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme

private data class PolicySection(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int
)

private val policySections = listOf(
    PolicySection(R.string.privacy_policy_s1_title, R.string.privacy_policy_s1_body),
    PolicySection(R.string.privacy_policy_s2_title, R.string.privacy_policy_s2_body),
    PolicySection(R.string.privacy_policy_s3_title, R.string.privacy_policy_s3_body),
    PolicySection(R.string.privacy_policy_s4_title, R.string.privacy_policy_s4_body),
    PolicySection(R.string.privacy_policy_s5_title, R.string.privacy_policy_s5_body),
    PolicySection(R.string.privacy_policy_s6_title, R.string.privacy_policy_s6_body),
)

@Composable
fun PrivacyPolicyScreen(
    onBackClick: () -> Unit,
    onSendFeedbackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_policy)) },
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
        PrivacyPolicyContent(
            onSendFeedbackClick = onSendFeedbackClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

@Composable
fun PrivacyPolicyContent(
    onSendFeedbackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
        }
    }
    Column(modifier = modifier) {
        if (isScrolled) {
            // Same 12dp inset as Models / Prompts / Language dividers.
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp)
        ) {
        item {
            Text(
                text = stringResource(R.string.privacy_policy_effective_date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.privacy_policy_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        items(policySections) { section ->
            Text(
                text = stringResource(section.titleRes),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(section.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Section 7 - Contact with feedback button
        item {
            Text(
                text = stringResource(R.string.privacy_policy_s7_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.privacy_policy_s7_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onSendFeedbackClick) {
                Text(stringResource(R.string.send_feedback))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
        }
    }
}

@Preview
@Composable
private fun PrivacyPolicyScreenPreview() {
    PlaygroundTheme {
        PrivacyPolicyScreen(
            onBackClick = {},
            onSendFeedbackClick = {}
        )
    }
}
