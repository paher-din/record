package com.paherdin.record2.storage

import androidx.room.withTransaction
import com.paherdin.record2.backup.AccountRow
import com.paherdin.record2.backup.BackupError
import com.paherdin.record2.backup.BackupManifest
import com.paherdin.record2.backup.CategoryRow
import com.paherdin.record2.backup.DraftRow
import com.paherdin.record2.backup.LinkRow
import com.paherdin.record2.backup.LogicalSegment
import com.paherdin.record2.backup.MessageRow
import com.paherdin.record2.backup.NoteRow
import com.paherdin.record2.backup.OperationRow
import com.paherdin.record2.backup.ProposalRow
import com.paherdin.record2.backup.ReferenceRow
import com.paherdin.record2.backup.RestoreCheckpoint
import com.paherdin.record2.backup.RestoreCompletionState
import com.paherdin.record2.backup.RestoreReceiptCodec
import com.paherdin.record2.backup.RestoreStore
import com.paherdin.record2.backup.RestoreStoredState
import com.paherdin.record2.backup.RestoreTransformStats
import com.paherdin.record2.backup.RunRow
import com.paherdin.record2.backup.SegmentRow
import com.paherdin.record2.backup.SessionRow
import com.paherdin.record2.backup.StreamedImport
import com.paherdin.record2.backup.TransactionRow
import com.paherdin.record2.backup.fail
import java.time.Clock

/**
 * Storage-owned implementation of the restore persistence port. The entire replacement remains
 * one Room transaction; the backup package only supplies the validated logical payload, receipt
 * text and checkpoint callback.
 */
