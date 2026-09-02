package com.paherdin.record.feature.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record.core.ui.ContentPresentation
import com.paherdin.record.core.ui.R as CoreUiR
import com.paherdin.record.core.ui.UiErrorCategory
import com.paherdin.record.core.ui.theme.RecordTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TimelineScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val dateState = TimelineDateController(
        Clock.fixed(Instant.parse("2026-09-01T02:30:00Z"), zoneId),
        zoneId,
    ).initial(restored = null)

    private val transactionCard = TimelineCardUiModel(
        id = "11111111-1111-1111-1111-111111111111",
        kind = TimelineCardKind.Transaction,
        headline = "测试交易卡片",
    )
    private val noteCard = TimelineCardUiModel(
        id = "22222222-2222-2222-2222-222222222222",
        kind = TimelineCardKind.Note,
        headline = "测试笔记卡片",
    )

    @Test
    fun timelineHomeShowsCurrentDateTodayAndAvailability() {
        var todayTapped = false
        composeRule.setContent {
            RecordTheme {
                TimelineHomeScreen(
                    dateState = dateState,
                    onToday = { todayTapped = true },
                    onOpenTransaction = {},
                    onOpenNote = {},
                )
            }
        }

        composeRule.onNodeWithText("2026年9月1日").assertIsDisplayed()
        composeRule.onNodeWithTag(TimelineScreenTags.TODAY).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(context.getString(R.string.timeline_availability)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(TimelineScreenTags.CARD_ROW).assertCountEquals(0)
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onNodeWithTag(TimelineScreenTags.TODAY).performClick()
        composeRule.runOnIdle { assertTrue(todayTapped) }
    }

    @Test
    fun timelineHomeCardsDispatchTransactionAndNoteByKind() {
        var openedTransaction: TimelineCardUiModel? = null
        var openedNote: TimelineCardUiModel? = null
        composeRule.setContent {
            RecordTheme {
                TimelineHomeScreen(
                    dateState = dateState,
                    onToday = {},
                    onOpenTransaction = { openedTransaction = it },
                    onOpenNote = { openedNote = it },
                    content = TimelineContent.Cards(listOf(transactionCard, noteCard)),
                )
            }
        }

        composeRule.onAllNodesWithTag(TimelineScreenTags.CARD_ROW).assertCountEquals(2)
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onNodeWithText(transactionCard.headline).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(transactionCard, openedTransaction) }
        composeRule.onNodeWithText(noteCard.headline).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(noteCard, openedNote) }
        composeRule.onNodeWithText(context.getString(R.string.timeline_availability)).assertDoesNotExist()
    }

    @Test
    fun projectsHomeRendersReadyAvailability() {
        composeRule.setContent {
            RecordTheme {
                ProjectsHomeScreen(
                    presentation = ContentPresentation.Ready(TimelineContent.Availability),
                    onOpenTransaction = {},
                    onOpenNote = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.projects_availability)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(TimelineScreenTags.CARD_ROW).assertCountEquals(0)
    }

    @Test
    fun projectsHomeReadyCardsDispatchByKind() {
        var openedNote: TimelineCardUiModel? = null
        composeRule.setContent {
            RecordTheme {
                ProjectsHomeScreen(
                    presentation = ContentPresentation.Ready(TimelineContent.Cards(listOf(noteCard))),
                    onOpenTransaction = {},
                    onOpenNote = { openedNote = it },
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText(noteCard.headline).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(noteCard, openedNote) }
    }

    @Test
    fun unifiedSearchRendersReadyAvailabilityAndCardsDispatch() {
        var openedTransaction: TimelineCardUiModel? = null
        var presentation by mutableStateOf<ContentPresentation<TimelineContent>>(
            ContentPresentation.Ready(TimelineContent.Availability),
        )
        composeRule.setContent {
            RecordTheme {
                UnifiedSearchScreen(
                    presentation = presentation,
                    onOpenTransaction = { openedTransaction = it },
                    onOpenNote = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.search_availability)).assertIsDisplayed()
        composeRule.runOnIdle {
            presentation = ContentPresentation.Ready(TimelineContent.Cards(listOf(transactionCard)))
        }
        composeRule.onNodeWithText(transactionCard.headline).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(transactionCard, openedTransaction) }
    }

    @Test
    fun projectsHomeRendersSharedLoadingAndRecoverableErrorStates() {
        var presentation by mutableStateOf<ContentPresentation<TimelineContent>>(ContentPresentation.Loading)
        var retried = false
        composeRule.setContent {
            RecordTheme {
                ProjectsHomeScreen(
                    presentation = presentation,
                    onOpenTransaction = {},
                    onOpenNote = {},
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(CoreUiR.string.shared_loading)).assertIsDisplayed()
        composeRule.runOnIdle {
            presentation = ContentPresentation.Error(UiErrorCategory.TemporarilyUnavailable, canRetry = true)
        }
        composeRule.onNodeWithTag("SharedContentRetry").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun timelineHomeRemainsUsableInDarkThemeAtTwoHundredPercentFont() {
        composeRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, 2f)) {
                RecordTheme(darkTheme = true) {
                    TimelineHomeScreen(
                        dateState = dateState,
                        onToday = {},
                        onOpenTransaction = {},
                        onOpenNote = {},
                        content = TimelineContent.Cards(listOf(transactionCard, noteCard)),
                    )
                }
            }
        }

        composeRule.onNodeWithText("2026年9月1日", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(TimelineScreenTags.TODAY, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
        composeRule.onNodeWithText(transactionCard.headline, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(noteCard.headline, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.timeline_screen_heading), useUnmergedTree = true)
            .assertIsDisplayed()
        assertBoundsWithinRoot(
            composeRule.onNodeWithText("2026年9月1日", useUnmergedTree = true),
            composeRule.onNodeWithTag(TimelineScreenTags.TODAY, useUnmergedTree = true),
        )
    }

    @Test
    fun staleSavedDayDoesNotSurfaceTheCurrentClockDayWithoutToday() {
        // Cross-module M1-T08 acceptance (spec 8.4): a state saved under an
        // older day restores exactly as saved and never surfaces as the
        // current clock day; only explicit Today re-reads the injected clock,
        // invalidates the request revision, and returns the list to the top.
        val zone = ZoneId.of("Asia/Shanghai")
        val savedUnderOlderDay = TimelineDateState(
            selectedDay = LocalDate.parse("2026-09-01"),
            requestRevision = 3L,
            firstVisibleItemIndex = 2,
            firstVisibleItemOffset = 10,
        )
        val advancedController = TimelineDateController(
            Clock.fixed(Instant.parse("2026-09-02T18:30:00Z"), zone),
            zone,
        )
        val currentClock = Clock.fixed(Instant.parse("2026-09-02T18:30:00Z"), zone)

        composeRule.setContent {
            RecordTheme {
                TimelineHomeScreen(
                    dateState = advancedController.initial(restored = savedUnderOlderDay),
                    onToday = {},
                    onOpenTransaction = {},
                    onOpenNote = {},
                )
            }
        }

        composeRule.onNodeWithText("2026年9月1日").assertIsDisplayed()
        composeRule.onNodeWithText("2026年9月3日").assertDoesNotExist()

        val restored = advancedController.initial(restored = savedUnderOlderDay)
        assertEquals(LocalDate.parse("2026-09-01"), restored.selectedDay)
        assertEquals(3L, restored.requestRevision)
        assertNotEquals(LocalDate.now(currentClock), restored.selectedDay)
        val today = advancedController.today(restored)
        assertEquals(LocalDate.parse("2026-09-03"), today.selectedDay)
        assertEquals(4L, today.requestRevision)
        assertEquals(0, today.firstVisibleItemIndex)
        assertEquals(0, today.firstVisibleItemOffset)
    }

    private fun assertBoundsWithinRoot(vararg nodes: SemanticsNodeInteraction) {
        val root = composeRule.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot()
        nodes.forEach { node ->
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "node outside root bounds: node=$bounds root=$root",
                bounds.left >= root.left && bounds.top >= root.top &&
                    bounds.right <= root.right && bounds.bottom <= root.bottom,
            )
        }
    }
}
