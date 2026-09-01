package com.paherdin.record.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import com.paherdin.record.core.ui.theme.RecordTokens

private object SharedContentTags {
    const val LOADING = "SharedContentLoading"
    const val EMPTY = "SharedContentEmpty"
    const val ERROR = "SharedContentError"
    const val READY = "SharedContentReady"
    const val RETRY = "SharedContentRetry"
}

/**
 * The shared accessible body presentation. It renders [ContentPresentation],
 * offering [onRetry] only when the presentation declares it safe, and delegates
 * ready content to [readyContent]. State is expressed through text plus
 * semantics, never color alone. [readyContent] must emit exactly one visible
 * heading; the Ready state wrapper retains only non-merging state semantics so
 * arbitrary child text and actions remain independently addressable.
 */
@Composable
fun <T> SharedContent(
    presentation: ContentPresentation<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    readyContent: @Composable (T) -> Unit,
) {
    when (presentation) {
        is ContentPresentation.Loading -> LoadingBody(modifier)
        is ContentPresentation.Empty -> EmptyBody(modifier)
        is ContentPresentation.Error -> ErrorBody(presentation, onRetry, modifier)
        is ContentPresentation.Ready -> ReadyBody(presentation.content, modifier, readyContent)
    }
}

@Composable
private fun LoadingBody(modifier: Modifier) {
    val heading = stringResource(R.string.shared_loading)
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(SharedContentTags.LOADING)
            .sharedStateSemantics(stringResource(R.string.shared_loading_state_description)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(RecordTokens.spaceMedium))
            StateHeading(heading)
        }
    }
}

@Composable
private fun EmptyBody(modifier: Modifier) {
    val heading = stringResource(R.string.shared_empty)
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(SharedContentTags.EMPTY)
            .sharedStateSemantics(stringResource(R.string.shared_empty_state_description)),
        contentAlignment = Alignment.Center,
    ) {
        StateHeading(heading)
    }
}

@Composable
private fun ErrorBody(
    presentation: ContentPresentation.Error,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val heading = stringResource(
        when (presentation.category) {
            UiErrorCategory.TemporarilyUnavailable -> R.string.shared_error_temporarily_unavailable
            UiErrorCategory.RecoveryRequired -> R.string.shared_error_recovery_required
        },
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(SharedContentTags.ERROR)
            .sharedStateSemantics(stringResource(R.string.shared_error_state_description)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StateHeading(heading)
            if (presentation.canRetry) {
                Spacer(Modifier.height(RecordTokens.spaceMedium))
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .testTag(SharedContentTags.RETRY)
                        .defaultMinSize(
                            minWidth = RecordTokens.minimumTouchTarget,
                            minHeight = RecordTokens.minimumTouchTarget,
                        ),
                ) {
                    Text(stringResource(R.string.shared_retry))
                }
            }
        }
    }
}

@Composable
private fun <T> ReadyBody(
    content: T,
    modifier: Modifier,
    readyContent: @Composable (T) -> Unit,
) {
    val description = stringResource(R.string.shared_ready_state_description)
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(SharedContentTags.READY)
            .semantics(mergeDescendants = false) {
                stateDescription = description
            },
    ) {
        readyContent(content)
    }
}

@Composable
private fun StateHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
}

private fun Modifier.sharedStateSemantics(description: String): Modifier = semantics(
    mergeDescendants = true,
) {
    stateDescription = description
    liveRegion = LiveRegionMode.Polite
}
