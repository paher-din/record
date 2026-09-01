package com.paherdin.record.feature.notes

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record.core.ui.ContentPresentation
import com.paherdin.record.core.ui.EmptyReason
import com.paherdin.record.core.ui.UiErrorCategory
import com.paherdin.record.core.ui.R as CoreUiR
import com.paherdin.record.core.ui.theme.RecordTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotesScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val sharedLoading = context.getString(CoreUiR.string.shared_loading)
    private val sharedEmpty = context.getString(CoreUiR.string.shared_empty)
    private val sharedError = context.getString(CoreUiR.string.shared_error_temporarily_unavailable)
    private val sharedRecoveryRequired = context.getString(CoreUiR.string.shared_error_recovery_required)
    private val retryTag = "SharedContentRetry"

    private val readyHeadingTag = "NotesReadyHeading"
    private val detailBackTag = "NoteDetailAvailabilityBack"
    private val editorBackTag = "NoteEditorAvailabilityBack"

    @Test
    fun loadingStateShowsSharedLoadingCopyAndNoRetry() {
        setHomeContent(ContentPresentation.Loading)

        composeRule.onNodeWithText(sharedLoading).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()
    }

    @Test
    fun emptyStateShowsSharedEmptyCopyAndNoRetry() {
        setHomeContent(ContentPresentation.Empty(EmptyReason.NoApplicableItems))

        composeRule.onNodeWithText(sharedEmpty).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()
    }

    @Test
    fun recoverableErrorShowsRetryAndInvokesCallback() {
        var retried = false
        composeRule.setContent {
            RecordTheme {
                NotesHomeScreen(
                    presentation = ContentPresentation.Error(
                        UiErrorCategory.TemporarilyUnavailable,
                        canRetry = true,
                    ),
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText(sharedError).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun errorWithoutRetryHidesRetryButton() {
        setHomeContent(
            ContentPresentation.Error(UiErrorCategory.RecoveryRequired, canRetry = false),
        )

        composeRule.onNodeWithText(sharedRecoveryRequired).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()
    }

    @Test
    fun readyStateShowsNotesHeadingAndM3ReadyCopy() {
        setHomeContent(ContentPresentation.Ready(NotesAvailability))

        composeRule.onNodeWithTag(readyHeadingTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.notes_heading))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText(context.getString(R.string.notes_ready_message))
            .assertIsDisplayed()
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    @Test
    fun homeReadyStateHasNoTitleMarkdownImageTagProjectOrSaveField() {
        setHomeContent(ContentPresentation.Ready(NotesAvailability))

        assertNoForbiddenEditorFields()
    }

    @Test
    fun detailAvailabilityShowsCopyAndBackInvokesCallback() {
        var backInvoked = false
        composeRule.setContent {
            RecordTheme {
                NoteDetailAvailabilityScreen(onBack = { backInvoked = true })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.notes_detail_heading))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.notes_detail_unavailable_message))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(detailBackTag)
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertTrue(backInvoked) }
        assertNoForbiddenEditorFields()
    }

    @Test
    fun editorAvailabilityShowsCopyAndBackInvokesCallback() {
        var backInvoked = false
        composeRule.setContent {
            RecordTheme {
                NoteEditorAvailabilityScreen(onBack = { backInvoked = true })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.notes_editor_heading))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.notes_editor_unavailable_message))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(editorBackTag)
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertTrue(backInvoked) }
        assertNoForbiddenEditorFields()
    }

    private fun setHomeContent(presentation: ContentPresentation<NotesAvailability>) {
        composeRule.setContent {
            RecordTheme {
                NotesHomeScreen(presentation = presentation, onRetry = {})
            }
        }
    }

    private fun assertNoForbiddenEditorFields() {
        listOf("标题", "Markdown", "图片", "标签", "项目", "保存").forEach { forbidden ->
            composeRule.onAllNodesWithText(forbidden).assertCountEquals(0)
        }
    }
}
