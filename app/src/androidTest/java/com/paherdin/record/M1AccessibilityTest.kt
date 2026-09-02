package com.paherdin.record

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.paherdin.record.core.ui.ContentPresentation
import com.paherdin.record.core.ui.EmptyReason
import com.paherdin.record.core.ui.Phase
import com.paherdin.record.core.ui.UiErrorCategory
import com.paherdin.record.ui.RestoreRecoverySurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * M1-T08 shell accessibility acceptance (plan Step 4, spec 5.3, criteria 4/5):
 * the loading/empty/error/ready presentations and the data-free recovery gate
 * expose one heading, polite state semantics, selected-tab semantics,
 * deterministic traversal order, 48-dp Retry targets, and no clipped or
 * overlapping required text at 200% font, in light and dark, and they never
 * render raw error text, record IDs, or paths.
 */
class M1AccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val loadingText = "加载中…"
    private val emptyText = "暂无内容"
    private val errorText = "暂时无法加载"
    private val retryTag = "SharedContentRetry"

    private val forbiddenRawText = listOf(
        "/",
        "://",
        "Exception",
        "Throwable",
        "SQL",
        "Room",
        "Dao",
        M1TestCards.TRANSACTION_ID,
    )

    @Test
    fun shellPresentationsExposeHeadingStateAndRetrySemanticsInLight() {
        assertPresentationMatrix(darkTheme = false)
    }

    @Test
    fun shellPresentationsExposeHeadingStateAndRetrySemanticsInDark() {
        assertPresentationMatrix(darkTheme = true)
    }

    @Test
    fun shellPresentationsKeepRequiredTextUsableAtTwoHundredPercentFontInLight() {
        assertPresentationMatrixAtTwoHundredPercentFont(darkTheme = false)
    }

    @Test
    fun shellPresentationsKeepRequiredTextUsableAtTwoHundredPercentFontInDark() {
        assertPresentationMatrixAtTwoHundredPercentFont(darkTheme = true)
    }

    @Test
    fun shellBottomBarExposesSelectedTabSemanticsAndDeterministicTraversalOrder() {
        composeRule.setContent {
            M1ShellHost(M1TestClocks.atSeptemberSecond(), M1TestClocks.zoneId)
        }

        val bar = composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
        assertEquals(
            listOf("时间线", "财务", "笔记", "项目"),
            bar.fetchSemanticsNode().collectVisibleTexts(),
        )
        assertTopBarActionOrder()
        composeRule.onNode(tabMatcher("时间线"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

        composeRule.onNode(tabMatcher("财务")).performClick()
        composeRule.onNodeWithText("财务功能将在 M2 提供").assertIsDisplayed()
        composeRule.onNode(tabMatcher("财务"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNode(tabMatcher("时间线"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        assertTopBarActionOrder()
    }

    @Test
    fun recoveryGateExposesOneHeadingRetryBoundsAndNoRawText() {
        var retried = false
        var phase by mutableStateOf(Phase.RecoverableFailure)
        composeRule.setContent {
            M1ShellHost(M1TestClocks.atSeptemberSecond(), M1TestClocks.zoneId) {
                RestoreRecoverySurface(phase = phase, canRetry = true, onRetry = { retried = true })
            }
        }

        composeRule.onNodeWithText("数据恢复未完成").assertIsDisplayed()
        composeRule.onNodeWithText("可以安全重试以继续恢复。").assertIsDisplayed()
        assertExactlyOneHeading()
        composeRule.onNodeWithTag(M1ShellTags.RESTORE_RECOVERY)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "启动恢复未完成"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithTag(M1ShellTags.RESTORE_RECOVERY_RETRY)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
            .performClick()
        composeRule.runOnIdle { assertTrue(retried) }
        assertNoRawErrorText()

        composeRule.runOnIdle { phase = Phase.Checking }
        composeRule.onNodeWithText("正在检查数据").assertIsDisplayed()
        composeRule.onNodeWithTag(M1ShellTags.RESTORE_RECOVERY_RETRY).assertDoesNotExist()
    }

    private fun assertPresentationMatrix(darkTheme: Boolean) {
        var retried = false
        var presentation by mutableStateOf<ContentPresentation<String>>(ContentPresentation.Loading)
        composeRule.setContent {
            M1PresentationHost(
                presentationProvider = { presentation },
                darkTheme = darkTheme,
                onRetry = { retried = true },
            )
        }

        assertSingleHeadingState("SharedContentLoading", loadingText, "正在加载")
        assertNoRawErrorText()

        composeRule.runOnIdle { presentation = ContentPresentation.Empty(EmptyReason.NoApplicableItems) }
        assertSingleHeadingState("SharedContentEmpty", "暂无内容", "内容为空")
        assertNoRawErrorText()

        composeRule.runOnIdle {
            presentation = ContentPresentation.Error(UiErrorCategory.TemporarilyUnavailable, canRetry = true)
        }
        assertSingleHeadingState("SharedContentError", errorText, "加载失败")
        composeRule.onNodeWithTag(retryTag)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertTrue(retried) }
        assertNoRawErrorText()

        composeRule.runOnIdle {
            presentation = ContentPresentation.Error(UiErrorCategory.RecoveryRequired, canRetry = false)
        }
        composeRule.onNodeWithText("需要完成恢复后才能继续").assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()

        composeRule.runOnIdle { presentation = ContentPresentation.Ready("shell") }
        composeRule.onNodeWithText("${M1FixtureTags.PRESENTATION_READY_TEXT}:shell").assertIsDisplayed()
        assertExactlyOneHeading()
        composeRule.onNodeWithTag("SharedContentReady", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "内容已就绪",
                ),
            )
        composeRule.onNodeWithTag(M1PresentationReadyActionTag)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
        assertNoRawErrorText()
    }

    private fun assertPresentationMatrixAtTwoHundredPercentFont(darkTheme: Boolean) {
        var retried = false
        var presentation by mutableStateOf<ContentPresentation<String>>(ContentPresentation.Loading)
        composeRule.setContent {
            M1PresentationHost(
                presentationProvider = { presentation },
                darkTheme = darkTheme,
                fontScale = 2f,
                onRetry = { retried = true },
            )
        }

        assertFullyWithinRoot(
            composeRule.onNodeWithText(loadingText, useUnmergedTree = true),
            composeRule.onNodeWithText("时间线", useUnmergedTree = true),
        )
        assertNoRawErrorText()

        composeRule.runOnIdle { presentation = ContentPresentation.Empty(EmptyReason.NoApplicableItems) }
        assertFullyWithinRoot(composeRule.onNodeWithText("暂无内容", useUnmergedTree = true))

        composeRule.runOnIdle {
            presentation = ContentPresentation.Error(UiErrorCategory.TemporarilyUnavailable, canRetry = true)
        }
        val errorNode = composeRule
            .onNodeWithText(errorText, useUnmergedTree = true)
            .assertIsDisplayed()
        val retryNode = composeRule.onNodeWithTag(retryTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        assertFullyWithinRoot(errorNode, retryNode)
        val errorBounds = errorNode.getUnclippedBoundsInRoot()
        val retryBounds = retryNode.getUnclippedBoundsInRoot()
        assertTrue(
            "error heading and Retry overlap: error=$errorBounds retry=$retryBounds",
            errorBounds.bottom <= retryBounds.top || retryBounds.bottom <= errorBounds.top,
        )
        retryNode.performClick()
        composeRule.runOnIdle { assertTrue(retried) }

        composeRule.runOnIdle { presentation = ContentPresentation.Ready("two-hundred-percent") }
        assertFullyWithinRoot(
            composeRule.onNodeWithText(
                "${M1FixtureTags.PRESENTATION_READY_TEXT}:two-hundred-percent",
                useUnmergedTree = true,
            ),
            composeRule.onNodeWithTag(M1PresentationReadyActionTag, useUnmergedTree = true),
            composeRule.onNodeWithText("时间线", useUnmergedTree = true),
        )
        assertNoRawErrorText()
    }

    private fun assertSingleHeadingState(tag: String, label: String, stateDescription: String) {
        composeRule.onNodeWithText(label).assertIsDisplayed()
        assertExactlyOneHeading()
        composeRule.onNodeWithTag(tag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, stateDescription))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    private fun assertExactlyOneHeading() {
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    private fun assertTopBarActionOrder() {
        val actions = composeRule.onRoot(useUnmergedTree = true)
            .fetchSemanticsNode()
            .collectContentDescriptions()
            .filter { it in setOf("新建", "搜索", "设置") }
        assertEquals(listOf("新建", "搜索", "设置"), actions)
    }

    private fun assertNoRawErrorText() {
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val visible = root.collectVisibleTexts() + root.collectContentDescriptions()
        val leaked = visible.filter { text -> forbiddenRawText.any(text::contains) }
        assertTrue(
            "raw error text/ID/path leaked into the shell tree: $leaked in $visible",
            leaked.isEmpty(),
        )
    }

    private fun assertFullyWithinRoot(vararg nodes: SemanticsNodeInteraction) {
        val root = composeRule.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot()
        nodes.forEach { node ->
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "required node has empty bounds: $bounds",
                bounds.right > bounds.left && bounds.bottom > bounds.top,
            )
            assertTrue(
                "required node is outside root bounds: node=$bounds root=$root",
                bounds.left >= root.left && bounds.top >= root.top &&
                    bounds.right <= root.right && bounds.bottom <= root.bottom,
            )
        }
    }

    private fun tabMatcher(label: String): SemanticsMatcher =
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
}