package com.paherdin.record.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.paherdin.record.core.ui.ContentPresentation
import com.paherdin.record.core.ui.SharedContent
import com.paherdin.record.core.ui.theme.RecordTokens

private object NotesTags {
    const val READY_HEADING = "NotesReadyHeading"
    const val DETAIL_AVAILABILITY_BACK = "NoteDetailAvailabilityBack"
    const val EDITOR_AVAILABILITY_BACK = "NoteEditorAvailabilityBack"
}

/**
 * Notes home body. Renders [presentation] through [SharedContent]; the ready
 * body is a fixed heading plus the M3 availability message.
 */
@Composable
fun NotesHomeScreen(
    presentation: ContentPresentation<NotesAvailability>,
    onRetry: () -> Unit,
) {
    SharedContent(
        presentation = presentation,
        onRetry = onRetry,
        readyContent = { NotesReadyBody() },
    )
}

@Composable
private fun NotesReadyBody() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.notes_heading),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .testTag(NotesTags.READY_HEADING)
                .semantics { heading() },
        )
        Spacer(Modifier.height(RecordTokens.spaceMedium))
        Text(
            text = stringResource(R.string.notes_ready_message),
            textAlign = TextAlign.Center,
        )
    }
}

/** Note detail placeholder for M1; states availability and offers [onBack]. */
@Composable
fun NoteDetailAvailabilityScreen(onBack: () -> Unit) {
    AvailabilityScreen(
        heading = stringResource(R.string.notes_detail_heading),
        message = stringResource(R.string.notes_detail_unavailable_message),
        backTag = NotesTags.DETAIL_AVAILABILITY_BACK,
        onBack = onBack,
    )
}

/** Note editor placeholder for M1; states availability and offers [onBack]. */
@Composable
fun NoteEditorAvailabilityScreen(onBack: () -> Unit) {
    AvailabilityScreen(
        heading = stringResource(R.string.notes_editor_heading),
        message = stringResource(R.string.notes_editor_unavailable_message),
        backTag = NotesTags.EDITOR_AVAILABILITY_BACK,
        onBack = onBack,
    )
}

@Composable
private fun AvailabilityScreen(
    heading: String,
    message: String,
    backTag: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(RecordTokens.spaceMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(RecordTokens.spaceMedium))
        Text(text = message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(RecordTokens.spaceLarge))
        Button(
            onClick = onBack,
            modifier = Modifier
                .testTag(backTag)
                .defaultMinSize(
                    minWidth = RecordTokens.minimumTouchTarget,
                    minHeight = RecordTokens.minimumTouchTarget,
                ),
        ) {
            Text(stringResource(R.string.notes_back))
        }
    }
}
