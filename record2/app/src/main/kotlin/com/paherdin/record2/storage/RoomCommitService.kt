package com.paherdin.record2.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.paherdin.record2.proposals.AppliedCommitResult
import com.paherdin.record2.proposals.AppliedOutcome
import com.paherdin.record2.proposals.CommitCheckpoint
import com.paherdin.record2.proposals.CommitCodec
import com.paherdin.record2.proposals.CommitError
import com.paherdin.record2.proposals.CommitException
import com.paherdin.record2.proposals.CommitOutcome
import com.paherdin.record2.proposals.CommitReceiptLossException
import com.paherdin.record2.proposals.CommitResultCodec
import com.paherdin.record2.proposals.CommitService
import com.paherdin.record2.proposals.CommitStatus
import com.paherdin.record2.proposals.ConfirmationCommand
import com.paherdin.record2.proposals.FrozenCommit
import com.paherdin.record2.proposals.FrozenOperation
import com.paherdin.record2.proposals.FrozenPair
import com.paherdin.record2.proposals.ProposalBudget
import com.paherdin.record2.proposals.ProposalCodec
import com.paherdin.record2.proposals.ProposalState
import com.paherdin.record2.records.FinanceDirection
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Transaction A/B of B0 §5.2/§5.3. The confirmation entry is native-only and never checks run
 * liveness; execution reads only the frozen payload, validates every target against one
 * consistent baseline inside a single Room transaction, and lets any exception roll the whole
 * attempt back to Pending instead of keeping a partial write.
 */
