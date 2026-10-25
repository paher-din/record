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

/**
 * Persistence port for restore. The backup package owns format, validation and lifecycle;
 * the storage implementation owns the single Room transaction, entity mapping and receipt row.
 * This keeps the architecture rule that backup never imports Room/storage types.
 */
internal interface RestoreStore {
    suspend fun currentState(): RestoreStoredState?

    suspend fun completionState(): RestoreCompletionState?

    suspend fun import(
        backup: LogicalBackup,
        runs: List<RunRow>,
        proposals: List<ProposalRow>,
        restoredGeneration: String,
        owner: String,
        newEpoch: Long,
        receipt: String,
        expectedGeneration: String?,
        expectedEpoch: Long?,
        checkpoint: (RestoreCheckpoint) -> Unit,
    )
}
