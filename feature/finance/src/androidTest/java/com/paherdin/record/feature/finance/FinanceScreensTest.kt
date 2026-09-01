package com.paherdin.record.feature.finance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record.core.ui.ContentPresentation
import com.paherdin.record.core.ui.EmptyReason
import com.paherdin.record.core.ui.UiErrorCategory
import com.paherdin.record.core.ui.theme.RecordTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FinanceScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val loadingText = context.getString(com.paherdin.record.core.ui.R.string.shared_loading)
    private val emptyText = context.getString(com.paherdin.record.core.ui.R.string.shared_empty)
    private val errorText =
        context.getString(com.paherdin.record.core.ui.R.string.shared_error_temporarily_unavailable)
    private val retryTag = "SharedContentRetry"
    private val readyHeadingTag = "FinanceHomeReadyHeading"
    private val readyBodyTag = "FinanceHomeReadyBody"
    private val destinationHeadingTag = "FinanceDestinationHeading"
    private val destinationBackTag = "FinanceDestinationBack"

    @Test
    fun loadingStateShowsSharedLoadingCopyAndNoRetry() {
        setContent {
            FinanceHomeScreen(
                presentation = ContentPresentation.Loading,
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(loadingText).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()
    }

    @Test
    fun emptyStateShowsSharedEmptyCopyAndNoRetry() {
        setContent {
            FinanceHomeScreen(
                presentation = ContentPresentation.Empty(EmptyReason.NoApplicableItems),
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(emptyText).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()
    }

    @Test
    fun recoverableErrorShowsRetryAndInvokesCallback() {
        var retried = false
        setContent {
            FinanceHomeScreen(
                presentation = ContentPresentation.Error(
                    UiErrorCategory.TemporarilyUnavailable,
                    canRetry = true,
                ),
                onRetry = { retried = true },
            )
        }

        composeRule.onNodeWithText(errorText).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun errorWithoutRetryHidesRetryButton() {
        setContent {
            FinanceHomeScreen(
                presentation = ContentPresentation.Error(
                    UiErrorCategory.RecoveryRequired,
                    canRetry = false,
                ),
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(
            context.getString(
                com.paherdin.record.core.ui.R.string.shared_error_recovery_required,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()
    }

    @Test
    fun readyStateShowsChineseM2AvailabilityCopyAsSingleFinanceHeading() {
        val readyMessage = context.getString(R.string.finance_home_ready_message)
        val readyBody = context.getString(R.string.finance_home_ready_body)

        setContent {
            FinanceHomeScreen(
                presentation = ContentPresentation.Ready(FinanceAvailability),
                onRetry = {},
            )
        }

        composeRule.onNodeWithTag(readyHeadingTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals(readyMessage)
        composeRule.onNodeWithTag(readyBodyTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals(readyBody)
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        val headingNode = composeRule.onNodeWithTag(readyHeadingTag, useUnmergedTree = true)
            .fetchSemanticsNode().config
        assertTrue(headingNode.contains(SemanticsProperties.Heading))
    }

    @Test
    fun readyStateRendersNoSaveFormOrDomainFields() {
        setContent {
            FinanceHomeScreen(
                presentation = ContentPresentation.Ready(FinanceAvailability),
                onRetry = {},
            )
        }

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onAllNodes(hasText("保存")).assertCountEquals(0)
        composeRule.onAllNodes(hasText("金额")).assertCountEquals(0)
        composeRule.onAllNodes(hasText("余额")).assertCountEquals(0)
    }

    @Test
    fun transactionDetailShowsAvailabilityCopyBackActionAndNoRecordId() {
        var backInvoked = false
        setContent {
            TransactionDetailAvailabilityScreen(onBack = { backInvoked = true })
        }

        composeRule.onNodeWithTag(destinationHeadingTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.finance_transaction_detail_availability))
        composeRule.onNodeWithTag(destinationBackTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertTrue(backInvoked) }
        composeRule.onAllNodes(hasText("ID", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun editorShowsKindSpecificChineseLabelAndBackAction() {
        var backInvoked = false
        val kindState = mutableStateOf(FinanceEditorKind.Expense)
        setContent {
            FinanceEditorAvailabilityScreen(kind = kindState.value, onBack = { backInvoked = true })
        }

        val cases = listOf(
            FinanceEditorKind.Expense to R.string.finance_editor_kind_expense,
            FinanceEditorKind.Income to R.string.finance_editor_kind_income,
            FinanceEditorKind.Transfer to R.string.finance_editor_kind_transfer,
        )
        cases.forEach { (kind, kindString) ->
            composeRule.runOnIdle {
                kindState.value = kind
                backInvoked = false
            }
            val expected = context.getString(
                R.string.finance_editor_availability,
                context.getString(kindString),
            )
            composeRule.onNodeWithTag(destinationHeadingTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertTextEquals(expected)
            composeRule.onNodeWithTag(destinationBackTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .performClick()
            composeRule.runOnIdle { assertTrue(backInvoked) }
        }
    }

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            RecordTheme {
                content()
            }
        }
    }
}
