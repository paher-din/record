package com.paherdin.record.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.paherdin.record.R
import com.paherdin.record.core.ui.DestinationKind
import com.paherdin.record.core.ui.Phase
import com.paherdin.record.core.ui.RecordIcons
import com.paherdin.record.core.ui.ShellChromePolicy
import com.paherdin.record.core.ui.theme.RecordTokens
import com.paherdin.record.navigation.ExpenseEditor
import com.paherdin.record.navigation.FinanceHome
import com.paherdin.record.navigation.IncomeEditor
import com.paherdin.record.navigation.NoteDetail
import com.paherdin.record.navigation.NoteEditor
import com.paherdin.record.navigation.NotesHome
import com.paherdin.record.navigation.ProjectsHome
import com.paherdin.record.navigation.RestoreRecoveryRoute
import com.paherdin.record.navigation.RecordNavHost
import com.paherdin.record.navigation.navigateToTopLevel
import com.paherdin.record.navigation.Settings
import com.paherdin.record.navigation.TransferEditor
import com.paherdin.record.navigation.TimelineHome
import com.paherdin.record.navigation.TopLevelOrigin
import com.paherdin.record.navigation.TransactionDetail
import com.paherdin.record.navigation.UnifiedSearch
import java.time.Clock
import java.time.ZoneId

private object RecordShellTags {
    const val BOTTOM_NAVIGATION = "RecordBottomNavigation"
    const val CREATE_ACTION = "RecordShellCreate"
    const val SEARCH_ACTION = "RecordShellSearch"
    const val SETTINGS_ACTION = "RecordShellSettings"
    const val BACK_ACTION = "RecordShellBack"
    const val CREATE_SHEET = "RecordCreateSheet"
    const val CREATE_EXPENSE = "RecordCreateExpense"
    const val CREATE_INCOME = "RecordCreateIncome"
    const val CREATE_TRANSFER = "RecordCreateTransfer"
    const val CREATE_NOTE = "RecordCreateNote"
    const val RESTORE_RECOVERY = "RestoreRecoverySurface"
    const val RESTORE_RECOVERY_RETRY = "RestoreRecoveryRetry"
}

/** The typed owner behind one global-create choice (spec 7). */
internal enum class CreateOwner { Expense, Income, Transfer, Note }

/** Maps one global-create choice onto its typed owner editor route. */
internal fun CreateOwner.toRoute(origin: TopLevelOrigin): Any = when (this) {
    CreateOwner.Expense -> ExpenseEditor(origin)
    CreateOwner.Income -> IncomeEditor(origin)
    CreateOwner.Transfer -> TransferEditor(origin)
    CreateOwner.Note -> NoteEditor(origin)
}

/** The bottom-navigation origins in their exact semantic and visible order. */
private val bottomTabs = listOf(
    TopLevelOrigin.Timeline,
    TopLevelOrigin.Finance,
    TopLevelOrigin.Notes,
    TopLevelOrigin.Projects,
)

private fun routeOf(origin: TopLevelOrigin): Any = when (origin) {
    TopLevelOrigin.Timeline -> TimelineHome
    TopLevelOrigin.Finance -> FinanceHome
    TopLevelOrigin.Notes -> NotesHome
    TopLevelOrigin.Projects -> ProjectsHome
}

private fun tabLabelOf(origin: TopLevelOrigin): Int = when (origin) {
    TopLevelOrigin.Timeline -> R.string.shell_tab_timeline
    TopLevelOrigin.Finance -> R.string.shell_tab_finance
    TopLevelOrigin.Notes -> R.string.shell_tab_notes
    TopLevelOrigin.Projects -> R.string.shell_tab_projects
}

private fun tabIconOf(origin: TopLevelOrigin): ImageVector = when (origin) {
    TopLevelOrigin.Timeline -> RecordIcons.timeline
    TopLevelOrigin.Finance -> RecordIcons.finance
    TopLevelOrigin.Notes -> RecordIcons.notes
    TopLevelOrigin.Projects -> RecordIcons.projects
}

/** Gate phases that represent launch/recovery work in progress. */
private val startupProgressPhases = listOf(Phase.Checking, Phase.Running, Phase.Recovering)

/** Gate phases that are terminal blocked outcomes. */
private val startupBlockedPhases = listOf(Phase.RecoverableFailure, Phase.BlockingFailure)

/** The chrome kind and launching origin of one back-stack destination. */
private data class ShellDestination(val kind: DestinationKind, val origin: TopLevelOrigin?)

/**
 * Maps the current destination onto the shared chrome-policy input. The
 * exhaustive `when` covers exactly the 13 M1 production routes; anything else
 * (including a failed restoration of a deleted route) returns null and the
 * shell renders no chrome at all instead of a default-visible fallback.
 */
