package com.paherdin.record.core.data

import com.paherdin.record.core.data.room.Attachment
import com.paherdin.record.core.data.room.RecordDao
import kotlin.coroutines.cancellation.CancellationException
import java.io.File
import java.util.UUID

/**
 * The ingestion pipeline (plan Task 5, spec 4.1/4.2): copy → platform probe +
 * pure validation → SHA-256 → shared per-hash serialization → deduplication
 * decision → atomic move → zero-link metadata write with
 * `deletedAt = ingestedAt`. Never trusts a caller-supplied hash; publishes
 * only through the atomic move; a duplicate import verifies and reuses the
 * canonical identity (ADR 0008: refresh the grace-period start on reuse).
 * Corruption quarantines only an unlinked new input and never overwrites.
 */
internal sealed interface IngestOutcome

internal data class IngestSuccess(val contentIdentity: String) : IngestOutcome

internal data class IngestRejected(val violation: AttachmentViolation) : IngestOutcome

internal data class IngestCorrupted(val detail: CorruptionDetail) : IngestOutcome

internal data class IngestIoFailed(val stage: AttachmentStage) : IngestOutcome

internal class AttachmentIngestor(
    private val store: AttachmentContentStore,
    private val io: AttachmentIo,
    private val locks: AttachmentHashLockRegistry,
    private val dao: RecordDao,
    private val clock: () -> Long,
    private val hook: AttachmentFaultHook = AttachmentFaultHook.NO_OP,
) {
    suspend fun ingest(source: File, declaredMimeType: String): IngestOutcome {
        // 1. Copy to app-private TEMP on the same filesystem as the content path.
        val temp = store.newTempFile()
        val copy = try {
            io.copyToTemp(source, temp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Partial TEMP residue: classified for safe cleanup by the cleaner.
            return IngestIoFailed(AttachmentStage.TEMP_COPY)
        }

        // 2. Validate (pure classifier over the Android probe result).
        val probe = AttachmentImageProbe.probe(temp)
        val classification = AttachmentValidator.classify(declaredMimeType, copy.totalBytes, probe)
        if (classification is Rejected) {
            temp.delete()
            return IngestRejected(classification.violation)
        }

        // 3. SHA-256 (streaming; never a caller-supplied hash).
        val sha = try {
            temp.inputStream().use { AttachmentValidator.sha256(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return IngestIoFailed(AttachmentStage.VALIDATION_HASH)
        }

        // 4. Per-hash serialized deduplication, atomic publish, metadata write.
        return locks.withLock(sha) {
            deduplicateAndPublish(temp, sha, copy.totalBytes)
        }
    }

    /** Row write with its fault boundary inside the try: a hook fault or IO
     * failure maps to `METADATA_WRITE`, never escaping as an unclassified
     * exception. The moved blob stays a safe orphan candidate. */
    private suspend fun metadataWrite(sha: String, hookCall: () -> Unit): IngestOutcome {
        return try {
            hookCall()
            dao.insertAttachment(Attachment(UUID.randomUUID().toString(), sha, clock()))
            IngestSuccess(sha)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            IngestIoFailed(AttachmentStage.METADATA_WRITE)
        }
    }

    private suspend fun deduplicateAndPublish(temp: File, sha: String, sizeBytes: Long): IngestOutcome {
        val existing = dao.getAttachmentByContentIdentity(sha)
        val target = store.contentFile(sha)
        val targetState = when {
            !target.exists() -> AttachmentDedupDecider.TargetState.ABSENT
            else -> {
                val actualHash = try {
                    target.inputStream().use { AttachmentValidator.sha256(it) }
                } catch (e: Throwable) {
                    null
                }
                when {
                    actualHash == null -> AttachmentDedupDecider.TargetState.MISMATCHED
                    actualHash == sha && target.length() == sizeBytes -> AttachmentDedupDecider.TargetState.VERIFIED
                    else -> AttachmentDedupDecider.TargetState.MISMATCHED
                }
            }
        }
        val rowState = existing?.let { if (it.deletedAt == null) AttachmentDedupDecider.RowState.ACTIVE else AttachmentDedupDecider.RowState.ZERO_LINK_PENDING }
        val decision = AttachmentDedupDecider.decide(rowState, targetState)

        return when (decision) {
            DedupDecision.MOVE_AND_CREATE -> {
                val published = store.atomicPublish(temp, sha)
                    ?: return IngestIoFailed(AttachmentStage.ATOMIC_MOVE)
                metadataWrite(sha) { hook.beforeMetadataWrite() }
            }
            DedupDecision.ADOPT_VERIFIED_BLOB -> {
                // Verified orphan blob (no canonical row): adopt it and create
                // the zero-link row; the incoming temp is a redundant copy.
                temp.delete()
                metadataWrite(sha) { hook.beforeMetadataWrite() }
            }
            DedupDecision.REUSE_ACTIVE -> {
                // Active/linked canonical content: verified bytes, reuse the ID.
                temp.delete()
                IngestSuccess(sha)
            }
            DedupDecision.REUSE_REFRESH -> {
                // Zero-link pending content with verified bytes: reuse and
                // refresh the grace-period start so the reported preparation
                // always has a full grace period (ADR 0008).
                temp.delete()
                try {
                    dao.updateAttachmentDeletedAt(existing!!.id, clock())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    return IngestIoFailed(AttachmentStage.METADATA_WRITE)
                }
                IngestSuccess(sha)
            }
            DedupDecision.RESTORE_REFRESH -> {
                // Expected cleanup window (zero-link pending row, blob absent):
                // restore the validated blob at the canonical target, then refresh.
                val published = store.atomicPublish(temp, sha)
                    ?: return IngestIoFailed(AttachmentStage.ATOMIC_MOVE)
                try {
                    dao.updateAttachmentDeletedAt(existing!!.id, clock())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    return IngestIoFailed(AttachmentStage.METADATA_WRITE)
                }
                IngestSuccess(sha)
            }
            DedupDecision.CORRUPT_SPLIT -> {
                store.quarantine(temp)
                IngestCorrupted(CorruptionDetail.METADATA_BLOB_SPLIT)
            }
            DedupDecision.CORRUPT_MISMATCH -> {
                store.quarantine(temp)
                IngestCorrupted(CorruptionDetail.HASH_MISMATCH)
            }
            DedupDecision.CORRUPT_CONFLICT -> {
                store.quarantine(temp)
                IngestCorrupted(CorruptionDetail.CONFLICTING_METADATA)
            }
        }
    }
}
