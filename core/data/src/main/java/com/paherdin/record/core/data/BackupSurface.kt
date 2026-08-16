package com.paherdin.record.core.data

import java.io.File

/** The published artifact identity returned on export success (spec 4.8). */
data class BackupArtifact(
    val backupId: String,
    val path: String,
    val sha256: String,
)

/**
 * The validated candidate summary returned when staging succeeds (spec 4.5,
 * plan Task 7). `candidateId` is the process-local staging generation; the
 * `validationDigest` is SHA-256 of the strict canonical receipt that binds
 * source package, manifest, entries, counts, and candidate digest — the exact
 * handle `confirmRestore` later re-verifies.
 */
data class StagedRestore(
    val candidateId: String,
    val validationDigest: String,
    val recordCounts: Map<String, Int>,
    val attachmentCount: Int,
)

/** Private atomic publication or an injected provider target (M6 wires the SAF DocumentFile). */
sealed interface BackupTarget {
    data class PrivateFile(val file: File) : BackupTarget
    data class Provider(val provider: BackupProviderTarget) : BackupTarget
}

/**
 * The SAF copy protocol steps (spec 4.1, plan Task 4/5); a partial object stays
 * incomplete-marked until fully copied and reverified. The M6 UI supplies the
 * real `DocumentFile` implementation.
 */
interface BackupProviderTarget {
    /** false = target already exists; nothing written. */
    suspend fun createIncomplete(): Boolean

    suspend fun writeChunk(bytes: ByteArray)

    suspend fun flushAndClose()

    /** Destination verification after close. */
    suspend fun verify(): Boolean

    /** true when a provider rename to the final name is supported and done. */
    suspend fun renameToFinalIfSupported(): Boolean

    suspend fun clearIncompleteMarker()
}
