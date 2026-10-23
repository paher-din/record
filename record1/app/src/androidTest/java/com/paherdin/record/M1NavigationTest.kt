package com.paherdin.record

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record.feature.timeline.TimelineContent
import com.paherdin.record.navigation.ExpenseEditor
import com.paherdin.record.navigation.FinanceHome
import com.paherdin.record.navigation.IncomeEditor
import com.paherdin.record.navigation.NoteDetail
import com.paherdin.record.navigation.NoteEditor
import com.paherdin.record.navigation.NotesHome
import com.paherdin.record.navigation.ProjectsHome
import com.paherdin.record.navigation.RestoreRecoveryRoute
import com.paherdin.record.navigation.Settings
import com.paherdin.record.navigation.TopLevelOrigin
import com.paherdin.record.navigation.TransactionDetail
import com.paherdin.record.navigation.TransferEditor
import com.paherdin.record.navigation.TimelineHome
import com.paherdin.record.navigation.UnifiedSearch
import com.paherdin.record.navigation.navigateToTopLevel
import java.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * M1-T08 navigation acceptance (plan Step 2, spec 7/8.1/8.2): the shell opens
 * on Timeline with the exact tab order, the global create exposes exactly the
 * four typed owners, Search/Settings preserve the exact origin, representative
 * cards dispatch to their typed owner details, chrome follows the shared
 * policy, and no route argument carries anything beyond a stable ID and the
 * origin discriminator.
 */
