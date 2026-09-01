package com.paherdin.record.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.paherdin.record.R
import com.paherdin.record.core.ui.theme.RecordTokens

private object SettingsTags {
    const val HEADING = "SettingsHeading"
    const val BACK = "SettingsBack"
}

/**
 * The M1 Settings surface (spec 7): application information plus the truthful
 * later-settings availability statement. No future settings subroute, no user
 * data, and no settings subfeature behavior.
 */
@Composable
internal fun SettingsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(RecordTokens.spaceMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_heading),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .testTag(SettingsTags.HEADING)
                .semantics { heading() },
        )
        Spacer(Modifier.height(RecordTokens.spaceMedium))
        Text(
            text = stringResource(R.string.settings_app_row),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(RecordTokens.spaceSmall))
        Text(
            text = stringResource(R.string.settings_availability),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(RecordTokens.spaceLarge))
        Button(
            onClick = onBack,
            modifier = Modifier
                .testTag(SettingsTags.BACK)
                .defaultMinSize(
                    minWidth = RecordTokens.minimumTouchTarget,
                    minHeight = RecordTokens.minimumTouchTarget,
                ),
        ) {
            Text(stringResource(R.string.settings_back))
        }
    }
}