package com.paherdin.record.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record.core.ui.theme.RecordPalette
import com.paherdin.record.core.ui.theme.RecordTheme
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SharedContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loadingText = context.getString(R.string.shared_loading)
    private val emptyText = context.getString(R.string.shared_empty)
    private val loadingTag = "SharedContentLoading"
    private val emptyTag = "SharedContentEmpty"
    private val errorTag = "SharedContentError"
    private val readyTag = "SharedContentReady"
    private val retryTag = "SharedContentRetry"
    private val readyHeadingTag = "SharedContentReadyHeading"
    private val readyActionTag = "SharedContentReadyAction"
    private val readyActionText = "ready action"

    @Test
    fun loadingStateShowsLoadingTextAndNoRetry() {
        setContent(ContentPresentation.Loading)

        composeRule.onNodeWithText(loadingText).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()
    }

    @Test
    fun emptyStateShowsEmptyTextAndNoRetry() {
        setContent(ContentPresentation.Empty(EmptyReason.NoApplicableItems))

        composeRule.onNodeWithText(emptyText).assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()
    }

    @Test
    fun recoverableErrorShowsRetryAndInvokesCallback() {
        var retried = false
        setContent(
            ContentPresentation.Error(UiErrorCategory.TemporarilyUnavailable, canRetry = true),
            onRetry = { retried = true },
        )

        composeRule.onNodeWithText(context.getString(R.string.shared_error_temporarily_unavailable))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun errorWithoutRetryHidesRetryButton() {
        setContent(
            ContentPresentation.Error(UiErrorCategory.RecoveryRequired, canRetry = false),
        )

        composeRule.onNodeWithText(context.getString(R.string.shared_error_recovery_required))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertDoesNotExist()
    }

    @Test
    fun readyStateRendersContentLambda() {
        setContent(ContentPresentation.Ready("hello"))

        composeRule.onNodeWithText("ready:hello").assertIsDisplayed()
    }

    @Test
    fun retryTargetIsAtLeastFortyEightDp() {
        setContent(
            ContentPresentation.Error(UiErrorCategory.TemporarilyUnavailable, canRetry = true),
        )

        composeRule.onNodeWithTag(retryTag)
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun loadingEmptyAndErrorExposeHeadingAndPoliteStateSemantics() {
        val errorText = context.getString(R.string.shared_error_temporarily_unavailable)
        var presentation by mutableStateOf<ContentPresentation<String>>(ContentPresentation.Loading)

        setStatefulContent(presentation = { presentation })

        assertStateSemantics(
            loadingTag,
            loadingText,
            context.getString(R.string.shared_loading_state_description),
        )

        composeRule.runOnIdle {
            presentation = ContentPresentation.Empty(EmptyReason.NoApplicableItems)
        }
        assertStateSemantics(
            emptyTag,
            emptyText,
            context.getString(R.string.shared_empty_state_description),
        )

        composeRule.runOnIdle {
            presentation = ContentPresentation.Error(
                UiErrorCategory.TemporarilyUnavailable,
                canRetry = true,
            )
        }
        assertStateSemantics(
            errorTag,
            errorText,
            context.getString(R.string.shared_error_state_description),
        )
    }

    @Test
    fun readyExposesStateSemanticsAndCompleteContent() {
        var actionInvoked = false
        setContent(
            ContentPresentation.Ready("hello"),
            onReadyAction = { actionInvoked = true },
        )

        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        composeRule.onNodeWithTag(readyTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.shared_ready_state_description),
                ),
            )
        composeRule.onNodeWithTag(readyHeadingTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("ready:hello")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithTag(readyActionTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertTrue(actionInvoked) }
    }

    @Test
    fun allStatesRemainUsableInLightThemeAtTwoHundredPercentFont() {
        assertAllStatesAtTwoHundredPercent(darkTheme = false)
    }

    @Test
    fun allStatesRemainUsableInDarkThemeAtTwoHundredPercentFont() {
        assertAllStatesAtTwoHundredPercent(darkTheme = true)
    }

    @Test
    fun themeDerivesEveryNonErrorRoleFromTheApprovedPalette() {
        var lightScheme: ColorScheme? = null
        var darkScheme: ColorScheme? = null

        composeRule.setContent {
            Column {
                RecordTheme(darkTheme = false) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect { lightScheme = scheme }
                    Text("light theme", Modifier.testTag("LightThemeProbe"))
                }
                RecordTheme(darkTheme = true) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect { darkScheme = scheme }
                    Text("dark theme", Modifier.testTag("DarkThemeProbe"))
                }
            }
        }

        composeRule.onNodeWithTag("LightThemeProbe").assertIsDisplayed()
        composeRule.onNodeWithTag("DarkThemeProbe").assertIsDisplayed()
        assertSchemeUsesApprovedRoles(checkNotNull(lightScheme), darkTheme = false)
        assertSchemeUsesApprovedRoles(checkNotNull(darkScheme), darkTheme = true)
    }

    private fun setContent(
        presentation: ContentPresentation<String>,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        onRetry: () -> Unit = {},
        onReadyAction: () -> Unit = {},
    ) {
        composeRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
                RecordTheme(darkTheme = darkTheme) {
                    SharedContent(
                        presentation = presentation,
                        onRetry = onRetry,
                        readyContent = { content -> ReadyFixture(content, onReadyAction) },
                    )
                }
            }
        }
    }

    private fun setStatefulContent(
        presentation: () -> ContentPresentation<String>,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        onRetry: () -> Unit = {},
        onReadyAction: () -> Unit = {},
    ) {
        composeRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
                RecordTheme(darkTheme = darkTheme) {
                    SharedContent(
                        presentation = presentation(),
                        onRetry = onRetry,
                        readyContent = { content -> ReadyFixture(content, onReadyAction) },
                    )
                }
            }
        }
    }

    private fun assertStateSemantics(tag: String, label: String, description: String) {
        composeRule.onNodeWithText(label).assertIsDisplayed()
        val headingConfig = composeRule.onNodeWithText(label).fetchSemanticsNode().config
        assertTrue(headingConfig.contains(SemanticsProperties.Heading))
        composeRule.onNodeWithTag(tag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    private fun assertAllStatesAtTwoHundredPercent(darkTheme: Boolean) {
        var presentation by mutableStateOf<ContentPresentation<String>>(ContentPresentation.Loading)
        var retried = false
        setStatefulContent(
            presentation = { presentation },
            darkTheme = darkTheme,
            fontScale = 2f,
            onRetry = { retried = true },
        )

        assertFullyWithinRoot(
            loadingTag,
            composeRule.onNodeWithText(loadingText, useUnmergedTree = true).assertIsDisplayed(),
        )
        composeRule.runOnIdle {
            presentation = ContentPresentation.Empty(EmptyReason.NoApplicableItems)
        }
        assertFullyWithinRoot(
            emptyTag,
            composeRule.onNodeWithText(emptyText, useUnmergedTree = true).assertIsDisplayed(),
        )
        composeRule.runOnIdle {
            presentation = ContentPresentation.Error(
                UiErrorCategory.TemporarilyUnavailable,
                canRetry = true,
            )
        }
        val errorNode = composeRule
            .onNodeWithText(
                context.getString(R.string.shared_error_temporarily_unavailable),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        val retryNode = composeRule.onNodeWithTag(retryTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
        assertFullyWithinRoot(errorTag, errorNode, retryNode)
        val errorBounds = errorNode.getUnclippedBoundsInRoot()
        val retryBounds = retryNode.getUnclippedBoundsInRoot()
        assertTrue(
            "error and Retry overlap: error=$errorBounds retry=$retryBounds",
            errorBounds.bottom <= retryBounds.top || retryBounds.bottom <= errorBounds.top,
        )
        retryNode.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
            .performClick()
        composeRule.runOnIdle {
            assertTrue(retried)
            presentation = ContentPresentation.Ready("two-hundred-percent")
        }
        assertFullyWithinRoot(
            readyTag,
            composeRule.onNodeWithTag(readyHeadingTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertTextEquals("ready:two-hundred-percent"),
            composeRule.onNodeWithTag(readyActionTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertHasClickAction(),
        )
    }

    private fun assertSchemeUsesApprovedRoles(scheme: ColorScheme, darkTheme: Boolean) {
        val approved = setOf(
            RecordPalette.appBackgroundLight,
            RecordPalette.primarySurfaceLight,
            RecordPalette.primaryAccentLight,
            RecordPalette.warmSecondaryLight,
            RecordPalette.primaryContentLight,
            RecordPalette.onPrimaryContentLight,
            RecordPalette.appBackgroundDark,
            RecordPalette.primarySurfaceDark,
            RecordPalette.primaryAccentDark,
            RecordPalette.warmSecondaryDark,
            RecordPalette.primaryContentDark,
            RecordPalette.onPrimaryContentDark,
        )
        val nonErrorRoles = listOf(
            scheme.primary,
            scheme.onPrimary,
            scheme.primaryContainer,
            scheme.onPrimaryContainer,
            scheme.inversePrimary,
            scheme.secondary,
            scheme.onSecondary,
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
            scheme.tertiary,
            scheme.onTertiary,
            scheme.tertiaryContainer,
            scheme.onTertiaryContainer,
            scheme.background,
            scheme.onBackground,
            scheme.surface,
            scheme.onSurface,
            scheme.surfaceVariant,
            scheme.onSurfaceVariant,
            scheme.surfaceTint,
            scheme.inverseSurface,
            scheme.inverseOnSurface,
            scheme.outline,
            scheme.outlineVariant,
            scheme.scrim,
            scheme.surfaceBright,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainerLowest,
            scheme.surfaceDim,
            scheme.primaryFixed,
            scheme.primaryFixedDim,
            scheme.onPrimaryFixed,
            scheme.onPrimaryFixedVariant,
            scheme.secondaryFixed,
            scheme.secondaryFixedDim,
            scheme.onSecondaryFixed,
            scheme.onSecondaryFixedVariant,
            scheme.tertiaryFixed,
            scheme.tertiaryFixedDim,
            scheme.onTertiaryFixed,
            scheme.onTertiaryFixedVariant,
        )
        assertTrue(
            "unexpected non-error roles: ${nonErrorRoles.filterNot(approved::contains)}",
            nonErrorRoles.all(approved::contains),
        )

        val expectedBackground: Color
        val expectedSurface: Color
        val expectedPrimary: Color
        val expectedOnPrimary: Color
        val expectedSecondary: Color
        val expectedContent: Color
        if (darkTheme) {
            expectedBackground = RecordPalette.appBackgroundDark
            expectedSurface = RecordPalette.primarySurfaceDark
            expectedPrimary = RecordPalette.primaryAccentDark
            expectedOnPrimary = RecordPalette.onPrimaryContentDark
            expectedSecondary = RecordPalette.warmSecondaryDark
            expectedContent = RecordPalette.primaryContentDark
        } else {
            expectedBackground = RecordPalette.appBackgroundLight
            expectedSurface = RecordPalette.primarySurfaceLight
            expectedPrimary = RecordPalette.primaryAccentLight
            expectedOnPrimary = RecordPalette.onPrimaryContentLight
            expectedSecondary = RecordPalette.warmSecondaryLight
            expectedContent = RecordPalette.primaryContentLight
        }
        assertEquals(expectedBackground, scheme.background)
        assertEquals(expectedSurface, scheme.surface)
        assertEquals(expectedPrimary, scheme.primary)
        assertEquals(expectedOnPrimary, scheme.onPrimary)
        assertEquals(expectedSecondary, scheme.secondary)
        assertEquals(expectedContent, scheme.onBackground)
        assertEquals(expectedContent, scheme.onSurface)
        assertSemanticErrorRoles(scheme, darkTheme)
    }

    @Composable
    private fun ReadyFixture(content: String, onAction: () -> Unit) {
        Column {
            Text(
                text = "ready:$content",
                modifier = Modifier
                    .testTag(readyHeadingTag)
                    .semantics { heading() },
            )
            Button(
                onClick = onAction,
                modifier = Modifier.testTag(readyActionTag),
            ) {
                Text(readyActionText)
            }
        }
    }

    private fun assertFullyWithinRoot(
        stateTag: String,
        vararg requiredNodes: SemanticsNodeInteraction,
    ) {
        val rootBounds = composeRule.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot()
        val stateBounds = composeRule
            .onNodeWithTag(stateTag, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertBoundsWithin(stateBounds, rootBounds)
        requiredNodes.forEach { node ->
            val bounds = node.getUnclippedBoundsInRoot()
            assertBoundsWithin(bounds, rootBounds)
        }
    }

    private fun assertBoundsWithin(bounds: DpRect, container: DpRect) {
        assertTrue(
            "required node has empty bounds: $bounds",
            bounds.right > bounds.left && bounds.bottom > bounds.top,
        )
        assertTrue(
            "required node is outside root bounds: node=$bounds root=$container",
            bounds.left >= container.left &&
                bounds.top >= container.top &&
                bounds.right <= container.right &&
                bounds.bottom <= container.bottom,
        )
    }

    private fun assertSemanticErrorRoles(scheme: ColorScheme, darkTheme: Boolean) {
        val defaults = if (darkTheme) darkColorScheme() else lightColorScheme()
        assertEquals(defaults.error, scheme.error)
        assertEquals(defaults.onError, scheme.onError)
        assertEquals(defaults.errorContainer, scheme.errorContainer)
        assertEquals(defaults.onErrorContainer, scheme.onErrorContainer)

        val warmSecondaries = setOf(
            RecordPalette.warmSecondaryLight,
            RecordPalette.warmSecondaryDark,
        )
        val errorRoles = listOf(
            scheme.error,
            scheme.onError,
            scheme.errorContainer,
            scheme.onErrorContainer,
        )
        assertTrue(
            "error roles alias warm secondary: ${errorRoles.filter(warmSecondaries::contains)}",
            errorRoles.none(warmSecondaries::contains),
        )
        assertTrue(contrastRatio(scheme.onError, scheme.error) >= 4.5)
        assertTrue(contrastRatio(scheme.onErrorContainer, scheme.errorContainer) >= 4.5)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val component = value.toDouble()
            return if (component <= 0.04045) {
                component / 12.92
            } else {
                ((component + 0.055) / 1.055).pow(2.4)
            }
        }

        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
