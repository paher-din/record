package com.paherdin.record2.storage

import androidx.room.withTransaction
import com.paherdin.record2.backup.BackupError
import com.paherdin.record2.backup.LogicalBackup
import com.paherdin.record2.backup.ProposalRow
import com.paherdin.record2.backup.RestoreCheckpoint
import com.paherdin.record2.backup.RestoreCompletionState
import com.paherdin.record2.backup.RestoreReceiptCodec
import com.paherdin.record2.backup.RestoreStore
import com.paherdin.record2.backup.RestoreStoredState
import com.paherdin.record2.backup.RunRow
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
    ) {
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

            backup.content.accounts.forEach { dao.insertAccount(it.toEntity()) }
            backup.content.categories.forEach { dao.insertCategory(it.toEntity()) }
            backup.content.transactions.forEach { dao.insertTransaction(it.toEntity()) }
            backup.content.notes.forEach { dao.insertNote(it.toEntity()) }
            backup.content.links.forEach { dao.insertLink(it.toEntity()) }
            backup.content.drafts.forEach { dao.insertDraft(it.toEntity()) }
            backup.content.sessions.forEach { dao.insertSession(it.toEntity()) }
            runs.forEach { dao.insertRun(it.toEntity()) }
            proposals.forEach { dao.insertProposal(it.toEntity()) }
            backup.content.operations.forEach { dao.insertOperation(it.toEntity()) }
            backup.content.messages.forEach { dao.insertMessage(it.toEntity()) }
            backup.content.references.forEach { dao.insertReference(it.toEntity()) }
            dao.insertControl(AiControlEntity(1, "OFF", null, null, null, null, null, newEpoch))
            checkpoint(RestoreCheckpoint.BEFORE_RECEIPT)
            dao.insertDataState(
                RecordDataStateEntity(
                    1, restoredGeneration, backup.manifest.businessRevision, owner, receipt,
                ),
            )
            assertImported(dao, backup, restoredGeneration, newEpoch, receipt)
            checkpoint(RestoreCheckpoint.BEFORE_COMMIT)
        }
    }

    private fun assertImported(
        dao: BackupDao,
        backup: LogicalBackup,
        restoredGeneration: String,
        newEpoch: Long,
        receipt: String,
    ) {
        val content = backup.content
        val counts = listOf(
            dao.accountCount() to content.accounts.size.toLong(),
            dao.categoryCount() to content.categories.size.toLong(),
            dao.transactionCount() to content.transactions.size.toLong(),
            dao.noteCount() to content.notes.size.toLong(),
            dao.linkCount() to content.links.size.toLong(),
            dao.draftCount() to content.drafts.size.toLong(),
            dao.proposalCount() to content.proposals.size.toLong(),
            dao.operationCount() to content.operations.size.toLong(),
            dao.sessionCount() to content.sessions.size.toLong(),
            dao.runCount() to content.runs.size.toLong(),
            dao.messageCount() to content.messages.size.toLong(),
            dao.referenceCount() to content.references.size.toLong(),
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
            state.restoreReceipt != receipt || state.businessRevision != backup.manifest.businessRevision
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