private fun NavBackStackEntry.toShellDestination(): ShellDestination? {
    val destination = this.destination
    return when {
        destination.hasRoute(TimelineHome::class) -> ShellDestination(DestinationKind.TopLevel, TopLevelOrigin.Timeline)
        destination.hasRoute(FinanceHome::class) -> ShellDestination(DestinationKind.TopLevel, TopLevelOrigin.Finance)
        destination.hasRoute(NotesHome::class) -> ShellDestination(DestinationKind.TopLevel, TopLevelOrigin.Notes)
        destination.hasRoute(ProjectsHome::class) -> ShellDestination(DestinationKind.TopLevel, TopLevelOrigin.Projects)
        destination.hasRoute(UnifiedSearch::class) -> ShellDestination(DestinationKind.Global, toRoute<UnifiedSearch>().origin)
        destination.hasRoute(Settings::class) -> ShellDestination(DestinationKind.Global, toRoute<Settings>().origin)
        destination.hasRoute(TransactionDetail::class) -> ShellDestination(DestinationKind.Detail, toRoute<TransactionDetail>().origin)
        destination.hasRoute(NoteDetail::class) -> ShellDestination(DestinationKind.Detail, toRoute<NoteDetail>().origin)
        destination.hasRoute(ExpenseEditor::class) -> ShellDestination(DestinationKind.Editor, toRoute<ExpenseEditor>().origin)
        destination.hasRoute(IncomeEditor::class) -> ShellDestination(DestinationKind.Editor, toRoute<IncomeEditor>().origin)
        destination.hasRoute(TransferEditor::class) -> ShellDestination(DestinationKind.Editor, toRoute<TransferEditor>().origin)
        destination.hasRoute(NoteEditor::class) -> ShellDestination(DestinationKind.Editor, toRoute<NoteEditor>().origin)
        destination.hasRoute(RestoreRecoveryRoute::class) -> ShellDestination(DestinationKind.Startup, null)
        else -> null
    }
}

/**
 * The M1 application shell: one app-owned `NavController`, the four-tab
 * scaffold, and the global commands. Bottom navigation and the top bar derive
 * only from [ShellChromePolicy]; no destination overrides the bar
 * independently, and an unmapped destination renders no shell chrome at all.
 */
@Composable
internal fun RecordApp(clock: Clock, zoneId: ZoneId) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val shellDestination = backStackEntry?.toShellDestination()
    // Bottom navigation is derived only from the shared chrome policy; a
    // destination the policy cannot map renders no shell chrome at all.
    val chrome = ShellChromePolicy.resolve(
        kind = shellDestination?.kind ?: DestinationKind.Startup,
        phase = Phase.Idle,
    )
    val selectedOrigin = shellDestination?.origin ?: TopLevelOrigin.Timeline
    var createSheetVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            when (shellDestination?.kind) {
                DestinationKind.TopLevel -> RecordTopBar(
                    onCreate = { createSheetVisible = true },
                    onSearch = { navController.navigate(UnifiedSearch(selectedOrigin)) },
                    onSettings = { navController.navigate(Settings(selectedOrigin)) },
                )
                DestinationKind.Global -> RecordBackBar(onBack = { navController.popBackStack() })
                else -> {}
            }
        },
        bottomBar = {
            if (chrome.showBottomBar) {
                RecordBottomNavigation(
                    selectedOrigin = selectedOrigin,
                    onOriginSelected = { origin -> navController.navigateToTopLevel(routeOf(origin)) },
                )
            }
        },
    ) { contentPadding ->
        RecordNavHost(
            navController = navController,
            clock = clock,
            zoneId = zoneId,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
    }
    if (createSheetVisible) {
        RecordCreateSheet(
            onCreateOwner = { owner ->
                createSheetVisible = false
                navController.navigate(owner.toRoute(selectedOrigin))
            },
            onDismiss = { createSheetVisible = false },
        )
    }
}

