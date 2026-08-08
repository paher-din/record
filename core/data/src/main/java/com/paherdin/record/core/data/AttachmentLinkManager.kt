package com.paherdin.record.core.data

import com.paherdin.record.core.data.room.AttachmentLink
import com.paherdin.record.core.data.room.RecordDao

/** Owner-save/link rejection with its precise violation; nothing committed. */
internal class AttachmentRejectedException(val violation: AttachmentViolation) : IllegalStateException()

/** Link-time corruption (metadata/blob split or mismatch); nothing committed. */
internal class AttachmentCorruptedException(val detail: CorruptionDetail) : IllegalStateException()

/**
 * Owner link management (plan Task 6, spec 4.4/4.7): linking re-enters the
 * zero-link cleanup-pending cancellation path — under the shared per-hash
 * `Mutex`, recheck target existence, verify the actual bytes, then clear
 * `deletedAt` and insert the link in one Room transaction. Unlinking removes
 * only the ownership relation; at zero global link count the canonical row
 * enters cleanup pending (`deletedAt = clock.now()`) in the same transaction.
 * Multiple requested identities are processed in ascending SHA-256 order
 * (deadlock-free). The caller runs these inside its own transaction and maps
 * the typed exceptions at the boundary.
 */
internal class AttachmentLinkManager(
    private val store: AttachmentContentStore,
    private val locks: AttachmentHashLockRegistry,
    private val dao: RecordDao,
    private val clock: () -> Long,
    private val hook: AttachmentFaultHook = AttachmentFaultHook.NO_OP,
) {

    suspend fun linkAll(contentIdentities: Set<String>, ownerEntityRefId: String) {
        // Ascending order prevents lock-ordering deadlocks across identities.
        for (sha in contentIdentities.sorted()) {
            locks.withLock(sha) { linkOne(sha, ownerEntityRefId) }
        }
    }

    suspend fun linkOne(contentIdentity: String, ownerEntityRefId: String) {
        val row = dao.getAttachmentByContentIdentity(contentIdentity)
            ?: throw AttachmentRejectedException(AttachmentViolation.CONTENT_IDENTITY_UNKNOWN)
        if (dao.getAttachmentLink(ownerEntityRefId, row.id) != null) return // idempotent: already linked
        verifyBytesOrThrow(row.id, contentIdentity, row.deletedAt != null)
        runLinkHook { hook.beforeLinkCommit() }
        dao.insertAttachmentLink(AttachmentLink(row.id, ownerEntityRefId))
        if (row.deletedAt != null) {
            // Clear the zero-link cleanup marker in the same transaction.
            dao.updateAttachmentDeletedAt(row.id, null)
        }
    }

    suspend fun unlinkOne(contentIdentity: String, ownerEntityRefId: String) {
        val row = dao.getAttachmentByContentIdentity(contentIdentity) ?: return // idempotent: nothing to unlink
        val link = dao.getAttachmentLink(ownerEntityRefId, row.id) ?: return // idempotent: no such link
        locks.withLock(contentIdentity) {
            if (dao.getAttachmentLink(ownerEntityRefId, row.id) == null) return@withLock
            runLinkHook { hook.beforeUnlinkCommit() }
            dao.deleteAttachmentLink(ownerEntityRefId, row.id)
            if (dao.countAttachmentLinks(row.id) == 0) {
                dao.updateAttachmentDeletedAt(row.id, clock())
            }
        }
    }

    /** Hook-originated exceptions are simulated faults (TransactionFailed at the boundary). */
    private fun runLinkHook(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw HookFaultException(fromCommitResponse = false)
        }
    }

    /**
     * Verifies the committed blob (SHA-256 of the actual bytes) before any
     * link can commit; `deletedAt != null` (zero-link pending) with missing
     * bytes is the expected cleanup window → `CONTENT_NOT_AVAILABLE`, while a
     * linked/active row without bytes is `METADATA_BLOB_SPLIT` corruption.
     * A hash match over the full byte stream implies size consistency.
     */
    private suspend fun verifyBytesOrThrow(attachmentId: String, contentIdentity: String, rowPending: Boolean) {
        val blob = store.contentFile(contentIdentity)
        val actual = try {
            if (!blob.isFile) null else blob.inputStream().use { AttachmentValidator.sha256(it) }
        } catch (e: Throwable) {
            null
        }
        when {
            actual == null && rowPending -> throw AttachmentRejectedException(AttachmentViolation.CONTENT_NOT_AVAILABLE)
            actual == null -> throw AttachmentCorruptedException(CorruptionDetail.METADATA_BLOB_SPLIT)
            actual != contentIdentity -> throw AttachmentCorruptedException(CorruptionDetail.HASH_MISMATCH)
        }
    }
}
