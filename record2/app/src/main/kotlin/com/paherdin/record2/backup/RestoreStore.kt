package com.paherdin.record2.backup

/** Snapshot of the target state that the restore engine may read without touching DAOs. */
internal data class RestoreStoredState(
    val dataGeneration: String,
    val processOwnerId: String,
    val authEpoch: Long,
    val restoreReceipt: String?,
    val businessRevision: Long,
)

/** Post-commit state needed to rebuild a result when the caller's observation was lost. */
internal data class RestoreCompletionState(
    val processOwnerId: String,
    val authEpoch: Long,
)

/** Result of the import pass: the verified container plus transform counts from the same rows. */
internal data class StreamedImport(
    val backup: StreamedLogicalBackup,
    val stats: RestoreTransformStats,
)

/**
 * Persistence port for restore. The backup package owns format, validation and lifecycle;
 * the storage implementation owns the single Room transaction, entity mapping and receipt row.
 * This keeps the architecture rule that backup never imports Room/storage types.
 */
internal interface RestoreStore {
    suspend fun currentState(): RestoreStoredState?

    suspend fun completionState(): RestoreCompletionState?

    suspend fun import(
        expectedManifest: BackupManifest,
        expectedZipSha256: String,
        expectedZipBytes: Long,
        restoredGeneration: String,
        owner: String,
        newEpoch: Long,
        receipt: String,
        expectedGeneration: String?,
        expectedEpoch: Long?,
        streamRows: ((LogicalSegment, SegmentRow) -> Unit) -> StreamedImport,
        checkpoint: (RestoreCheckpoint) -> Unit,
    ): RestoreTransformStats
}
