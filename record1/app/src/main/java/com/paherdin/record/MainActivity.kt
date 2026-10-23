package com.paherdin.record

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paherdin.record.core.ui.theme.RecordTheme
import com.paherdin.record.startup.AppStartState
import com.paherdin.record.ui.RecordApp
import com.paherdin.record.ui.RestoreRecoverySurface

/**
 * The M1 activity (plan M1-T07 Step 6). The startup store state decides what
 * may exist: no shell, route, or user-data frame is constructed before the
 * single safe Ready emission; every gate state renders the data-free
 * recovery/blocked surface instead. No custom splash Activity or timer.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RecordApplication).container
        setContent {
            RecordTheme {
                val appStartState by container.startupStore.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { container.startupStore.start() }
                when (val current = appStartState) {
                    is AppStartState.Ready -> {
                        // Drawn is reported only once the safe shell is interactive.
                        ReportDrawnWhen { true }
                        RecordApp(
                            clock = container.clock,
                            zoneId = container.zoneId,
                        )
                    }

                    is AppStartState.Gate -> RestoreRecoverySurface(
                        phase = current.phase,
                        canRetry = current.canRetry,
                        onRetry = { container.startupStore.retry() },
                    )
                }
            }
        }
    }
}