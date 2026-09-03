package com.paherdin.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.paherdin.record.core.ui.ContentPresentation
import com.paherdin.record.core.ui.DestinationKind
import com.paherdin.record.core.ui.Phase
import com.paherdin.record.core.ui.RecordIcons
import com.paherdin.record.core.ui.SharedContent
import com.paherdin.record.core.ui.ShellChromePolicy
import com.paherdin.record.core.ui.theme.RecordTheme
import com.paherdin.record.core.ui.theme.RecordTokens
import com.paherdin.record.feature.timeline.TimelineCardKind
import com.paherdin.record.feature.timeline.TimelineCardUiModel
import com.paherdin.record.feature.timeline.TimelineContent
import com.paherdin.record.feature.timeline.TimelineDateController
import com.paherdin.record.feature.timeline.TimelineHomeScreen
import com.paherdin.record.ui.RecordApp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * M1-T08 test-only fixtures (plan Step 1). Everything in this file lives in the
 * `androidTest` source set only: no test switch, hook, fixture, or synthetic
 * model exists in `main` or `release`. The synthetic Timeline cards are visibly
 * test-only [TimelineContent.Cards] values injected through the production
 * `RecordApp` content parameters; they never become product data (spec 6/7).
 */

/**
 * A controlled [Clock] whose instant a test advances to model foreground
 * midnight and clock drift. The production composition root owns the real
 * clock; only tests need re-readable control over it.
 */
internal class M1SettableClock(
    initial: Instant,
    private val zone: ZoneId,
) : Clock() {
    private var current: Instant = initial

    override fun getZone(): ZoneId = zone

    override fun withZone(zoneId: ZoneId): Clock = M1SettableClock(current, zoneId)

    override fun instant(): Instant = current

    fun setInstant(instant: Instant) {
        current = instant
    }
}

/** Controlled time sources for the Timeline date contract (spec 8.4). */
internal object M1TestClocks {

    /** The device zone the controlled clocks are read in. */
    val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-09-01 23:30 in [zoneId]: the viewed day is September 1st. */
    fun beforeMidnight(): M1SettableClock =
        M1SettableClock(Instant.parse("2026-09-01T15:30:00Z"), zoneId)

    /** 2026-09-02 02:30 in [zoneId]: the viewed day is September 2nd. */
    fun atSeptemberSecond(): M1SettableClock =
        M1SettableClock(Instant.parse("2026-09-01T18:30:00Z"), zoneId)

    /** 2026-09-03 02:30 in [zoneId]: the viewed day is September 3rd. */
    fun atSeptemberThird(): M1SettableClock =
        M1SettableClock(Instant.parse("2026-09-02T18:30:00Z"), zoneId)

    /** One hour after [beforeMidnight]: the local day has rolled to September 2nd. */
    fun advancePastMidnight(clock: M1SettableClock) {
        clock.setInstant(Instant.parse("2026-09-01T16:30:00Z"))
    }
}

/** Synthetic, visibly test-only Timeline cards proving source-typed dispatch. */
internal object M1TestCards {
    const val TRANSACTION_ID = "0d8ecd0b-8b5c-4a3f-9a2e-1f0c5b6d7e8f"
    const val NOTE_ID = "7c1e39a4-5b2d-4c6e-8f10-9a0b3c4d5e6f"

    val transaction = TimelineCardUiModel(
        id = TRANSACTION_ID,
        kind = TimelineCardKind.Transaction,
        headline = "测试交易卡片",
    )
    val note = TimelineCardUiModel(
        id = NOTE_ID,
        kind = TimelineCardKind.Note,
        headline = "测试笔记卡片",
    )

    val timeline = TimelineContent.Cards(listOf(transaction, note))
    val projects = TimelineContent.Cards(listOf(transaction))
    val search = TimelineContent.Cards(listOf(note))
}

/**
 * Mirrors of the private production shell tags. Tests address the shell through
 * the same stable testTag contract the shell renders.
 */
