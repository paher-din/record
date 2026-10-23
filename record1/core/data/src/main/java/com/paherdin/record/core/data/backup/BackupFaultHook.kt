package com.paherdin.record.core.data.backup

/**
 * Named fault boundaries of the export pipeline (spec 4.6 `BACKUP_STREAM_AND_PUBLISH`).
 * No-op in production.
 */
internal interface BackupFaultHook {
    fun beforeEntryWrite(logicalType: String) = Unit
    fun midEntryWrite(logicalType: String) = Unit
    fun afterEntryWrite(logicalType: String) = Unit
    fun beforeManifestWrite() = Unit
    fun midManifestWrite() = Unit
    fun afterManifestWriteBeforeClose() = Unit
    fun beforePrivateRename() = Unit
    fun afterPrivateRename() = Unit

    companion object {
        val NO_OP: BackupFaultHook = object : BackupFaultHook {}
    }
}
