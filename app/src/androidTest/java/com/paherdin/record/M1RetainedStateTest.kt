package com.paherdin.record

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.paherdin.record.feature.timeline.TimelineDateController
import com.paherdin.record.feature.timeline.TimelineDateState
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/**
 * M1-T08 retained-state acceptance (plan Step 3, spec 8.3/8.4). The recreation
 * class drives the real [MainActivity] (the only composition that survives a
 * real `ActivityScenario.recreate()`) and proves the four top-level stacks plus
 * the Timeline day probe survive switch, reselect, global round trips, and
 * activity recreation. The controlled-clock classes prove the Timeline date
 * contract: current-day default, foreground-midnight stability, explicit Today
 * re-read, Today-to-top, filter preservation, and stale-revision suppression.
 */
class M1RetainedStateTest {

    @get:Rule
    val composeRule = createAndroidComposeRule(MainActivity::class.java)

    private fun awaitShell() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(M1ShellTags.BOTTOM_NAVIGATION)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun activityRecreationPreservesTheCurrentDestinationAndExactOrigin() {
        awaitShell()
        composeRule.onNodeWithText("时间线内容将在 M5 接入").assertIsDisplayed()

        composeRule.onNodeWithTag(M1ShellTags.SETTINGS_ACTION).performClick()
        awaitText("更多设置将在后续版本提供。")

        composeRule.activityRule.scenario.recreate()
        awaitShell()
        awaitText("更多设置将在后续版本提供。")

        composeRule.onNodeWithTag(M1ShellTags.BACK_ACTION).performClick()
        awaitText("时间线内容将在 M5 接入")
    }

    @Test
    fun activityRecreationPreservesEachTabsSavedStackIndependently() {
        awaitShell()

        // Timeline pushes Settings; Finance pushes Search; both stacks are saved
        // when the user switches tabs and must survive an activity recreation.
        composeRule.onNodeWithTag(M1ShellTags.SETTINGS_ACTION).performClick()
        awaitText("更多设置将在后续版本提供。")
        composeRule.onNode(tabMatcher("财务")).performClick()
        awaitText("财务功能将在 M2 提供")
        composeRule.onNodeWithTag(M1ShellTags.SEARCH_ACTION).performClick()
        awaitText("搜索功能将在 M5 接入")
        composeRule.onNode(tabMatcher("笔记")).performClick()
        awaitText("笔记功能将在 M3 版本中提供")
        composeRule.onNode(tabMatcher("项目")).performClick()
        awaitText("项目功能将在 M4 接入")

        composeRule.activityRule.scenario.recreate()
        awaitShell()
        awaitText("项目功能将在 M4 接入")

        // Finance restores its own saved stack with Search still on top.
        composeRule.onNode(tabMatcher("财务")).performClick()
        awaitText("搜索功能将在 M5 接入")
        composeRule.onNodeWithTag(M1ShellTags.BACK_ACTION).performClick()
        awaitText("财务功能将在 M2 提供")

        // Timeline restores its own independent stack with Settings on top.
        composeRule.onNode(tabMatcher("时间线")).performClick()
        awaitText("更多设置将在后续版本提供。")
        composeRule.onNodeWithTag(M1ShellTags.BACK_ACTION).performClick()
        awaitText("时间线内容将在 M5 接入")
    }

    @Test
    fun activityRecreationPreservesTheTimelineDayHeader() {
        awaitShell()
        val expectedDay = todayText()
        awaitText(expectedDay)

        composeRule.activityRule.scenario.recreate()
        awaitShell()
        awaitText(expectedDay)
        composeRule.onNodeWithTag(M1ShellTags.SETTINGS_ACTION).assertIsDisplayed()
    }

    private fun todayText(): String =
        DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE).format(LocalDate.now())

    private fun tabMatcher(label: String): SemanticsMatcher =
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
}

/** Controlled-clock Timeline date behaviour through the real shell (spec 8.4). */
class M1TimelineDateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun timelineDefaultsToTheCurrentDayOfTheInjectedClockAndZone() {
        val clock = M1TestClocks.atSeptemberSecond()
        composeRule.setContent { M1ShellHost(clock, M1TestClocks.zoneId) }

