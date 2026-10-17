@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme

private const val GITHUB_URL = "https://github.com/andriydruk/LMPlayground"

private data class FaqItem(
    @StringRes val questionRes: Int,
    @StringRes val answerRes: Int,
    val hasGithubLink: Boolean = false
)

private val faqItems = listOf(
    FaqItem(R.string.faq_q1, R.string.faq_a1),
    FaqItem(R.string.faq_q2, R.string.faq_a2),
    FaqItem(R.string.faq_q3, R.string.faq_a3),
    FaqItem(R.string.faq_q4, R.string.faq_a4),
    FaqItem(R.string.faq_q5, R.string.faq_a5),
    FaqItem(R.string.faq_q6, R.string.faq_a6),
    FaqItem(R.string.faq_q7, R.string.faq_a7),
    FaqItem(R.string.faq_q8, R.string.faq_a8, hasGithubLink = true),
    FaqItem(R.string.faq_q9, R.string.faq_a9),
    FaqItem(R.string.faq_q10, R.string.faq_a10),
    FaqItem(R.string.faq_q11, R.string.faq_a11),
    FaqItem(R.string.faq_q12, R.string.faq_a12),
    FaqItem(R.string.faq_q13, R.string.faq_a13),
    FaqItem(R.string.faq_q14, R.string.faq_a14),
    FaqItem(R.string.faq_q15, R.string.faq_a15),
)

@Composable
fun FaqScreen(
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.faq)) },
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
        FaqContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

@Composable
fun FaqContent(modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
        }
    }
    Column(modifier = modifier) {
        if (isScrolled) {
            // Scroll-edge divider matching Models / Prompts / Language.
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight()
        ) {
            items(faqItems) { item ->
                FaqExpandableItem(item)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun FaqExpandableItem(item: FaqItem) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(item.questionRes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                if (item.hasGithubLink) {
                    FaqAnswerWithLink(item.answerRes)
                } else {
                    Text(
                        text = stringResource(item.answerRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FaqAnswerWithLink(@StringRes answerRes: Int) {
    val answerText = stringResource(answerRes)
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textStyle = MaterialTheme.typography.bodyMedium

    val linkText = "my GitHub repository"
    val startIndex = answerText.indexOf(linkText)

    if (startIndex >= 0) {
        val annotatedString = buildAnnotatedString {
            withStyle(SpanStyle(color = textColor)) {
                append(answerText.substring(0, startIndex))
            }
            pushStringAnnotation(tag = "URL", annotation = GITHUB_URL)
            withStyle(
                SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline
                )
            ) {
                append(linkText)
            }
            pop()
            withStyle(SpanStyle(color = textColor)) {
                append(answerText.substring(startIndex + linkText.length))
            }
        }

        ClickableText(
            text = annotatedString,
            style = textStyle,
            onClick = { offset ->
                annotatedString.getStringAnnotations("URL", offset, offset)
                    .firstOrNull()?.let { uriHandler.openUri(it.item) }
            }
        )
    } else {
        Text(
            text = answerText,
            style = textStyle,
            color = textColor
        )
    }
}

@Preview
@Composable
private fun FaqScreenPreview() {
    PlaygroundTheme {
        FaqScreen(onBackClick = {})
    }
}
