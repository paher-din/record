package com.paherdin.record.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record.core.ui.theme.RecordPalette
import com.paherdin.record.core.ui.theme.RecordTheme
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
        setContent(ContentPresentation.Ready("hello"))

        composeRule.onNodeWithText("ready:hello").assertIsDisplayed()
        composeRule.onNodeWithTag(readyTag)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.shared_ready_state_description),
                ),
            )
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
    ) {
        composeRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
                RecordTheme(darkTheme = darkTheme) {
                    SharedContent(
                        presentation = presentation,
                        onRetry = onRetry,
                        readyContent = { content -> Text("ready:$content") },
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
    ) {
        composeRule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
                RecordTheme(darkTheme = darkTheme) {
                    SharedContent(
                        presentation = presentation(),
                        onRetry = onRetry,
                        readyContent = { content -> Text("ready:$content") },
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

        composeRule.onNodeWithText(loadingText).assertIsDisplayed()
        composeRule.runOnIdle {
            presentation = ContentPresentation.Empty(EmptyReason.NoApplicableItems)
        }
        composeRule.onNodeWithText(emptyText).assertIsDisplayed()
        composeRule.runOnIdle {
            presentation = ContentPresentation.Error(
                UiErrorCategory.TemporarilyUnavailable,
                canRetry = true,
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.shared_error_temporarily_unavailable))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(retryTag).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertTrue(retried)
            presentation = ContentPresentation.Ready("two-hundred-percent")
        }
        composeRule.onNodeWithText("ready:two-hundred-percent").assertIsDisplayed()
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
    }
}
