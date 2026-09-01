package com.paherdin.record.feature.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object TimelineScreenTags {
    const val TODAY = "TimelineToday"
    const val AVAILABILITY = "TimelineAvailability"
    const val CARD_ROW = "TimelineCardRow"
}

/**
 * The real Timeline shell: retained selected day, explicit Today, and a
 * truthful M1 body. Production renders [TimelineContent.Availability]; the
 * synthetic [TimelineContent.Cards] path proves source-typed dispatch.
 */
@Composable
fun TimelineHomeScreen(
    dateState: TimelineDateState,
    onToday: () -> Unit,
    onOpenTransaction: (TimelineCardUiModel) -> Unit,
    onOpenNote: (TimelineCardUiModel) -> Unit,
    modifier: Modifier = Modifier,
    content: TimelineContent = TimelineContent.Availability,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TimelineDateHeader(dateState = dateState, onToday = onToday)
        TimelineContentBody(
            content = content,
            heading = stringResource(R.string.timeline_screen_heading),
            availabilityMessage = stringResource(R.string.timeline_availability),
            onOpenTransaction = onOpenTransaction,
            onOpenNote = onOpenNote,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The Projects M4 surface. [presentation] selects the shared body state; a
 * Ready body renders [TimelineContent] with source-typed card dispatch.
 */
@Composable
fun ProjectsHomeScreen(
    presentation: ContentPresentation<TimelineContent>,
    onOpenTransaction: (TimelineCardUiModel) -> Unit,
    onOpenNote: (TimelineCardUiModel) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedContent(
        presentation = presentation,
        onRetry = onRetry,
        modifier = modifier,
        readyContent = { content ->
            TimelineContentBody(
                content = content,
                heading = stringResource(R.string.projects_screen_heading),
                availabilityMessage = stringResource(R.string.projects_availability),
                onOpenTransaction = onOpenTransaction,
                onOpenNote = onOpenNote,
            )
        },
    )
}

/**
 * The unified Search M5 surface, with the same presentation and dispatch
 * contract as [ProjectsHomeScreen].
 */
@Composable
fun UnifiedSearchScreen(
    presentation: ContentPresentation<TimelineContent>,
    onOpenTransaction: (TimelineCardUiModel) -> Unit,
    onOpenNote: (TimelineCardUiModel) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedContent(
        presentation = presentation,
        onRetry = onRetry,
        modifier = modifier,
        readyContent = { content ->
            TimelineContentBody(
                content = content,
                heading = stringResource(R.string.search_screen_heading),
                availabilityMessage = stringResource(R.string.search_availability),
                onOpenTransaction = onOpenTransaction,
                onOpenNote = onOpenNote,
            )
        },
    )
}

/** Renders exactly one visible heading in every branch. */
@Composable
internal fun TimelineContentBody(
    content: TimelineContent,
    heading: String,
    availabilityMessage: String,
    onOpenTransaction: (TimelineCardUiModel) -> Unit,
    onOpenNote: (TimelineCardUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (content) {
        TimelineContent.Availability -> AvailabilityBody(availabilityMessage, modifier)
        is TimelineContent.Cards -> CardsBody(
            cards = content.cards,
            heading = heading,
            onOpenTransaction = onOpenTransaction,
            onOpenNote = onOpenNote,
            modifier = modifier,
        )
    }
}

@Composable
private fun TimelineDateHeader(
    dateState: TimelineDateState,
    onToday: () -> Unit,
) {
    val datePattern = stringResource(R.string.timeline_date_pattern)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = RecordTokens.spaceMedium,
                vertical = RecordTokens.spaceSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DateTimeFormatter.ofPattern(datePattern, Locale.SIMPLIFIED_CHINESE)
                .format(dateState.selectedDay),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onToday,
            modifier = Modifier
                .testTag(TimelineScreenTags.TODAY)
                .defaultMinSize(
                    minWidth = RecordTokens.minimumTouchTarget,
                    minHeight = RecordTokens.minimumTouchTarget,
                ),
        ) {
            Text(stringResource(R.string.timeline_today))
        }
    }
}

@Composable
private fun AvailabilityBody(message: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(TimelineScreenTags.AVAILABILITY),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(RecordTokens.spaceLarge)
                .semantics { heading() },
        )
    }
}

@Composable
private fun CardsBody(
    cards: List<TimelineCardUiModel>,
    heading: String,
    onOpenTransaction: (TimelineCardUiModel) -> Unit,
    onOpenNote: (TimelineCardUiModel) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(
                    horizontal = RecordTokens.spaceMedium,
                    vertical = RecordTokens.spaceSmall,
                )
                .semantics { heading() },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = RecordTokens.spaceMedium,
                vertical = RecordTokens.spaceSmall,
            ),
            verticalArrangement = Arrangement.spacedBy(RecordTokens.spaceSmall),
        ) {
            items(cards, key = { it.id }) { card ->
                TimelineCardRow(card, onOpenTransaction, onOpenNote)
            }
        }
    }
}

@Composable
private fun TimelineCardRow(
    card: TimelineCardUiModel,
    onOpenTransaction: (TimelineCardUiModel) -> Unit,
    onOpenNote: (TimelineCardUiModel) -> Unit,
) {
    Surface(
        onClick = {
            when (card.kind) {
                TimelineCardKind.Transaction -> onOpenTransaction(card)
                TimelineCardKind.Note -> onOpenNote(card)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TimelineScreenTags.CARD_ROW),
        shape = RecordTokens.shapeSmall,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = card.headline,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .defaultMinSize(minHeight = RecordTokens.minimumTouchTarget)
                .padding(
                    horizontal = RecordTokens.spaceMedium,
                    vertical = RecordTokens.spaceSmall,
                ),
        )
    }
}