internal class RoomRestoreStore(
    private val database: RecordDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : RestoreStore {
    override suspend fun currentState(): RestoreStoredState? {
        val state = database.records().dataState() ?: return null
        return RestoreStoredState(
            dataGeneration = state.dataGeneration,
            processOwnerId = state.processOwnerId,
            authEpoch = database.aiControl().control()?.authEpoch ?: 0,
            restoreReceipt = state.restoreReceipt,
            businessRevision = state.businessRevision,
        )
    }

    override suspend fun completionState(): RestoreCompletionState? {
        val state = database.records().dataState() ?: return null
        return RestoreCompletionState(
            processOwnerId = state.processOwnerId,
            authEpoch = database.aiControl().control()?.authEpoch ?: 0,
        )
    }

    override suspend fun import(
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
    ): RestoreTransformStats {
        var stats: RestoreTransformStats = RestoreTransformStats.Unknown
        database.withTransaction {
            val current = database.records().dataState()
            if (current?.dataGeneration != expectedGeneration) {
                fail(BackupError.TargetInvalid, "target generation changed inside the restore gate")
            }
            val currentControl = database.aiControl().control()
            if (currentControl?.authEpoch != expectedEpoch) {
                fail(BackupError.TargetInvalid, "target authEpoch changed inside the restore gate")
            }
            val dao = database.backup()
            // The fixed ZIP segment order places proposals before their sessions/runs. Deferring
            // FK enforcement to commit keeps one streaming pass while the final state still
            // satisfies every FK (the validator proves the referenced rows are present).
            dao.rawQuery(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA defer_foreign_keys = ON")).use {
                it.moveToFirst()
            }
            dao.deleteReferences()
            dao.deleteMessages()
            dao.deleteProposals()
            dao.deleteOperations()
            dao.deleteRuns()
            dao.deleteSessions()
            dao.deleteLinks()
            dao.deleteDrafts()
            dao.deleteNotes()
            dao.deleteTransactions()
            dao.deleteCategories()
            dao.deleteAccounts()
            dao.deleteControl()
            dao.deleteDataState()
            checkpoint(RestoreCheckpoint.AFTER_DELETE)

            val inserted = LongArray(LogicalSegment.entries.size)
            val imported = streamRows { segment, row ->
                insertRow(dao, segment, row)
                inserted[segment.ordinal]++
            }
            stats = imported.stats
            if (imported.backup.manifest != expectedManifest ||
                imported.backup.zipSha256 != expectedZipSha256 ||
                imported.backup.zipBytes != expectedZipBytes
            ) {
                fail(BackupError.InvalidSource, "staging backup changed during restore")
            }
            for (segment in LogicalSegment.entries) {
                if (inserted[segment.ordinal] != expectedManifest.segments[segment.ordinal].rows) {
                    fail(BackupError.Internal, "restored segment counts do not match the manifest")
                }
            }
            dao.insertControl(AiControlEntity(1, "OFF", null, null, null, null, null, newEpoch))
            checkpoint(RestoreCheckpoint.BEFORE_RECEIPT)
            dao.insertDataState(
                RecordDataStateEntity(
                    1, restoredGeneration, expectedManifest.businessRevision, owner, receipt,
                ),
            )
            assertImported(dao, expectedManifest, restoredGeneration, newEpoch, receipt)
            checkpoint(RestoreCheckpoint.BEFORE_COMMIT)
        }
        return stats
    }

    private fun insertRow(dao: BackupDao, segment: LogicalSegment, row: SegmentRow) {
        when (segment) {
            LogicalSegment.ACCOUNT -> dao.insertAccount((row as AccountRow).toEntity())
            LogicalSegment.CATEGORY -> dao.insertCategory((row as CategoryRow).toEntity())
            LogicalSegment.TRANSACTION -> dao.insertTransaction((row as TransactionRow).toEntity())
            LogicalSegment.NOTE -> dao.insertNote((row as NoteRow).toEntity())
            LogicalSegment.LINK -> dao.insertLink((row as LinkRow).toEntity())
            LogicalSegment.DRAFT -> dao.insertDraft((row as DraftRow).toEntity())
            LogicalSegment.PROPOSAL -> dao.insertProposal((row as ProposalRow).toEntity())
            LogicalSegment.OPERATION -> dao.insertOperation((row as OperationRow).toEntity())
            LogicalSegment.SESSION -> dao.insertSession((row as SessionRow).toEntity())
            LogicalSegment.RUN -> dao.insertRun((row as RunRow).toEntity())
            LogicalSegment.MESSAGE -> dao.insertMessage((row as MessageRow).toEntity())
            LogicalSegment.REFERENCE -> dao.insertReference((row as ReferenceRow).toEntity())
        }
    }

    private fun assertImported(
        dao: BackupDao,
        manifest: BackupManifest,
        restoredGeneration: String,
        newEpoch: Long,
        receipt: String,
    ) {
        val counts = listOf(
            dao.accountCount() to manifest.segments[LogicalSegment.ACCOUNT.ordinal].rows,
            dao.categoryCount() to manifest.segments[LogicalSegment.CATEGORY.ordinal].rows,
            dao.transactionCount() to manifest.segments[LogicalSegment.TRANSACTION.ordinal].rows,
            dao.noteCount() to manifest.segments[LogicalSegment.NOTE.ordinal].rows,
            dao.linkCount() to manifest.segments[LogicalSegment.LINK.ordinal].rows,
            dao.draftCount() to manifest.segments[LogicalSegment.DRAFT.ordinal].rows,
            dao.proposalCount() to manifest.segments[LogicalSegment.PROPOSAL.ordinal].rows,
            dao.operationCount() to manifest.segments[LogicalSegment.OPERATION.ordinal].rows,
            dao.sessionCount() to manifest.segments[LogicalSegment.SESSION.ordinal].rows,
            dao.runCount() to manifest.segments[LogicalSegment.RUN.ordinal].rows,
            dao.messageCount() to manifest.segments[LogicalSegment.MESSAGE.ordinal].rows,
            dao.referenceCount() to manifest.segments[LogicalSegment.REFERENCE.ordinal].rows,
        )
        if (counts.any { it.first != it.second }) {
            fail(BackupError.Internal, "restored segment counts do not match the manifest")
        }
        if (dao.activeRunCount() != 0L || dao.activeProposalCount() != 0L) {
            fail(BackupError.Internal, "restore left an active run or proposal slot")
        }
        if (dao.offControlCount() != 1L) {
            fail(BackupError.Internal, "restore did not leave AI OFF without a grant")
        }
        val state = database.records().dataState()
        if (state == null || state.dataGeneration != restoredGeneration ||
            state.restoreReceipt != receipt || state.businessRevision != manifest.businessRevision
        ) {
            fail(BackupError.Internal, "restore receipt/data state is not self-consistent")
        }
        val parsed = RestoreReceiptCodec.decode(receipt, clock.millis())
        if (parsed.restoredGeneration != restoredGeneration) {
            fail(BackupError.Internal, "restore receipt generation mismatch")
        }
        val control = database.aiControl().control()
        if (control == null || control.authEpoch != newEpoch || control.mode != "OFF" ||
            control.grantReceiverIdentity != null || control.selectedReceiverIdentity != null
        ) {
            fail(BackupError.Internal, "restore control row is not the frozen OFF state")
        }
    }
}