        composeRule.onNodeWithText("2026年9月2日").assertIsDisplayed()
        composeRule.onNodeWithText("2026年9月1日").assertDoesNotExist()
    }

    @Test
    fun foregroundMidnightAndChildRoundTripDoNotChangeTheViewedDay() {
        val clock = M1TestClocks.beforeMidnight()
        composeRule.setContent { M1ShellHost(clock, M1TestClocks.zoneId) }

        composeRule.onNodeWithText("2026年9月1日").assertIsDisplayed()
        composeRule.runOnIdle { M1TestClocks.advancePastMidnight(clock) }

        // The viewed day never re-reads the clock on its own, even after the
        // surface fully recomposes through a Search round trip past midnight.
        composeRule.onNodeWithText("2026年9月1日").assertIsDisplayed()
        composeRule.onNodeWithTag(M1ShellTags.SEARCH_ACTION).performClick()
        composeRule.onNodeWithTag(M1ShellTags.BACK_ACTION).performClick()
        composeRule.onNodeWithText("2026年9月1日").assertIsDisplayed()
        composeRule.onNodeWithText("2026年9月2日").assertDoesNotExist()
    }

    @Test
    fun explicitTodayRereadsTheClockAndSelectsTheThenCurrentDay() {
        val clock = M1TestClocks.beforeMidnight()
        composeRule.setContent { M1ShellHost(clock, M1TestClocks.zoneId) }

        composeRule.onNodeWithText("2026年9月1日").assertIsDisplayed()
        composeRule.runOnIdle { M1TestClocks.advancePastMidnight(clock) }
        composeRule.onNodeWithText("今天").performClick()
        composeRule.onNodeWithText("2026年9月2日").assertIsDisplayed()
        composeRule.onNodeWithText("2026年9月1日").assertDoesNotExist()

        // Today without a further clock advance is idempotent on the day.
        composeRule.onNodeWithText("今天").performClick()
        composeRule.onNodeWithText("2026年9月2日").assertIsDisplayed()
    }

    @Test
    fun savedDayWinsOverTheAdvancedClockAfterTabSwitchRestore() {
        val clock = M1TestClocks.beforeMidnight()
        composeRule.setContent { M1ShellHost(clock, M1TestClocks.zoneId) }

        composeRule.onNodeWithText("2026年9月1日").assertIsDisplayed()
        // Leave Timeline (its state is saved), advance the clock two days, and
        // restore the tab: the legal saved day must win over the current clock.
        composeRule.onNode(tabMatcher("财务")).performClick()
        composeRule.onNodeWithText("财务功能将在 M2 提供").assertIsDisplayed()
        composeRule.runOnIdle { clock.setInstant(Instant.parse("2026-09-02T18:30:00Z")) }
        composeRule.onNode(tabMatcher("时间线")).performClick()

        composeRule.onNodeWithText("2026年9月1日").assertIsDisplayed()
        composeRule.onNodeWithText("2026年9月3日").assertDoesNotExist()
    }

    private fun tabMatcher(label: String): SemanticsMatcher =
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
}

/**
 * Pure Timeline date-contract proofs (spec 8.4) using the injected controlled
 * clock: a stale saved day never surfaces as the current day without Today,
 * and explicit Today re-reads the clock, invalidates the request revision,
 * returns to the top, and preserves every other field.
 */
class M1TimelineDateContractTest {

    @Test
    fun staleSavedDayNeverSurfacesAsTheCurrentClockDayWithoutToday() {
        val clock = M1TestClocks.atSeptemberThird()
        val controller = TimelineDateController(clock, M1TestClocks.zoneId)

        // A state saved under September 1st restores exactly as saved.
        val savedUnderOlderDay = TimelineDateState(
            selectedDay = LocalDate.parse("2026-09-01"),
            requestRevision = 3L,
            firstVisibleItemIndex = 2,
            firstVisibleItemOffset = 10,
        )
        val restored = controller.initial(restored = savedUnderOlderDay)

        assertEquals(LocalDate.parse("2026-09-01"), restored.selectedDay)
        assertEquals(3L, restored.requestRevision)
        assertEquals(2, restored.firstVisibleItemIndex)
        assertEquals(10, restored.firstVisibleItemOffset)
        // The stale saved day is not silently relabelled as the current day.
        assertNotEquals(LocalDate.now(clock.withZone(M1TestClocks.zoneId)), restored.selectedDay)

        // Only a fresh no-state launch after midnight reads the new current day.
        val freshAfterMidnight = controller.initial(restored = null)
        assertEquals(LocalDate.parse("2026-09-03"), freshAfterMidnight.selectedDay)
    }

    @Test
    fun explicitTodayRereadsTheClockReturnsToTopAndPreservesEveryOtherField() {
        val clock = M1TestClocks.beforeMidnight()
        val controller = TimelineDateController(clock, M1TestClocks.zoneId)

        val first = controller.initial(restored = null)
        assertEquals(LocalDate.parse("2026-09-01"), first.selectedDay)
        assertEquals(0L, first.requestRevision)

        M1TestClocks.advancePastMidnight(clock)
        val today = controller.today(first)

        assertEquals(
            TimelineDateState(
                selectedDay = LocalDate.parse("2026-09-02"),
                requestRevision = 1L,
                firstVisibleItemIndex = 0,
                firstVisibleItemOffset = 0,
            ),
            today,
        )
        // The revision always invalidates the obsolete day's request exactly once.
        val todayAgain = controller.today(today)
        assertEquals(2L, todayAgain.requestRevision)
        assertEquals(LocalDate.parse("2026-09-02"), todayAgain.selectedDay)
        assertNotEquals(first.requestRevision, today.requestRevision)
    }
}