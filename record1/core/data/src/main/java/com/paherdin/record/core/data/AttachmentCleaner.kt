package com.paherdin.record.core.data

import com.paherdin.record.core.data.room.RecordDao
import java.io.File

/**
 * The attachment cleaner (plan Task 7, spec 4.4/4.5 and criterion 5/8):
 * zero-link canonical rows whose grace period has elapsed are removed —
 * under the shared per-hash serialization, recheck zero references, retry
 * physical deletion idempotently, confirm physical absence, then delete the
 * metadata row in a coordinated sequence (row and blob are never removed
 * separately, so cleanup cannot manufacture a `METADATA_BLOB_SPLIT`). A
 * zero-link pending row whose blob is already absent is the expected cleanup
 * crash window: the cleaner completes the metadata deletion only. File-only
 * residue (TEMP partials, moved-but-uncommitted blobs) is cleaned after
 * verification and the grace period; a missing/invalid timestamp fails
 * closed and retains the residue. The grace period uses an injectable clock.
 */
internal class AttachmentCleaner(
    private val store: AttachmentContentStore,
    private val locks: AttachmentHashLockRegistry,
    private val dao: RecordDao,
    private val clock: () -> Long,
    private val gracePeriodMillis: Long = GRACE_PERIOD_MILLIS,
    private val hook: AttachmentFaultHook = AttachmentFaultHook.NO_OP,
) {
    internal data class Report(
        val rowsCleaned: Int,
        val expectedWindowsCompleted: Int,
        val orphanBlobsCleaned: Int,
        val tempResidueCleaned: Int,
        val failedClosed: Int,
    )

    suspend fun run(): Report {
        var rowsCleaned = 0
        var expectedWindows = 0
        var orphans = 0
        var temps = 0
        var failClosed = 0

        // 1. Zero-link canonical rows past the grace period.
        val now = clock()
        val pending = dao.allAttachments().filter { it.deletedAt != null && now - it.deletedAt!! >= gracePeriodMillis }
        for (row in pending) {
            locks.withLock(row.contentIdentity) {
                // Recheck: a concurrent relink may have cleared the marker.
                val current = dao.getAttachment(row.id) ?: return@withLock
                if (current.deletedAt == null) return@withLock
                if (dao.countAttachmentLinks(row.id) != 0) return@withLock
                val blob = store.contentFile(current.contentIdentity)
                when {
                    blob.isFile -> {
                        // A hook-originated fault is a simulated physical-delete
                        // failure: retain the row for retry, never propagate.
                        if (!safeDelete(blob)) return@withLock
                    }
                    else -> expectedWindows++ // expected crash window after physical deletion
                }
                // Metadata deletion is a simulated-fault boundary too: a fault
                // here retains the row (the blob is already gone, so the next
                // run takes the expected-window path and completes it).
                if (!safeMetadataDelete(row.id)) return@withLock
                rowsCleaned++
            }
        }

        // 2. File-only orphan blobs (no canonical row) past the grace period.
        val knownIdentities = dao.allAttachments().map { it.contentIdentity }.toSet()
        for (blob in store.scanContentBlobs()) {
            if (blob.name in knownIdentities) continue
            val age = graceAgeMillis(blob, now)
            if (age == null) {
                failClosed++ // missing/invalid timestamp: retain for explicit recovery
            } else if (age >= gracePeriodMillis) {
                if (store.deleteIfExists(blob)) orphans++
            }
        }

        // 3. TEMP staging residue past the grace period.
        for (temp in store.scanTempResidue()) {
            val age = graceAgeMillis(temp, now)
            if (age == null) {
                failClosed++
            } else if (age >= gracePeriodMillis) {
                if (store.deleteIfExists(temp)) temps++
            }
        }

        return Report(rowsCleaned, expectedWindows, orphans, temps, failClosed)
    }

    private fun graceAgeMillis(file: File, now: Long): Long? {
        val lastModified = file.lastModified()
        if (lastModified <= 0L) return null
        return now - lastModified
    }

    /** Physical deletion with simulated-fault boundaries; false = retained for retry. */
    private fun safeDelete(blob: File): Boolean {
        return try {
            hook.beforePhysicalDelete()
            if (!store.deleteIfExists(blob)) return false
            hook.afterPhysicalDelete()
            true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    /** Metadata deletion with a simulated-fault boundary; false = retained. */
    private suspend fun safeMetadataDelete(rowId: String): Boolean {
        return try {
            hook.beforeMetadataDelete()
            dao.deleteAttachment(rowId)
            true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    companion object {
        /** Recorded PoC grace period (7 days); the product value is M3's. */
        const val GRACE_PERIOD_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