class M1NavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clock: Clock = M1TestClocks.atSeptemberSecond()

    private val tabs = listOf(
        "时间线" to "时间线内容将在 M5 接入",
        "财务" to "财务功能将在 M2 提供",
        "笔记" to "笔记功能将在 M3 版本中提供",
        "项目" to "项目功能将在 M4 接入",
    )

    @Test
    fun healthyShellOpensTimelineWithTheExactBottomTabOrderAndSelection() {
        composeRule.setContent { M1ShellHost(clock, M1TestClocks.zoneId) }

        composeRule.onNodeWithText("2026年9月2日").assertIsDisplayed()
        composeRule.onNodeWithText("时间线内容将在 M5 接入").assertIsDisplayed()
        val bar = composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
        assertEquals(
            listOf("时间线", "财务", "笔记", "项目"),
            bar.fetchSemanticsNode().collectVisibleTexts(),
        )
        composeRule.onNode(tabMatcher("时间线"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNode(tabMatcher("财务"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test
    fun everyTopLevelTabSwitchesAndReselectKeepsItsRootContent() {
        composeRule.setContent { M1ShellHost(clock, M1TestClocks.zoneId) }

        tabs.forEach { (label, rootCopy) ->
            composeRule.onNode(tabMatcher(label)).performClick()
            composeRule.onNodeWithText(rootCopy).assertIsDisplayed()
            // Reselecting the active tab never resets or leaves the root.
            composeRule.onNode(tabMatcher(label)).performClick()
            composeRule.onNodeWithText(rootCopy).assertIsDisplayed()
            composeRule.onNode(tabMatcher(label))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        }
    }

    @Test
    fun globalCreateExposesExactlyTheFourTypedChoicesInOrder() {
        composeRule.setContent { M1ShellHost(clock, M1TestClocks.zoneId) }

        composeRule.onNodeWithTag(M1ShellTags.CREATE_ACTION).performClick()
        val sheet = composeRule.onNodeWithTag(M1ShellTags.CREATE_SHEET).assertIsDisplayed()
        val sheetTexts = sheet.fetchSemanticsNode().collectVisibleTexts()
        assertEquals(
            listOf("支出", "收入", "转账", "笔记"),
            sheetTexts.filter { it in setOf("支出", "收入", "转账", "笔记") },
        )
        assertTrue(
            "generic Entry semantics leaked into the create sheet: $sheetTexts",
            sheetTexts.none { it.contains("Entry") || it.contains("记录") },
        )
        listOf(
            M1ShellTags.CREATE_EXPENSE,
            M1ShellTags.CREATE_INCOME,
            M1ShellTags.CREATE_TRANSFER,
            M1ShellTags.CREATE_NOTE,
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun everyCreateChoiceOpensItsTypedOwnerEditorAndBackRestoresTheOrigin() {
        composeRule.setContent { M1ShellHost(clock, M1TestClocks.zoneId) }

        val cases = listOf(
            Triple(M1ShellTags.CREATE_EXPENSE, "支出编辑将在 M2 提供", "FinanceDestinationBack"),
            Triple(M1ShellTags.CREATE_INCOME, "收入编辑将在 M2 提供", "FinanceDestinationBack"),
            Triple(M1ShellTags.CREATE_TRANSFER, "转账编辑将在 M2 提供", "FinanceDestinationBack"),
            Triple(M1ShellTags.CREATE_NOTE, "笔记编辑器将在 M3 版本中提供", "NoteEditorAvailabilityBack"),
        )
        cases.forEach { (choiceTag, editorCopy, contentBackTag) ->
            composeRule.onNodeWithTag(M1ShellTags.CREATE_ACTION).performClick()
            composeRule.onNodeWithTag(choiceTag).performClick()
            composeRule.onNodeWithText(editorCopy).assertIsDisplayed()
            // Editor chrome hides the bottom bar and offers no shell chrome.
            composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertDoesNotExist()
            composeRule.onNodeWithTag(contentBackTag).performClick()
            composeRule.onNodeWithText("时间线内容将在 M5 接入").assertIsDisplayed()
            composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
        }
    }

    @Test
    fun searchAndSettingsAreReachableFromEveryRootAndBackRestoresTheExactOrigin() {
        composeRule.setContent { M1ShellHost(clock, M1TestClocks.zoneId) }

        tabs.forEach { (label, rootCopy) ->
            composeRule.onNode(tabMatcher(label)).performClick()
            composeRule.onNodeWithText(rootCopy).assertIsDisplayed()

            composeRule.onNodeWithTag(M1ShellTags.SEARCH_ACTION).performClick()
            composeRule.onNodeWithText("搜索功能将在 M5 接入").assertIsDisplayed()
            composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
            composeRule.onNodeWithTag(M1ShellTags.BACK_ACTION).performClick()
            composeRule.onNodeWithText(rootCopy).assertIsDisplayed()

            composeRule.onNodeWithTag(M1ShellTags.SETTINGS_ACTION).performClick()
            composeRule.onNodeWithText("更多设置将在后续版本提供。").assertIsDisplayed()
            composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
            composeRule.onNodeWithTag(M1ShellTags.BACK_ACTION).performClick()
            composeRule.onNodeWithText(rootCopy).assertIsDisplayed()

            composeRule.onNodeWithTag(M1ShellTags.CREATE_ACTION).performClick()
            composeRule.onNodeWithTag(M1ShellTags.CREATE_SHEET).assertIsDisplayed()
            composeRule.onNodeWithTag(M1ShellTags.CREATE_EXPENSE).performClick()
            composeRule.onNodeWithText("支出编辑将在 M2 提供").assertIsDisplayed()
            composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertDoesNotExist()
            composeRule.onNodeWithTag("FinanceDestinationBack").performClick()
            composeRule.onNodeWithText(rootCopy).assertIsDisplayed()
        }
    }

    @Test
    fun representativeCardsRouteOnlyToTheirTypedOwnerDetailsAndBackRestoresOrigin() {
        var navController: NavHostController? = null
        var detail: TransactionDetail? = null
        var noteRoute: NoteDetail? = null
        composeRule.setContent {
            M1DispatchHost(clock, M1TestClocks.zoneId, onNavController = { navController = it })
        }

        composeRule.onNodeWithText(M1TestCards.transaction.headline).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("交易详情将在 M2 提供").assertIsDisplayed()
        composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
        composeRule.runOnIdle {
            detail = checkNotNull(navController).currentBackStackEntry?.toRoute<TransactionDetail>()
        }
        assertEquals(M1TestCards.TRANSACTION_ID, detail?.transactionId)
        assertEquals(TopLevelOrigin.Timeline, detail?.origin)
        pressSystemBack()
        composeRule.onNodeWithText(M1TestCards.transaction.headline).assertIsDisplayed()

        composeRule.onNodeWithText(M1TestCards.note.headline).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("笔记详情将在 M3 版本中提供").assertIsDisplayed()
        composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
        composeRule.runOnIdle {
            noteRoute = checkNotNull(navController).currentBackStackEntry?.toRoute<NoteDetail>()
        }
        assertEquals(M1TestCards.NOTE_ID, noteRoute?.noteId)
        assertEquals(TopLevelOrigin.Timeline, noteRoute?.origin)
        pressSystemBack()
        composeRule.onNodeWithText(M1TestCards.note.headline).assertIsDisplayed()
    }

    @Test
    fun projectsAndSearchCardsRouteFromTheirOwnOrigins() {
        var navController: NavHostController? = null
        var detail: TransactionDetail? = null
        var noteRoute: NoteDetail? = null
        composeRule.setContent {
            M1DispatchHost(
                clock = clock,
                zoneId = M1TestClocks.zoneId,
                onNavController = { navController = it },
                timelineContent = TimelineContent.Availability,
                projectsContent = M1TestCards.projects,
                searchContent = M1TestCards.search,
            )
        }

        composeRule.runOnIdle {
            checkNotNull(navController).navigateToTopLevel(ProjectsHome)
        }
        composeRule.onNodeWithText("项目功能将在 M4 接入").assertDoesNotExist()
        composeRule.onNodeWithText(M1TestCards.transaction.headline).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("交易详情将在 M2 提供").assertIsDisplayed()
        composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
        composeRule.runOnIdle {
            detail = checkNotNull(navController).currentBackStackEntry?.toRoute<TransactionDetail>()
        }
        assertEquals(M1TestCards.TRANSACTION_ID, detail?.transactionId)
        assertEquals(TopLevelOrigin.Projects, detail?.origin)
        pressSystemBack()
        // Back restores the exact Projects origin with its injected card content.
        composeRule.onNodeWithText(M1TestCards.transaction.headline).assertIsDisplayed()

        composeRule.runOnIdle {
            checkNotNull(navController).navigate(UnifiedSearch(TopLevelOrigin.Projects))
        }
        composeRule.onNodeWithText(M1TestCards.note.headline).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("笔记详情将在 M3 版本中提供").assertIsDisplayed()
        composeRule.onNodeWithTag(M1ShellTags.BOTTOM_NAVIGATION).assertIsDisplayed()
        composeRule.runOnIdle {
            noteRoute = checkNotNull(navController).currentBackStackEntry?.toRoute<NoteDetail>()
        }
        assertEquals(M1TestCards.NOTE_ID, noteRoute?.noteId)
        assertEquals(TopLevelOrigin.Projects, noteRoute?.origin)
        pressSystemBack()
        // Back restores the exact Search origin with its injected card content.
        composeRule.onNodeWithText(M1TestCards.note.headline).assertIsDisplayed()
    }

    @Test
    fun progressLockSurfaceHidesNavigationAndBlocksBackWhileLocked() {
        var lock by mutableStateOf(M1LockKind.EditorSaving)
        composeRule.setContent { M1ProgressLockHost(lockProvider = { lock }) }

        // Phase.Saving editor lock: no bottom bar, no Back, shared loading body.
        composeRule.onNodeWithTag(M1LockBottomBarTag).assertDoesNotExist()
        composeRule.onNodeWithTag(M1LockBackTag).assertDoesNotExist()
        composeRule.onNodeWithText("加载中…").assertIsDisplayed()

        composeRule.runOnIdle { lock = M1LockKind.EditorIdle }
        composeRule.onNodeWithTag(M1LockBackTag).assertIsDisplayed()
        composeRule.onNodeWithTag(M1LockBottomBarTag).assertDoesNotExist()

        composeRule.runOnIdle { lock = M1LockKind.ProgressRunning }
        composeRule.onNodeWithTag(M1LockBackTag).assertDoesNotExist()
        composeRule.onNodeWithTag(M1LockBottomBarTag).assertDoesNotExist()

        composeRule.runOnIdle { lock = M1LockKind.ProgressIdle }
        composeRule.onNodeWithTag(M1LockBackTag).assertIsDisplayed()
        composeRule.onNodeWithTag(M1LockBottomBarTag).assertDoesNotExist()
    }

    @Test
    fun productionRoutesExposeNoRoomDaoPathUriOrPayloadArguments() {
        var navController: NavHostController? = null
        composeRule.setContent {
            M1DispatchHost(clock, M1TestClocks.zoneId, onNavController = { navController = it })
        }
        val canonicalId = M1TestCards.TRANSACTION_ID
        val serializedRoutes = listOf(
            "TimelineHome" to Json.encodeToString(TimelineHome.serializer(), TimelineHome),
            "FinanceHome" to Json.encodeToString(FinanceHome.serializer(), FinanceHome),
            "NotesHome" to Json.encodeToString(NotesHome.serializer(), NotesHome),
            "ProjectsHome" to Json.encodeToString(ProjectsHome.serializer(), ProjectsHome),
            "UnifiedSearch" to Json.encodeToString(
                UnifiedSearch.serializer(),
                UnifiedSearch(TopLevelOrigin.Timeline),
            ),
            "Settings" to Json.encodeToString(Settings.serializer(), Settings(TopLevelOrigin.Finance)),
            "TransactionDetail" to Json.encodeToString(
                TransactionDetail.serializer(),
                TransactionDetail(canonicalId, TopLevelOrigin.Notes),
            ),
            "NoteDetail" to Json.encodeToString(
                NoteDetail.serializer(),
                NoteDetail(M1TestCards.NOTE_ID, TopLevelOrigin.Notes),
            ),
            "ExpenseEditor" to Json.encodeToString(ExpenseEditor.serializer(), ExpenseEditor(TopLevelOrigin.Notes)),
            "IncomeEditor" to Json.encodeToString(IncomeEditor.serializer(), IncomeEditor(TopLevelOrigin.Finance)),
            "TransferEditor" to Json.encodeToString(TransferEditor.serializer(), TransferEditor(TopLevelOrigin.Timeline)),
            "NoteEditor" to Json.encodeToString(NoteEditor.serializer(), NoteEditor(TopLevelOrigin.Notes)),
            "RestoreRecoveryRoute" to Json.encodeToString(RestoreRecoveryRoute.serializer(), RestoreRecoveryRoute),
        )
        composeRule.runOnIdle {
            val destinations = checkNotNull(navController).graph.toList()
            assertEquals("M1 registers exactly 13 production routes", 13, destinations.size)
            val expectedRouteTypes = listOf(
                TimelineHome::class,
                FinanceHome::class,
                NotesHome::class,
                ProjectsHome::class,
                UnifiedSearch::class,
                Settings::class,
                TransactionDetail::class,
                NoteDetail::class,
                ExpenseEditor::class,
                IncomeEditor::class,
                TransferEditor::class,
                NoteEditor::class,
                RestoreRecoveryRoute::class,
            )
            expectedRouteTypes.forEach { routeType ->
                assertEquals(
                    "production graph route count for ${routeType.simpleName}",
                    1,
                    destinations.count { destination -> destination.hasRoute(routeType) },
                )
            }
        }

        val argumentKeys = mapOf(
            "UnifiedSearch" to setOf("origin"),
            "Settings" to setOf("origin"),
            "TransactionDetail" to setOf("transactionId", "origin"),
            "NoteDetail" to setOf("noteId", "origin"),
            "ExpenseEditor" to setOf("origin"),
            "IncomeEditor" to setOf("origin"),
            "TransferEditor" to setOf("origin"),
            "NoteEditor" to setOf("origin"),
        )
        val forbidden = listOf("content://", "file://", "://", "/", "Dao", "Entity", "Room", "Entry", "记录")
        serializedRoutes.forEach { (name, encoded) ->
            assertTrue(
                "route $name leaks forbidden argument content: $encoded",
                forbidden.none { encoded.contains(it, ignoreCase = false) },
            )
            val decoded = Json.parseToJsonElement(encoded)
            if (decoded is JsonObject) {
                val allowed = argumentKeys[name] ?: emptySet()
                assertTrue(
                    "route $name carries unexpected argument keys: ${decoded.keys}",
                    decoded.keys.all(allowed::contains),
                )
            }
        }
    }

    private fun tabMatcher(label: String): SemanticsMatcher =
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun pressSystemBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
    }
}