internal object M1ShellTags {
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

/** Tags and copy owned by the fixture surfaces themselves. */
internal object M1FixtureTags {
    const val LOCK_BACK = "M1LockBack"
    const val LOCK_BOTTOM_BAR = "M1LockBottomBar"
    const val PRESENTATION_READY_HEADING = "M1PresentationReadyHeading"
    const val PRESENTATION_READY_ACTION = "M1PresentationReadyAction"
    const val PRESENTATION_BAR = "M1PresentationBottomBar"
    const val PRESENTATION_READY_TEXT = "测试就绪内容"
}

/**
 * The real production shell under the A/A1 theme with test-controlled dark
 * scheme and font scale. One composition per test renders the entire shell.
 */
@Composable
internal fun M1ShellHost(
    clock: Clock,
    zoneId: ZoneId,
    darkTheme: Boolean = false,
    fontScale: Float = 1f,
    content: @Composable () -> Unit = { RecordApp(clock = clock, zoneId = zoneId) },
) {
    FixtureTheme(darkTheme, fontScale) {
        content()
    }
}

/**
 * Test host around the production `RecordApp` shell with synthetic Timeline
 * content injected into the three Timeline-family destinations. This is the
 * only way representative cards can exist (spec 7: details are reached only
 * from injected representative UI); the host captures the app-owned controller
 * so tests may assert typed destinations without features ever seeing it.
 */
@Composable
internal fun M1DispatchHost(
    clock: Clock,
    zoneId: ZoneId,
    onNavController: (NavHostController) -> Unit,
    timelineContent: TimelineContent = M1TestCards.timeline,
    projectsContent: TimelineContent = TimelineContent.Availability,
    searchContent: TimelineContent = TimelineContent.Availability,
) {
    FixtureTheme(darkTheme = false, fontScale = 1f) {
        val navController = rememberNavController()
        LaunchedEffect(navController) { onNavController(navController) }
        RecordApp(
            clock = clock,
            zoneId = zoneId,
            providedNavController = navController,
            timelineContent = timelineContent,
            projectsContent = projectsContent,
            searchContent = searchContent,
        )
    }
}

internal data class M1TimelineTestResult(
    val requestRevision: Long,
    val content: TimelineContent,
)

/** Test-only asynchronous result host for the Timeline request-revision contract. */
@Composable
internal fun M1TimelineRequestHost(
    clock: Clock,
    zoneId: ZoneId,
    resultProvider: () -> M1TimelineTestResult?,
) {
    val controller = remember(clock, zoneId) { TimelineDateController(clock, zoneId) }
    var dateState by remember { mutableStateOf(controller.initial(restored = null)) }
    var acceptedContent by remember { mutableStateOf<TimelineContent>(TimelineContent.Availability) }
    val result = resultProvider()

    LaunchedEffect(result, dateState.requestRevision) {
        result
            ?.takeIf { it.requestRevision == dateState.requestRevision }
            ?.let { acceptedContent = it.content }
    }

    TimelineHomeScreen(
        dateState = dateState,
        onToday = {
            dateState = controller.today(dateState)
            acceptedContent = TimelineContent.Availability
        },
        onOpenTransaction = {},
        onOpenNote = {},
        content = acceptedContent,
        modifier = Modifier.fillMaxSize(),
    )
}

/** The test-only chrome lock kinds the progress destination proves (spec 8.2). */
internal enum class M1LockKind {
    EditorSaving,
    EditorIdle,
    ProgressRunning,
    ProgressIdle,
}

/**
 * The test-only representative editor/progress destination (spec 7: "a
 * test-only representative progress destination verifies the shared chrome
 * policy and is excluded from main and release"). The visible chrome derives
 * only from the shared [ShellChromePolicy]: a `Phase.Saving` editor lock hides
 * Back, a running progress lock hides Back and navigation, and the body stays a
 * shared loading presentation with no enabled content actions.
 */
@Composable
internal fun M1ProgressLockHost(lockProvider: () -> M1LockKind) {
    FixtureTheme(darkTheme = false, fontScale = 1f) {
        val (kind, phase) = when (lockProvider()) {
            M1LockKind.EditorSaving -> DestinationKind.Editor to Phase.Saving
            M1LockKind.EditorIdle -> DestinationKind.Editor to Phase.Idle
            M1LockKind.ProgressRunning -> DestinationKind.Progress to Phase.Running
            M1LockKind.ProgressIdle -> DestinationKind.Progress to Phase.Idle
        }
        val chrome = ShellChromePolicy.resolve(kind = kind, phase = phase)
        Scaffold(
            topBar = {
                if (chrome.allowBack) {
                    IconButton(
                        onClick = {},
                        modifier = Modifier.testTag(M1FixtureTags.LOCK_BACK),
                    ) {
                        Icon(
                            imageVector = RecordIcons.back,
                            contentDescription = "返回",
                        )
                    }
                }
            },
            bottomBar = {
                if (chrome.showBottomBar) {
                    NavigationBar(modifier = Modifier.testTag(M1FixtureTags.LOCK_BOTTOM_BAR)) {
                        NavigationBarItem(
                            selected = true,
                            onClick = {},
                            icon = { Icon(imageVector = RecordIcons.timeline, contentDescription = null) },
                            label = { Text("时间线") },
                        )
                    }
                }
            },
        ) { contentPadding ->
            SharedContent(
                presentation = ContentPresentation.Loading,
                onRetry = {},
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                readyContent = {},
            )
        }
    }
}

/**
 * Shell-shaped presentation host: the shared body presentation inside the
 * chrome-policy scaffold with a representative bottom bar, so loading/empty/
 * error/ready are exercised together with the shell chrome at any scheme and
 * font scale (spec 5.3/6).
 */
@Composable
internal fun M1PresentationHost(
    presentationProvider: () -> ContentPresentation<String>,
    darkTheme: Boolean = false,
    fontScale: Float = 1f,
    onRetry: () -> Unit = {},
    onReadyAction: () -> Unit = {},
) {
    FixtureTheme(darkTheme, fontScale) {
        val chrome = ShellChromePolicy.resolve(kind = DestinationKind.TopLevel, phase = Phase.Idle)
        Scaffold(
            bottomBar = {
                if (chrome.showBottomBar) {
                    NavigationBar(modifier = Modifier.testTag(M1FixtureTags.PRESENTATION_BAR)) {
                        M1PresentationTab("时间线", RecordIcons.timeline, selected = true)
                        M1PresentationTab("财务", RecordIcons.finance)
                        M1PresentationTab("笔记", RecordIcons.notes)
                        M1PresentationTab("项目", RecordIcons.projects)
                    }
                }
            },
        ) { contentPadding ->
            SharedContent(
                presentation = presentationProvider(),
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                readyContent = { content ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(RecordTokens.spaceSmall),
                    ) {
                        Text(
                            text = "${M1FixtureTags.PRESENTATION_READY_TEXT}:$content",
                            modifier = Modifier
                                .testTag(M1FixtureTags.PRESENTATION_READY_HEADING)
                                .semantics { heading() },
                        )
                        Button(
                            onClick = onReadyAction,
                            modifier = Modifier
                                .testTag(M1FixtureTags.PRESENTATION_READY_ACTION)
                                .defaultMinSize(
                                    minWidth = RecordTokens.minimumTouchTarget,
                                    minHeight = RecordTokens.minimumTouchTarget,
                                ),
                        ) {
                            Text(text = "$content 操作")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RowScope.M1PresentationTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean = false,
) {
    NavigationBarItem(
        selected = selected,
        onClick = {},
        icon = { Icon(imageVector = icon, contentDescription = null) },
        label = { Text(label) },
    )
}

/** Collects visible text in deterministic traversal order. */
internal fun SemanticsNode.collectVisibleTexts(): List<String> = buildList {
    if (config.contains(SemanticsProperties.Text)) {
        addAll(config[SemanticsProperties.Text].map { it.text })
    }
    children.forEach { addAll(it.collectVisibleTexts()) }
}

/** Collects content descriptions only, in deterministic traversal order. */
internal fun SemanticsNode.collectContentDescriptions(): List<String> = buildList {
    if (config.contains(SemanticsProperties.ContentDescription)) {
        addAll(config[SemanticsProperties.ContentDescription])
    }
    children.forEach { addAll(it.collectContentDescriptions()) }
}

internal val M1LockBackTag = M1FixtureTags.LOCK_BACK
internal val M1LockBottomBarTag = M1FixtureTags.LOCK_BOTTOM_BAR
internal val M1PresentationReadyHeadingTag = M1FixtureTags.PRESENTATION_READY_HEADING
internal val M1PresentationReadyActionTag = M1FixtureTags.PRESENTATION_READY_ACTION
internal val M1PresentationBottomBarTag = M1FixtureTags.PRESENTATION_BAR

/** The controlled dark-scheme/font-scale wrapper shared by all fixture hosts. */
@Composable
private fun FixtureTheme(
    darkTheme: Boolean,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val current = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
        RecordTheme(darkTheme = darkTheme) {
            content()
        }
    }
}
