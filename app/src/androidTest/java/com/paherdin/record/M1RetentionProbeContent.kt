package com.paherdin.record

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.paherdin.record.core.ui.RecordIcons
import com.paherdin.record.core.ui.theme.RecordTheme
import com.paherdin.record.core.ui.theme.RecordTokens
import com.paherdin.record.navigation.FinanceHome
import com.paherdin.record.navigation.NotesHome
import com.paherdin.record.navigation.ProjectsHome
import com.paherdin.record.navigation.Settings
import com.paherdin.record.navigation.TimelineHome
import com.paherdin.record.navigation.TopLevelOrigin
import com.paherdin.record.navigation.TransactionDetail
import com.paherdin.record.navigation.UnifiedSearch
import com.paherdin.record.navigation.navigateToTopLevel

internal object M1RetentionProbeTags {
    const val ROOT = "M1RetentionProbeRoot"
    const val BACK = "M1RetentionProbeBack"

    fun tab(origin: TopLevelOrigin): String = "M1RetentionProbeTab-${origin.name}"

    fun seed(origin: TopLevelOrigin): String = "M1RetentionProbeSeed-${origin.name}"

    fun value(origin: TopLevelOrigin): String = "M1RetentionProbeValue-${origin.name}"

    fun openStack(origin: TopLevelOrigin): String = "M1RetentionProbeOpenStack-${origin.name}"

    fun stack(origin: TopLevelOrigin): String = "M1RetentionProbeStack-${origin.name}"

    fun expected(origin: TopLevelOrigin): String {
        val ordinal = TopLevelOrigin.entries.indexOf(origin) + 1
        return "${origin.name}|scroll=${ordinal * 10}|filter=filter-${origin.name}|date=2026-09-0$ordinal"
    }
}

private val probeOrigins = listOf(
    TopLevelOrigin.Timeline,
    TopLevelOrigin.Finance,
    TopLevelOrigin.Notes,
    TopLevelOrigin.Projects,
)

@Composable
internal fun M1RetentionProbeHost() {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val selectedOrigin = entry?.probeOrigin() ?: TopLevelOrigin.Timeline

    Scaffold(
        bottomBar = {
            NavigationBar {
                probeOrigins.forEach { origin ->
                    NavigationBarItem(
                        selected = selectedOrigin == origin,
                        onClick = { navController.navigateToTopLevel(origin.route()) },
                        modifier = Modifier.testTag(M1RetentionProbeTags.tab(origin)),
                        icon = { Icon(origin.icon(), contentDescription = null) },
                        label = { Text(origin.label()) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TimelineHome,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable<TimelineHome> { M1RetentionProbeRoot(TopLevelOrigin.Timeline, navController) }
            composable<FinanceHome> { M1RetentionProbeRoot(TopLevelOrigin.Finance, navController) }
            composable<NotesHome> { M1RetentionProbeRoot(TopLevelOrigin.Notes, navController) }
            composable<ProjectsHome> { M1RetentionProbeRoot(TopLevelOrigin.Projects, navController) }
            composable<TransactionDetail> { entry ->
                M1RetentionProbeStack(entry.toRoute<TransactionDetail>().origin, navController)
            }
            composable<UnifiedSearch> { entry ->
                M1RetentionProbeStack(entry.toRoute<UnifiedSearch>().origin, navController)
            }
            composable<Settings> { entry ->
                M1RetentionProbeStack(entry.toRoute<Settings>().origin, navController)
            }
        }
    }
}

@Composable
private fun M1RetentionProbeRoot(origin: TopLevelOrigin, navController: NavHostController) {
    var scroll by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("none") }
    var date by rememberSaveable { mutableStateOf("none") }
    val ordinal = probeOrigins.indexOf(origin) + 1

    Column(modifier = Modifier.testTag(M1RetentionProbeTags.ROOT)) {
        Text(
            text = "${origin.name}|scroll=$scroll|filter=$filter|date=$date",
            modifier = Modifier.testTag(M1RetentionProbeTags.value(origin)),
        )
        Button(
            onClick = {
                scroll = ordinal * 10
                filter = "filter-${origin.name}"
                date = "2026-09-0$ordinal"
            },
            modifier = Modifier.testTag(M1RetentionProbeTags.seed(origin)),
        ) {
            Text("设置 ${origin.label()} probe")
        }
        Button(
            onClick = {
                when (origin) {
                    TopLevelOrigin.Timeline,
                    TopLevelOrigin.Projects,
                    -> navController.navigate(TransactionDetail(M1TestCards.TRANSACTION_ID, origin))

                    TopLevelOrigin.Finance -> navController.navigate(UnifiedSearch(origin))
                    TopLevelOrigin.Notes -> navController.navigate(Settings(origin))
                }
            },
            modifier = Modifier.testTag(M1RetentionProbeTags.openStack(origin)),
        ) {
            Text("打开 ${origin.label()} 子栈")
        }
    }
}

@Composable
private fun M1RetentionProbeStack(origin: TopLevelOrigin, navController: NavHostController) {
    Column(modifier = Modifier.testTag(M1RetentionProbeTags.stack(origin))) {
        Text("${origin.label()} 子栈")
        Button(
            onClick = { navController.popBackStack() },
            modifier = Modifier.testTag(M1RetentionProbeTags.BACK),
        ) {
            Text("返回")
        }
    }
}

private fun NavBackStackEntry.probeOrigin(): TopLevelOrigin = when {
    destination.hasRoute(TimelineHome::class) -> TopLevelOrigin.Timeline
    destination.hasRoute(FinanceHome::class) -> TopLevelOrigin.Finance
    destination.hasRoute(NotesHome::class) -> TopLevelOrigin.Notes
    destination.hasRoute(ProjectsHome::class) -> TopLevelOrigin.Projects
    destination.hasRoute(TransactionDetail::class) -> toRoute<TransactionDetail>().origin
    destination.hasRoute(UnifiedSearch::class) -> toRoute<UnifiedSearch>().origin
    destination.hasRoute(Settings::class) -> toRoute<Settings>().origin
    else -> error("Unexpected retention probe route: ${destination.route}")
}

private fun TopLevelOrigin.route(): Any = when (this) {
    TopLevelOrigin.Timeline -> TimelineHome
    TopLevelOrigin.Finance -> FinanceHome
    TopLevelOrigin.Notes -> NotesHome
    TopLevelOrigin.Projects -> ProjectsHome
}

private fun TopLevelOrigin.icon(): ImageVector = when (this) {
    TopLevelOrigin.Timeline -> RecordIcons.timeline
    TopLevelOrigin.Finance -> RecordIcons.finance
    TopLevelOrigin.Notes -> RecordIcons.notes
    TopLevelOrigin.Projects -> RecordIcons.projects
}

private fun TopLevelOrigin.label(): String = when (this) {
    TopLevelOrigin.Timeline -> "时间线"
    TopLevelOrigin.Finance -> "财务"
    TopLevelOrigin.Notes -> "笔记"
    TopLevelOrigin.Projects -> "项目"
}
