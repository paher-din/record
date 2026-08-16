package com.paherdin.record.core.data.restore

/**
 * Named fault boundaries of the legacy generation bootstrap (spec 4.6
 * `GENERATION_BOOTSTRAP`, ADR 0010). No-op in production.
 */
internal interface BootstrapFaultHook {
    fun beforeLegacyRecovery() = Unit
    fun afterLegacyRecovery() = Unit
    fun beforeLegacyCheckpoint() = Unit
    fun afterLegacyCheckpoint() = Unit
    fun beforeDatabaseCopy() = Unit
    fun afterDatabaseCopy() = Unit
    fun beforeAttachmentCopy() = Unit
    fun afterAttachmentCopy() = Unit
    fun beforeCopyValidation() = Unit
    fun afterCopyValidation() = Unit
    fun beforeRecordWrite() = Unit
    fun afterRecordWrite() = Unit

    companion object {
        val NO_OP: BootstrapFaultHook = object : BootstrapFaultHook {}
    }
}
