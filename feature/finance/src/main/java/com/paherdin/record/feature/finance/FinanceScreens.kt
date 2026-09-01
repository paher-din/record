package com.paherdin.record.feature.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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

private object FinanceTags {
    const val HOME_READY_HEADING = "FinanceHomeReadyHeading"
    const val HOME_READY_BODY = "FinanceHomeReadyBody"
    const val DESTINATION_HEADING = "FinanceDestinationHeading"
    const val DESTINATION_BACK = "FinanceDestinationBack"
}

/**
 * The M1 Finance home body. It renders the shared accessible presentation and
 * states truthfully that Finance arrives in M2; it carries no domain data.
 */
@Composable
fun FinanceHomeScreen(
    presentation: ContentPresentation<FinanceAvailability>,
    onRetry: () -> Unit,
) {
    SharedContent(
        presentation = presentation,
        onRetry = onRetry,
        readyContent = { FinanceReadyContent() },
    )
}

@Composable
private fun FinanceReadyContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.finance_home_ready_message),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .testTag(FinanceTags.HOME_READY_HEADING)
                .semantics { heading() },
        )
        Spacer(Modifier.height(RecordTokens.spaceMedium))
        Text(
            text = stringResource(R.string.finance_home_ready_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(FinanceTags.HOME_READY_BODY),
        )
    }
}

/**
 * The M1 typed TransactionDetail destination placeholder. It renders only the
 * availability copy and a back action; no transaction identity is shown.
 */
@Composable
fun TransactionDetailAvailabilityScreen(onBack: () -> Unit) {
    FinanceAvailabilityDestination(
        heading = stringResource(R.string.finance_transaction_detail_availability),
        onBack = onBack,
    )
}

/**
 * The M1 typed finance editor destination placeholder. It names the editor
 * kind, states M2 ownership, and offers a back action; no fields or save.
 */
@Composable
fun FinanceEditorAvailabilityScreen(kind: FinanceEditorKind, onBack: () -> Unit) {
    val kindLabel = stringResource(
        when (kind) {
            FinanceEditorKind.Expense -> R.string.finance_editor_kind_expense
            FinanceEditorKind.Income -> R.string.finance_editor_kind_income
            FinanceEditorKind.Transfer -> R.string.finance_editor_kind_transfer
        },
    )
    FinanceAvailabilityDestination(
        heading = stringResource(R.string.finance_editor_availability, kindLabel),
        onBack = onBack,
    )
}

@Composable
private fun FinanceAvailabilityDestination(heading: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .testTag(FinanceTags.DESTINATION_HEADING)
                .semantics { heading() },
        )
        Spacer(Modifier.height(RecordTokens.spaceMedium))
        Button(
            onClick = onBack,
            modifier = Modifier
                .testTag(FinanceTags.DESTINATION_BACK)
                .defaultMinSize(
                    minWidth = RecordTokens.minimumTouchTarget,
                    minHeight = RecordTokens.minimumTouchTarget,
                ),
        ) {
            Text(stringResource(R.string.finance_back))
        }
    }
}
