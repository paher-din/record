package com.paherdin.record

import android.app.Application
import android.content.Context
import com.paherdin.record.core.data.RecordStartup
import com.paherdin.record.startup.StartupStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import java.time.ZoneId

class RecordApplication : Application() {

    internal val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * The application composition root (plan M1-T07 Step 3): one process-wide
 * [RecordStartup] coordinator, an injected I/O dispatcher, the UTC clock and
 * system zone used by time-dependent surfaces, and the application coroutine
 * scope the [StartupStateStore] owns its single attempt in.
 */
internal class AppContainer(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val clock: Clock = Clock.systemUTC()
    val zoneId: ZoneId = ZoneId.systemDefault()
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    val startupStore = StartupStateStore(RecordStartup.create(context), applicationScope)
}