@Composable
private fun RecordBottomNavigation(selectedOrigin: TopLevelOrigin, onOriginSelected: (TopLevelOrigin) -> Unit) {
    NavigationBar(modifier = Modifier.testTag(RecordShellTags.BOTTOM_NAVIGATION)) {
        bottomTabs.forEach { origin ->
            NavigationBarItem(
                selected = selectedOrigin == origin,
                onClick = { onOriginSelected(origin) },
                icon = { Icon(imageVector = tabIconOf(origin), contentDescription = null) },
                label = { Text(stringResource(tabLabelOf(origin))) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordTopBar(onCreate: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    TopAppBar(
        title = {},
        actions = {
            ShellAction(RecordIcons.create, stringResource(R.string.shell_action_create), RecordShellTags.CREATE_ACTION, onCreate)
            ShellAction(RecordIcons.search, stringResource(R.string.shell_action_search), RecordShellTags.SEARCH_ACTION, onSearch)
            ShellAction(RecordIcons.settings, stringResource(R.string.shell_action_settings), RecordShellTags.SETTINGS_ACTION, onSettings)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordBackBar(onBack: () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            ShellAction(
                icon = RecordIcons.back,
                contentDescription = stringResource(R.string.shell_action_back),
                tag = RecordShellTags.BACK_ACTION,
                onClick = onBack,
            )
        },
        actions = {},
    )
}

@Composable
private fun ShellAction(icon: ImageVector, contentDescription: String, tag: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .testTag(tag)
            .defaultMinSize(
                minWidth = RecordTokens.minimumTouchTarget,
                minHeight = RecordTokens.minimumTouchTarget,
            ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/** The global create modal: exactly the four typed owners, in order (spec 7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordCreateSheet(
    onCreateOwner: (CreateOwner) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RecordShellTags.CREATE_SHEET),
    ) {
        Column(modifier = Modifier.padding(bottom = RecordTokens.spaceMedium)) {
            Text(
                text = stringResource(R.string.create_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = RecordTokens.spaceMedium, vertical = RecordTokens.spaceSmall)
                    .semantics { heading() },
            )
            CreateChoice(
                label = stringResource(R.string.create_choice_expense),
                tag = RecordShellTags.CREATE_EXPENSE,
            ) { onCreateOwner(CreateOwner.Expense) }
            CreateChoice(
                label = stringResource(R.string.create_choice_income),
                tag = RecordShellTags.CREATE_INCOME,
            ) { onCreateOwner(CreateOwner.Income) }
            CreateChoice(
                label = stringResource(R.string.create_choice_transfer),
                tag = RecordShellTags.CREATE_TRANSFER,
            ) { onCreateOwner(CreateOwner.Transfer) }
            CreateChoice(
                label = stringResource(R.string.create_choice_note),
                tag = RecordShellTags.CREATE_NOTE,
            ) { onCreateOwner(CreateOwner.Note) }
        }
    }
}

@Composable
private fun CreateChoice(label: String, tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RecordTokens.minimumTouchTarget)
            .testTag(tag),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The data-free recovery/blocked surface (spec 9.2): the startup checking,
 * detection, recovery, and blocked states render this surface with no shell,
 * no user data, and a Retry action only when the coordinator permits one.
 * Each state exposes exactly one heading and announces its state politely.
 */
@Composable
internal fun RestoreRecoverySurface(
    phase: Phase,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    val title = stringResource(
        when (phase) {
            Phase.Checking -> R.string.startup_gate_checking_title
            Phase.Running -> R.string.startup_gate_recovery_detected_title
            Phase.Recovering -> R.string.startup_gate_recovering_title
            else -> R.string.startup_gate_blocked_title
        },
    )
    val body = stringResource(
        when (phase) {
            Phase.RecoverableFailure -> R.string.startup_gate_recoverable_body
            Phase.BlockingFailure -> R.string.startup_gate_blocking_body
            else -> R.string.startup_gate_checking_body
        },
    )
    val stateDescriptionText = stringResource(
        when (phase) {
            Phase.Checking -> R.string.startup_gate_checking_state_description
            Phase.Running -> R.string.startup_gate_recovery_detected_state_description
            Phase.Recovering -> R.string.startup_gate_recovering_state_description
            else -> R.string.startup_gate_state_description
        },
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(RecordShellTags.RESTORE_RECOVERY)
            .semantics(mergeDescendants = true) {
                stateDescription = stateDescriptionText
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (phase in startupProgressPhases) {
                CircularProgressIndicator()
                Spacer(Modifier.height(RecordTokens.spaceMedium))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(RecordTokens.spaceMedium)
                    .semantics { heading() },
            )
            if (phase in startupBlockedPhases) {
                Spacer(Modifier.height(RecordTokens.spaceSmall))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = RecordTokens.spaceLarge),
                )
            }
            if (canRetry) {
                Spacer(Modifier.height(RecordTokens.spaceLarge))
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .testTag(RecordShellTags.RESTORE_RECOVERY_RETRY)
                        .defaultMinSize(
                            minWidth = RecordTokens.minimumTouchTarget,
                            minHeight = RecordTokens.minimumTouchTarget,
                        ),
                ) {
                    Text(stringResource(R.string.startup_gate_retry))
                }
            }
        }
    }
}