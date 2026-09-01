package com.paherdin.record.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.paherdin.record.core.ui.ContentPresentation
import com.paherdin.record.core.ui.Phase
import com.paherdin.record.feature.finance.FinanceAvailability
import com.paherdin.record.feature.finance.FinanceEditorAvailabilityScreen
import com.paherdin.record.feature.finance.FinanceEditorKind
import com.paherdin.record.feature.finance.FinanceHomeScreen
import com.paherdin.record.feature.finance.TransactionDetailAvailabilityScreen
import com.paherdin.record.feature.notes.NoteDetailAvailabilityScreen
import com.paherdin.record.feature.notes.NoteEditorAvailabilityScreen
import com.paherdin.record.feature.notes.NotesAvailability
import com.paherdin.record.feature.notes.NotesHomeScreen
import com.paherdin.record.feature.timeline.ProjectsHomeScreen
import com.paherdin.record.feature.timeline.TimelineContent
import com.paherdin.record.feature.timeline.TimelineDateController
import com.paherdin.record.feature.timeline.TimelineDateStateSaver
import com.paherdin.record.feature.timeline.TimelineHomeScreen
import com.paherdin.record.feature.timeline.UnifiedSearchScreen
import com.paherdin.record.ui.RestoreRecoverySurface
import com.paherdin.record.ui.SettingsScreen
import java.time.Clock
import java.time.ZoneId

/**
 * The one app-owned typed navigation graph (spec 8.1). Exactly the 13 M1
 * production routes are registered; every destination is feature-owned content
 * reached through typed callbacks, and top-level switching uses single-top plus
 * saved/retained state so each tab keeps an independent stack.
 *
 * Production renders the feature-owned availability content models only. The
 * three Timeline-family content parameters let the later UI-acceptance task
 * inject synthetic, test-only ready cards to prove source-typed dispatch; the
 * defaults are the truthful M1 availability surfaces.
 */
@Composable
internal fun RecordNavHost(
    navController: NavHostController,
    clock: Clock,
    zoneId: ZoneId,
    modifier: Modifier = Modifier,
    timelineContent: TimelineContent = TimelineContent.Availability,
    projectsContent: TimelineContent = TimelineContent.Availability,
    searchContent: TimelineContent = TimelineContent.Availability,
) {
    NavHost(
        navController = navController,
        startDestination = TimelineHome,
        modifier = modifier,
    ) {
        composable<TimelineHome> {
            val dateController = remember(clock, zoneId) { TimelineDateController(clock, zoneId) }
            var dateState by rememberSaveable(stateSaver = TimelineDateStateSaver) {
                mutableStateOf(dateController.initial(restored = null))
            }
            TimelineHomeScreen(
                dateState = dateState,
                onToday = { dateState = dateController.today(dateState) },
                onOpenTransaction = { card -> navController.navigate(TransactionDetail(card.id, TopLevelOrigin.Timeline)) },
                onOpenNote = { card -> navController.navigate(NoteDetail(card.id, TopLevelOrigin.Timeline)) },
                content = timelineContent,
            )
        }
        composable<FinanceHome> {
            FinanceHomeScreen(
                presentation = ContentPresentation.Ready(FinanceAvailability),
                onRetry = {},
            )
        }
        composable<NotesHome> {
            NotesHomeScreen(
                presentation = ContentPresentation.Ready(NotesAvailability),
                onRetry = {},
            )
        }
        composable<ProjectsHome> {
            ProjectsHomeScreen(
                presentation = ContentPresentation.Ready(projectsContent),
                onOpenTransaction = { card -> navController.navigate(TransactionDetail(card.id, TopLevelOrigin.Projects)) },
                onOpenNote = { card -> navController.navigate(NoteDetail(card.id, TopLevelOrigin.Projects)) },
                onRetry = {},
            )
        }
        composable<UnifiedSearch> { entry ->
            val route = entry.toRoute<UnifiedSearch>()
            UnifiedSearchScreen(
                presentation = ContentPresentation.Ready(searchContent),
                onOpenTransaction = { card -> navController.navigate(TransactionDetail(card.id, route.origin)) },
                onOpenNote = { card -> navController.navigate(NoteDetail(card.id, route.origin)) },
                onRetry = {},
            )
        }
        composable<Settings> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<TransactionDetail> {
            TransactionDetailAvailabilityScreen(onBack = { navController.popBackStack() })
        }
        composable<NoteDetail> {
            NoteDetailAvailabilityScreen(onBack = { navController.popBackStack() })
        }
        composable<ExpenseEditor> {
            FinanceEditorAvailabilityScreen(FinanceEditorKind.Expense, onBack = { navController.popBackStack() })
        }
        composable<IncomeEditor> {
            FinanceEditorAvailabilityScreen(FinanceEditorKind.Income, onBack = { navController.popBackStack() })
        }
        composable<TransferEditor> {
            FinanceEditorAvailabilityScreen(FinanceEditorKind.Transfer, onBack = { navController.popBackStack() })
        }
        composable<NoteEditor> {
            NoteEditorAvailabilityScreen(onBack = { navController.popBackStack() })
        }
        composable<RestoreRecoveryRoute> {
            RestoreRecoverySurface(phase = Phase.Recovering, canRetry = false, onRetry = {})
        }
    }
}

/** Navigates to a top-level tab with single-top plus saved/retained state. */
internal fun NavHostController.navigateToTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination()) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}