internal class RoomCommitService(
    private val database: RecordDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val checkpoint: (CommitCheckpoint, Int) -> Unit = { _, _ -> },
) : CommitService {
    private val proposals = database.proposals()
    private val sessions = database.sessions()
    private val records = database.records()

    override suspend fun confirm(command: ConfirmationCommand): CommitOutcome {
        val outcome = try {
            io {
                try {
                    database.withTransaction { confirmNew(command) }
                } catch (error: SQLiteConstraintException) {
                    // A unique-index race (double click) rolls this attempt back; the winner is then
                    // re-checked by identity instead of minting a second operation.
                    database.withTransaction { existingOutcome(command) ?: throw error }
                }
            }
        } catch (error: CancellationException) {
            // The Room transaction may already be committed when a prompt-cancellation discards
            // the returning value; the persisted identity decides, never the cancelled caller.
            throw reconcileConfirmation(command, error)
        }
        if (outcome.fresh) afterCommit(CommitCheckpoint.A_COMMITTED)
        return outcome
    }

    override suspend fun execute(operationId: String): CommitOutcome {
        val (outcome, performed) = try {
            io { database.withTransaction { executeOnce(operationId) } }
        } catch (error: CancellationException) {
            throw reconcileExecution(operationId, error)
        }
        if (performed) afterCommit(CommitCheckpoint.B_COMMITTED)
        return outcome
    }

    override suspend fun inspect(operationId: String): CommitOutcome? = io {
        database.withTransaction {
            identity(operationId)
            proposals.operation(operationId)?.let { outcomeOf(it, it.frozen()) }
        }
    }

    // ---- Transaction A ----

    private suspend fun confirmNew(command: ConfirmationCommand): CommitOutcome {
        validate(command)
        proposals.operationForProposal(command.proposalId)?.let { return verify(command, it) }
        val row = proposals.proposal(command.proposalId) ?: fail(CommitError.NotFound)
        val generation = displayed(row, command)
        mark(CommitCheckpoint.DISPLAY_VERIFIED)
        val frozen = CommitCodec.freeze(ProposalCodec.decode(row.payloadJson), row.payloadDigest, newId)
        val operationId = newId()
        val now = clock.millis()
        proposals.insertOperation(CommitOperationEntity(
            id = operationId,
            proposalId = row.id,
            confirmedRevision = row.revision,
            createdGeneration = generation,
            frozenPayloadJson = frozen.canonicalJson,
            payloadDigest = frozen.payloadDigest,
            payloadFormatVersion = ProposalBudget.FORMAT_VERSION,
            state = CommitStatus.Pending.name,
            createdAt = now,
            finishedAt = null,
            resultJson = null,
            rejectionCode = null,
        ))
        if (proposals.lockProposal(row.id, row.revision, now) != 1) fail(CommitError.RevisionConflict)
        mark(CommitCheckpoint.OPERATION_WRITTEN)
        return CommitOutcome(
            operationId = operationId,
            proposalId = row.id,
            confirmedRevision = row.revision,
            createdGeneration = generation,
            status = CommitStatus.Pending,
            fresh = true,
            result = null,
            rejectionCode = null,
            finishedAt = null,
            idAssignments = frozen.idAssignments,
        )
    }

    private fun existingOutcome(command: ConfirmationCommand): CommitOutcome? {
        validate(command)
        return proposals.operationForProposal(command.proposalId)?.let { verify(command, it) }
    }

    /** The identity path: an existing operation answers for itself in any state and any generation. */
    private fun verify(command: ConfirmationCommand, row: CommitOperationEntity): CommitOutcome {
        val frozen = row.frozen()
        if (row.confirmedRevision != command.displayedRevision || frozen.displayedDigest != command.displayedPayloadDigest) {
            fail(CommitError.ConfirmationMismatch)
        }
        return outcomeOf(row, frozen)
    }

    // ---- Transaction B ----

    private suspend fun executeOnce(operationId: String): Pair<CommitOutcome, Boolean> {
        identity(operationId)
        val row = proposals.operation(operationId) ?: fail(CommitError.NotFound)
        val frozen = row.frozen()
        if (row.status() != CommitStatus.Pending) return outcomeOf(row, frozen) to false
        val state = currentState()
        // An interrupted same-generation Pending retried by its owner id is the only executable
        // case; a generation restored from elsewhere is history, never an execution grant.
        if (row.createdGeneration != state.dataGeneration) fail(CommitError.GenerationMismatch)
        mark(CommitCheckpoint.PAYLOAD_VERIFIED)

        val baseline = Baseline(state.businessRevision)
        val rejection = plan(frozen.operations, baseline)
        if (rejection != null) {
            val status = rejectionStatusOf(rejection) ?: fail(CommitError.RecoveryRequired)
            val now = clock.millis()
            settleOperation(row, status, null, rejection.name, now)
            return outcomeOf(row, frozen).copy(status = status, rejectionCode = rejection.name, finishedAt = now) to true
        }
        mark(CommitCheckpoint.TARGETS_VALIDATED)

        val writes = apply(frozen.operations, baseline)
        val changed = writes.outcomes.any { it.changed }
        if (changed) {
            if (records.advanceBusinessRevision(baseline.businessRevision) != 1) fail(CommitError.RecoveryRequired)
            mark(CommitCheckpoint.BUSINESS_WRITTEN, writes.count + 1)
        }
        val result = AppliedCommitResult(
            businessRevision = if (changed) baseline.businessRevision + 1 else baseline.businessRevision,
            outcomes = writes.outcomes.map { it.outcome },
        )
        val now = clock.millis()
        settleOperation(row, CommitStatus.Applied, CommitResultCodec.canonical(result), null, now)
        return outcomeOf(row, frozen).copy(status = CommitStatus.Applied, result = result, finishedAt = now) to true
    }

    /** Deterministic pre-write validation against one consistent baseline snapshot. */
    private suspend fun plan(operations: List<FrozenOperation>, baseline: Baseline): CommitError? {
        // Objects this payload creates start as baseline rows at version 1, so later pair
        // endpoints declared by localRef validate against the same snapshot as existing facts.
        for (operation in operations) when (operation) {
            is FrozenOperation.CreateTransaction -> {
                baseline.transactions[operation.id] = TransactionEntity(
                    operation.id, operation.direction.name, operation.amountMinor, "CNY",
                    operation.accountId, operation.categoryId, operation.memo, operation.time.occurredAt, 1, 0, 0,
                )
            }
            is FrozenOperation.CreateNote -> {
                baseline.notes[operation.id] = NoteEntity(operation.id, operation.body, operation.time.occurredAt, 1, 0, 0, null)
            }
            else -> Unit
        }
        for (operation in operations) {
            try {
                when (operation) {
                    is FrozenOperation.CreateTransaction -> {
                        freeIdentity(operation.id)
                        account(operation.accountId, operation.accountVersion)
                        category(operation.categoryId, operation.categoryVersion, operation.direction)
                    }
                    is FrozenOperation.UpdateTransaction -> {
                        if (baseline.transaction(operation.id).version != operation.expectedVersion) fail(CommitError.VersionConflict)
                        account(operation.accountId, operation.accountVersion)
                        category(operation.categoryId, operation.categoryVersion, operation.direction)
                    }
                    is FrozenOperation.CreateNote -> freeIdentity(operation.id)
                    is FrozenOperation.UpdateNote -> {
                        val note = baseline.note(operation.id)
                        if (note.version != operation.expectedVersion) fail(CommitError.VersionConflict)
                        // Note occurrence time is fixed at creation on the native path, so a
                        // frozen edit that moves it is deterministically invalid, not a conflict.
                        if (note.occurredAt != operation.time.occurredAt) fail(CommitError.Validation)
                    }
                    is FrozenOperation.LinkRecords -> operation.pairs.forEach { endpoint(baseline, it) }
                    is FrozenOperation.UnlinkRecords -> operation.pairs.forEach { endpoint(baseline, it) }
                }
            } catch (error: CommitException) {
                // Only deterministic business rejections become a terminal state; integrity and
                // generation failures propagate so the transaction rolls back unchanged.
                rejectionStatusOf(error.code) ?: throw error
                return error.code
            }
        }
        return null
    }

    private fun endpoint(baseline: Baseline, pair: FrozenPair) {
        if (baseline.transaction(pair.transactionId).version != pair.transactionVersion) fail(CommitError.VersionConflict)
        if (baseline.note(pair.noteId).version != pair.noteVersion) fail(CommitError.VersionConflict)
    }

    private fun account(id: String, expectedVersion: Long) {
        val row = records.account(id) ?: fail(CommitError.NotFound)
        if (row.currency != "CNY" || row.name.isBlank() || row.version < 1) fail(CommitError.RecoveryRequired)
        if (row.version != expectedVersion) fail(CommitError.VersionConflict)
    }

    private fun category(id: String?, expectedVersion: Long?, direction: FinanceDirection) {
        if (id == null) {
            if (expectedVersion != null) fail(CommitError.Validation)
            return
        }
        if (expectedVersion == null) fail(CommitError.Validation)
        val row = records.category(id) ?: fail(CommitError.NotFound)
        if (row.direction != direction.name) fail(CommitError.Validation)
        if (row.version != expectedVersion) fail(CommitError.VersionConflict)
    }

    private fun freeIdentity(id: String) {
        if (records.transaction(id) != null || records.noteForLifecycle(id) != null) fail(CommitError.IdOccupied)
    }

    private class Planned(val outcome: AppliedOutcome, val changed: Boolean)

    private class Applied(val outcomes: List<Planned>, val count: Int)

    private suspend fun apply(operations: List<FrozenOperation>, baseline: Baseline): Applied {
        val now = clock.millis()
        var writes = 0
        val outcomes = ArrayList<Planned>(operations.size)
        for (operation in operations) outcomes += when (operation) {
            is FrozenOperation.CreateTransaction -> {
                records.insertTransaction(TransactionEntity(
                    operation.id, operation.direction.name, operation.amountMinor, "CNY",
                    operation.accountId, operation.categoryId, operation.memo, operation.time.occurredAt,
                    1, now, now,
                ))
                mark(CommitCheckpoint.BUSINESS_WRITTEN, ++writes)
                Planned(AppliedOutcome.CreateTransaction(operation.id, 1), true)
            }
            is FrozenOperation.UpdateTransaction -> {
                val row = baseline.transaction(operation.id)
                val changed = row.direction != operation.direction.name || row.amountMinor != operation.amountMinor ||
                    row.accountId != operation.accountId || row.categoryId != operation.categoryId ||
                    row.memo != operation.memo || row.occurredAt != operation.time.occurredAt
                if (changed) {
                    if (records.editTransaction(
                            operation.id, row.version, operation.direction.name, operation.amountMinor,
                            operation.accountId, operation.categoryId, operation.memo, operation.time.occurredAt, now,
                        ) != 1
                    ) fail(CommitError.RecoveryRequired)
                    baseline.transactions[operation.id] = row.copy(
                        direction = operation.direction.name, amountMinor = operation.amountMinor,
                        accountId = operation.accountId, categoryId = operation.categoryId,
                        memo = operation.memo, occurredAt = operation.time.occurredAt,
                        version = row.version + 1, updatedAt = now,
                    )
                    mark(CommitCheckpoint.BUSINESS_WRITTEN, ++writes)
                }
                Planned(AppliedOutcome.UpdateTransaction(operation.id, baseline.transaction(operation.id).version), changed)
            }
            is FrozenOperation.CreateNote -> {
                records.insertNote(NoteEntity(operation.id, operation.body, operation.time.occurredAt, 1, now, now, null))
                mark(CommitCheckpoint.BUSINESS_WRITTEN, ++writes)
                Planned(AppliedOutcome.CreateNote(operation.id, 1), true)
            }
            is FrozenOperation.UpdateNote -> {
                val row = baseline.note(operation.id)
                val changed = row.body != operation.body
                if (changed) {
                    if (records.updateNoteBody(operation.id, row.version, operation.body, now) != 1) fail(CommitError.RecoveryRequired)
                    baseline.notes[operation.id] = row.copy(body = operation.body, version = row.version + 1, updatedAt = now)
                    mark(CommitCheckpoint.BUSINESS_WRITTEN, ++writes)
                }
                Planned(AppliedOutcome.UpdateNote(operation.id, baseline.note(operation.id).version), changed)
            }
            is FrozenOperation.LinkRecords -> {
                val count = relate(baseline, operation.pairs, link = true) { mark(CommitCheckpoint.BUSINESS_WRITTEN, ++writes) }
                Planned(AppliedOutcome.LinkRecords(count), count > 0)
            }
            is FrozenOperation.UnlinkRecords -> {
                val count = relate(baseline, operation.pairs, link = false) { mark(CommitCheckpoint.BUSINESS_WRITTEN, ++writes) }
                Planned(AppliedOutcome.UnlinkRecords(count), count > 0)
            }
        }
        return Applied(outcomes, writes)
    }

    /** Applies one relation operation: effective pairs only, mirroring the native batch semantics. */
    private suspend fun relate(baseline: Baseline, pairs: List<FrozenPair>, link: Boolean, written: suspend () -> Unit): Int {
        val now = clock.millis()
        val effective = pairs.filter { (baseline.linked(it.transactionId).contains(it.noteId)) != link }
        val affectedTransactions = LinkedHashSet<String>()
        for (pair in effective) {
            val note = baseline.note(pair.noteId)
            if (records.touchNote(note.id, note.version, now) != 1) fail(CommitError.RecoveryRequired)
            baseline.notes[note.id] = note.copy(version = note.version + 1, updatedAt = now)
            written()
            if (link) {
                records.insertLink(TransactionNoteLinkEntity(pair.transactionId, pair.noteId))
            } else {
                if (records.removeLink(pair.transactionId, pair.noteId) != 1) fail(CommitError.RecoveryRequired)
            }
            baseline.setLinked(pair.transactionId, pair.noteId, link)
            written()
            affectedTransactions += pair.transactionId
        }
        for (transactionId in affectedTransactions) {
            val transaction = baseline.transaction(transactionId)
            if (records.touchTransaction(transaction.id, transaction.version, now) != 1) fail(CommitError.RecoveryRequired)
            baseline.transactions[transaction.id] = transaction.copy(version = transaction.version + 1, updatedAt = now)
            written()
        }
        return effective.size
    }

    /** Writes the terminal operation row and settles a still-locked proposal, freeing its slot. */
    private suspend fun settleOperation(row: CommitOperationEntity, status: CommitStatus, resultJson: String?, rejectionCode: String?, now: Long) {
        if (proposals.finishOperation(row.id, row.payloadDigest, status.name, resultJson, rejectionCode, now) != 1) {
            fail(CommitError.RecoveryRequired)
        }
        settleProposal(row.proposalId, status, now)
        mark(CommitCheckpoint.APPLIED_WRITTEN, 0)
    }

    private fun settleProposal(proposalId: String, status: CommitStatus, now: Long) {
        val proposal = proposals.proposal(proposalId) ?: return
        val state = try {
            ProposalState.valueOf(proposal.state)
        } catch (error: IllegalArgumentException) {
            throw CommitException(CommitError.RecoveryRequired, error)
        }
        when (state) {
            ProposalState.LOCKED ->
                if (proposals.settleProposal(proposalId, proposalStateOf(status), now) != 1) fail(CommitError.RecoveryRequired)
            ProposalState.EDITABLE -> fail(CommitError.RecoveryRequired)
            // Terminal or review states are outside B's authority; the operation row stays authoritative.
            else -> Unit
        }
    }

    // ---- row and state validation ----

    /**
     * Post-commit cancellation reconciliation. A cancelled withContext may discard an already
     * committed result, so the durable operation state — read synchronously so no cancelled
     * resume can discard the answer — classifies the outcome: committed means receipt loss,
     * unchanged means a plain cancellation, and an unreadable database means the outcome
     * cannot be determined.
     */
    /** What the durable operation state says about a cancelled attempt. */
    private enum class Verdict { COMMITTED, ROLLED_BACK, MISMATCH, UNREADABLE }

    private suspend fun reconcileConfirmation(command: ConfirmationCommand, cause: CancellationException): Nothing {
        val verdict = withContext(NonCancellable) {
            // Inner IO hop without an outer dispatcher switch: the outer undispatched completion
            // delivers the answer into the cancelled frame atomically, so neither the value is
            // discarded nor the caller thread blocked.
            withContext(Dispatchers.IO) {
                try {
                    validate(command)
                    val row = proposals.operationForProposal(command.proposalId)
                        ?: return@withContext Verdict.ROLLED_BACK
                    val frozen = row.frozen()
                    if (row.confirmedRevision == command.displayedRevision && frozen.displayedDigest == command.displayedPayloadDigest) Verdict.COMMITTED else Verdict.MISMATCH
                } catch (error: SQLiteException) {
                    Verdict.UNREADABLE
                }
            }
        }
        when (verdict) {
            Verdict.COMMITTED -> throw CommitReceiptLossException(cause)
            Verdict.ROLLED_BACK -> throw cause
            // The persisted operation belongs to a different display; this command's answer is
            // the deterministic mismatch it would have received without the cancellation.
            Verdict.MISMATCH -> throw CommitException(CommitError.ConfirmationMismatch, cause)
            Verdict.UNREADABLE -> throw CommitException(CommitError.OutcomeUnknown, cause)
        }
    }

    private suspend fun reconcileExecution(operationId: String, cause: CancellationException): Nothing {
        val committed = withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                try {
                    identity(operationId)
                    when (proposals.operation(operationId)?.status()) {
                        CommitStatus.Pending, null -> false
                        else -> true
                    }
                } catch (error: SQLiteException) {
                    null
                }
            }
        }
        when (committed) {
            null -> throw CommitException(CommitError.OutcomeUnknown, cause)
            true -> throw CommitReceiptLossException(cause)
            false -> throw cause
        }
    }

    private fun displayed(row: ChangeProposalEntity, command: ConfirmationCommand): String {
        if (row.revision < 1 || row.payloadFormatVersion != ProposalBudget.FORMAT_VERSION) fail(CommitError.RecoveryRequired)
        if (row.createdAt < 0 || row.updatedAt < row.createdAt) fail(CommitError.RecoveryRequired)
        for (identity in listOf(row.id, row.sessionId, row.originRunId, row.lastWriterRunId, row.creationRequestId, row.dataGeneration)) {
            if (!ProposalBudget.IDENTITY.matches(identity)) fail(CommitError.RecoveryRequired)
        }
        // The same C0 invariant: the creation identity belongs to the originating run, so a
        // well-formed but rebound row is corruption and must never freeze into an operation.
        val originRun = sessions.run(row.originRunId) ?: fail(CommitError.RecoveryRequired)
        if (originRun.proposalCreationRequestId != row.creationRequestId) fail(CommitError.RecoveryRequired)
        val state = try {
            ProposalState.valueOf(row.state)
        } catch (error: IllegalArgumentException) {
            throw CommitException(CommitError.RecoveryRequired, error)
        }
        if (state != ProposalState.EDITABLE || row.activeSlot != 1) fail(CommitError.ProposalFrozen)
        if (ProposalCodec.digest(row.payloadJson) != row.payloadDigest) fail(CommitError.RecoveryRequired)
        ProposalCodec.decode(row.payloadJson)
        ProposalCodec.decode(row.creationPayloadJson)
        if (sessions.session(row.sessionId) == null) fail(CommitError.NotFound)
        if (row.revision != command.displayedRevision || row.payloadDigest != command.displayedPayloadDigest) {
            fail(CommitError.ConfirmationMismatch)
        }
        val current = currentState()
        if (row.dataGeneration != current.dataGeneration) fail(CommitError.GenerationMismatch)
        if (command.dataGeneration != current.dataGeneration) fail(CommitError.GenerationMismatch)
        return current.dataGeneration
    }

    private fun currentState(): RecordDataStateEntity {
        val state = records.dataState() ?: fail(CommitError.RecoveryRequired)
        val canonical = runCatching { UUID.fromString(state.dataGeneration).toString() }.getOrNull()
        if (canonical != state.dataGeneration || state.businessRevision < 0) fail(CommitError.RecoveryRequired)
        return state
    }

    private fun CommitOperationEntity.status(): CommitStatus = try {
        CommitStatus.valueOf(state)
    } catch (error: IllegalArgumentException) {
        throw CommitException(CommitError.RecoveryRequired, error)
    }

    /** Reads the frozen payload with full integrity checks; corruption fails closed everywhere. */
    private fun CommitOperationEntity.frozen(): FrozenCommit {
        if (!ProposalBudget.IDENTITY.matches(id) || !ProposalBudget.IDENTITY.matches(proposalId)) fail(CommitError.RecoveryRequired)
        if (confirmedRevision < 1 || confirmedRevision == Long.MAX_VALUE) fail(CommitError.RecoveryRequired)
        if (runCatching { UUID.fromString(createdGeneration).toString() }.getOrNull() != createdGeneration) fail(CommitError.RecoveryRequired)
        if (payloadFormatVersion != ProposalBudget.FORMAT_VERSION || createdAt < 0) fail(CommitError.RecoveryRequired)
        if (CommitCodec.digest(frozenPayloadJson) != payloadDigest) fail(CommitError.RecoveryRequired)
        val frozen = CommitCodec.decode(frozenPayloadJson)
        val status = status()
        when (status) {
            CommitStatus.Pending ->
                if (finishedAt != null || resultJson != null || rejectionCode != null) fail(CommitError.RecoveryRequired)
            CommitStatus.Applied -> {
                if (finishedAt == null || rejectionCode != null || resultJson == null) fail(CommitError.RecoveryRequired)
                CommitResultCodec.validateAgainst(CommitResultCodec.decode(resultJson), frozen.operations)
            }
            CommitStatus.RejectedConflict, CommitStatus.RejectedInvalid -> {
                if (finishedAt == null || rejectionCode.isNullOrBlank() || resultJson != null) fail(CommitError.RecoveryRequired)
                // The persisted rejection cause must be a known deterministic error whose
                // rejection class matches the persisted state; anything else is corruption.
                val cause = try {
                    CommitError.valueOf(rejectionCode)
                } catch (error: IllegalArgumentException) {
                    throw CommitException(CommitError.RecoveryRequired, error)
                }
                if (rejectionStatusOf(cause) != status) fail(CommitError.RecoveryRequired)
            }
        }
        return frozen
    }

    private fun outcomeOf(row: CommitOperationEntity, frozen: FrozenCommit): CommitOutcome = CommitOutcome(
        operationId = row.id,
        proposalId = row.proposalId,
        confirmedRevision = row.confirmedRevision,
        createdGeneration = row.createdGeneration,
        status = row.status(),
        fresh = false,
        result = row.resultJson?.let(CommitResultCodec::decode),
        rejectionCode = row.rejectionCode,
        finishedAt = row.finishedAt,
        idAssignments = frozen.idAssignments,
    )

    private fun rejectionStatusOf(error: CommitError): CommitStatus? = when (error) {
        CommitError.NotFound, CommitError.NotVisible, CommitError.VersionConflict, CommitError.IdOccupied -> CommitStatus.RejectedConflict
        CommitError.Validation -> CommitStatus.RejectedInvalid
        else -> null
    }

    private fun proposalStateOf(status: CommitStatus): String = when (status) {
        CommitStatus.Applied -> ProposalState.APPLIED.name
        CommitStatus.RejectedConflict -> ProposalState.REJECTED_CONFLICT.name
        CommitStatus.RejectedInvalid -> ProposalState.REJECTED_INVALID.name
        CommitStatus.Pending -> fail(CommitError.RecoveryRequired)
    }

    /** One consistent pre-write snapshot; identities this payload creates start at version 1. */
    private inner class Baseline(val businessRevision: Long) {
        val transactions = HashMap<String, TransactionEntity>()
        val notes = HashMap<String, NoteEntity>()
        private val links = HashMap<String, HashSet<String>>()

        fun transaction(id: String): TransactionEntity = transactions.getOrPut(id) {
            records.transaction(id)?.let { row ->
                if (row.currency != "CNY" || row.amountMinor <= 0 || row.version < 1) fail(CommitError.RecoveryRequired)
                direction(row.direction)
                row
            } ?: fail(CommitError.NotFound)
        }

        fun note(id: String): NoteEntity = notes.getOrPut(id) {
            records.visibleNote(id)?.let { row ->
                if (row.body.isBlank() || row.version < 1) fail(CommitError.RecoveryRequired)
                row
            } ?: if (records.noteForLifecycle(id) != null) fail(CommitError.NotVisible) else fail(CommitError.NotFound)
        }

        fun linked(transactionId: String): Set<String> = links.getOrPut(transactionId) { records.linkedNoteIds(transactionId).toHashSet() }

        fun setLinked(transactionId: String, noteId: String, present: Boolean) {
            if (present) links.getOrPut(transactionId) { HashSet() }.add(noteId)
            else links.getOrPut(transactionId) { HashSet() }.remove(noteId)
        }
    }

    private fun direction(value: String): FinanceDirection = try {
        FinanceDirection.valueOf(value)
    } catch (error: IllegalArgumentException) {
        throw CommitException(CommitError.RecoveryRequired, error)
    }

    private fun validate(command: ConfirmationCommand) {
        if (!ProposalBudget.IDENTITY.matches(command.proposalId)) fail(CommitError.Validation)
        if (command.displayedRevision < 1 || command.displayedRevision == Long.MAX_VALUE) fail(CommitError.Validation)
        if (!Regex("[0-9a-f]{64}").matches(command.displayedPayloadDigest)) fail(CommitError.Validation)
        if (runCatching { UUID.fromString(command.dataGeneration).toString() }.getOrNull() != command.dataGeneration) fail(CommitError.Validation)
    }

    private fun identity(id: String) {
        if (!ProposalBudget.IDENTITY.matches(id)) fail(CommitError.Validation)
    }

    private suspend fun mark(point: CommitCheckpoint, index: Int = 0) {
        checkpoint(point, index)
        currentCoroutineContext().ensureActive()
    }

    /** The durable state is already committed; only the receipt can still fail here. */
    private suspend fun afterCommit(point: CommitCheckpoint) {
        try {
            mark(point)
        } catch (error: CancellationException) {
            throw CommitReceiptLossException(error)
        } catch (error: Exception) {
            throw CommitReceiptLossException(error)
        }
    }

    private fun fail(code: CommitError): Nothing = throw CommitException(code)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SQLiteFullException) {
            throw CommitException(CommitError.StorageFull, error)
        } catch (error: SQLiteException) {
            throw CommitException(CommitError.RecoverableIO, error)
        }
    }
}
