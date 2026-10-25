package com.paherdin.record2.backup

/** Observable restore phases owned by the application-level coordinator. */
internal enum class RestoreProgress {
    WAITING_FOR_GATE,
    PREPARING,
    IMPORTING,
    COMMITTED,
    POST_COMMIT,
}

internal sealed interface RestoreReceiptLookup {
    data object None : RestoreReceiptLookup
    data class Committed(val receipt: RestoreReceipt) : RestoreReceiptLookup
    data class Unknown(val reason: String) : RestoreReceiptLookup
}

/** Transform counts are unavailable when a result is rebuilt only from a persisted receipt. */
internal sealed interface RestoreTransformStats {
    data class Known(val transformedRuns: Int, val transformedProposals: Int) : RestoreTransformStats
    data object Unknown : RestoreTransformStats
}

/** Engine port used by the lifecycle coordinator; the production implementation is RestoreService. */
internal interface RestoreEngine {
    suspend fun preflight(source: BackupSource): RestoreCandidate

    suspend fun receiptLookup(candidate: RestoreCandidate): RestoreReceiptLookup

    suspend fun restore(
        candidate: RestoreCandidate,
        progress: (RestoreProgress) -> Unit = {},
    ): RestoreResult

    suspend fun completedResult(receipt: RestoreReceipt): RestoreResult
}
