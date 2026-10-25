package com.paherdin.record2

import android.app.Application
import android.util.Log
import com.paherdin.record2.backup.BackupStaging

/**
 * Process composition root currently owns only the startup sweep of private backup/restore task
 * directories. F1 must use [BackupStaging.appDirectory] for the production exporter/restore
 * staging root so a killed process cannot leave unbounded `preflight-*`/`restore-*`/`export-*`
 * directories behind. The sweep itself never touches user data, schemas or gates.
 */
class RecordApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val root = BackupStaging.appDirectory(this)
        Thread({
            runCatching { BackupStaging.sweep(root) }
                .onFailure { Log.w("RecordStaging", "orphan staging sweep failed", it) }
        }, "record2-staging-sweep").apply { isDaemon = true }.start()
    }
}
