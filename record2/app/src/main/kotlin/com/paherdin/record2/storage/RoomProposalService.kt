package com.paherdin.record2.storage

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.paherdin.record2.proposals.ProposalBudget
import com.paherdin.record2.proposals.ProposalCodec
import com.paherdin.record2.proposals.ProposalError
import com.paherdin.record2.proposals.ProposalException
import com.paherdin.record2.proposals.ProposalIntent
import com.paherdin.record2.proposals.ProposalOperation
import com.paherdin.record2.proposals.ProposalRunContext
import com.paherdin.record2.proposals.ProposalService
import com.paherdin.record2.proposals.ProposalState
import com.paherdin.record2.proposals.ProposalView
import com.paherdin.record2.proposals.RecordPair
import com.paherdin.record2.proposals.RecordRef
import com.paherdin.record2.records.FinanceDirection
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ProposalCheckpoint { TARGETS_VALIDATED, PROPOSAL_INSERTED, PROPOSAL_REVISED }

internal class RoomProposalService(
    private val database: RecordDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val checkpoint: (ProposalCheckpoint) -> Unit = {},
) : ProposalService {
    private val proposals = database.proposals()
    private val sessions = database.sessions()
    private val records = database.records()

    override suspend fun prepare(context: ProposalRunContext, intents: List<ProposalIntent>): ProposalView = io {
        try {
            database.withTransaction { create(context, intents) }
        } catch (error: SQLiteConstraintException) {
            // A unique index race is resolved by re-reading the winner, never by minting a second identity.
            database.withTransaction { afterContention(context, intents, error) }
        }
    }

    override suspend fun revise(
        context: ProposalRunContext,
        proposalId: String,
        expectedRevision: Long,
        intents: List<ProposalIntent>,
    ): ProposalView = io {
        database.withTransaction {
            val run = validRun(context)
            val generation = currentGeneration(run.dataGeneration)
            editable(proposalId, run.sessionId, generation, expectedRevision)
            val operations = ProposalCodec.normalize(intents, clock)
            val canonical = ProposalCodec.canonical(operations)
            validateTargets(operations)
            mark(ProposalCheckpoint.TARGETS_VALIDATED)
            val digest = ProposalCodec.digest(canonical)
            if (proposals.reviseProposal(proposalId, expectedRevision, run.id, canonical, digest, clock.millis()) != 1) {
                fail(ProposalError.RevisionConflict)
            }
            mark(ProposalCheckpoint.PROPOSAL_REVISED)
            proposals.proposal(proposalId)!!.view()
        }
    }

    override suspend fun activeProposal(generation: String, sessionId: String): ProposalView? = io {
        database.withTransaction {
            currentGeneration(generation)
            val row = proposals.activeProposal(sessionId) ?: return@withTransaction null
            // Integrity first: a corrupt row must not be reported as an ordinary generation change.
            val view = row.view()
            if (row.dataGeneration != generation) fail(ProposalError.GenerationMismatch)
            view
        }
    }

    override suspend fun proposal(proposalId: String): ProposalView? = io {
        database.withTransaction { proposals.proposal(proposalId)?.view() }
    }

    private suspend fun create(context: ProposalRunContext, intents: List<ProposalIntent>): ProposalView {
        val run = validRun(context)
        val generation = currentGeneration(run.dataGeneration)
        val operations = ProposalCodec.normalize(intents, clock)
        val canonical = ProposalCodec.canonical(operations)
        validateTargets(operations)
        mark(ProposalCheckpoint.TARGETS_VALIDATED)
        val existing = proposals.proposalByCreationRequest(run.proposalCreationRequestId)
        if (existing != null) return repeated(existing, canonical, run, generation)
        proposals.activeProposal(run.sessionId)?.let { occupied(it) }
        val now = clock.millis()
        val id = UUID.randomUUID().toString()
        proposals.insertProposal(ChangeProposalEntity(
            id = id,
            sessionId = run.sessionId,
            originRunId = run.id,
            lastWriterRunId = run.id,
            creationRequestId = run.proposalCreationRequestId,
            creationPayloadJson = canonical,
            revision = 1,
            payloadJson = canonical,
            payloadDigest = ProposalCodec.digest(canonical),
            payloadFormatVersion = ProposalBudget.FORMAT_VERSION,
            dataGeneration = generation,
            state = ProposalState.EDITABLE.name,
            activeSlot = 1,
            createdAt = now,
            updatedAt = now,
        ))
        mark(ProposalCheckpoint.PROPOSAL_INSERTED)
        return proposals.proposal(id)!!.view()
    }

    private fun afterContention(
        context: ProposalRunContext,
        intents: List<ProposalIntent>,
        error: SQLiteConstraintException,
    ): ProposalView {
        val run = validRun(context)
        val generation = currentGeneration(run.dataGeneration)
        val canonical = ProposalCodec.canonical(ProposalCodec.normalize(intents, clock))
        val existing = proposals.proposalByCreationRequest(run.proposalCreationRequestId)
        if (existing != null) return repeated(existing, canonical, run, generation)
        proposals.activeProposal(run.sessionId)?.let { occupied(it) }
        throw error
    }

    /** A corrupt row holding the slot is a recovery condition, not merely an occupied slot. */
    private fun occupied(row: ChangeProposalEntity): Nothing {
        row.view()
        fail(ProposalError.ActiveProposalExists)
    }

    private fun repeated(
        row: ChangeProposalEntity,
        canonical: String,
        run: AgentRunEntity,
        generation: String,
    ): ProposalView {
        if (row.originRunId != run.id || row.sessionId != run.sessionId) fail(ProposalError.RecoveryRequired)
        // Integrity first: a corrupt row must never be reported as an ordinary content conflict.
        val view = row.view()
        if (row.dataGeneration != generation) fail(ProposalError.GenerationMismatch)
        // A repeated create returns the current revision; it must not roll back a later revision.
        if (row.creationPayloadJson != canonical) fail(ProposalError.CreationRequestConflict)
        return view
    }

    private fun editable(proposalId: String, sessionId: String, generation: String, expectedRevision: Long) {
        val row = proposals.proposal(proposalId) ?: fail(ProposalError.NotFound)
        if (row.sessionId != sessionId) fail(ProposalError.RunInvalidated)
        // A revision must not silently repair a payload whose persisted integrity no longer holds.
        val view = row.view()
        if (row.dataGeneration != generation) fail(ProposalError.GenerationMismatch)
        if (view.state != ProposalState.EDITABLE) fail(ProposalError.ProposalFrozen)
        if (expectedRevision < 1 || view.revision == Long.MAX_VALUE || view.revision != expectedRevision) {
            fail(ProposalError.RevisionConflict)
        }
    }

    private fun validRun(context: ProposalRunContext): AgentRunEntity {
        if (sessions.session(context.sessionId) == null) fail(ProposalError.NotFound)
        val run = sessions.run(context.runId) ?: fail(ProposalError.NotFound)
        if (run.sessionId != context.sessionId) fail(ProposalError.RunInvalidated)
        if (run.state != "RUNNING" || run.activeSlot != 1) fail(ProposalError.RunInvalidated)
        if (run.processOwnerId != context.processOwnerId) fail(ProposalError.RunInvalidated)
        if (run.receiverIdentity != context.receiverIdentity) fail(ProposalError.RunInvalidated)
        if (run.authEpoch < 1 || run.authEpoch != context.authEpoch) fail(ProposalError.RunInvalidated)
        // The run row is a start-time snapshot. Ownership may have moved to another process while
        // this row is still RUNNING, so the persisted owner is re-read inside the same transaction.
        val state = records.dataState() ?: fail(ProposalError.RecoveryRequired)
        if (state.processOwnerId != run.processOwnerId) fail(ProposalError.RunInvalidated)
        return run
    }

    private fun currentGeneration(expected: String): String {
        val state = records.dataState() ?: fail(ProposalError.RecoveryRequired)
        val canonical = runCatching { UUID.fromString(state.dataGeneration).toString() }.getOrNull()
        if (canonical != state.dataGeneration || state.businessRevision < 0) fail(ProposalError.RecoveryRequired)
        if (expected != state.dataGeneration) fail(ProposalError.GenerationMismatch)
        return state.dataGeneration
    }

    private fun validateTargets(operations: List<ProposalOperation>) {
        for (operation in operations) when (operation) {
            is ProposalOperation.CreateTransaction -> {
                account(operation.accountId, operation.accountVersion)
                category(operation.categoryId, operation.categoryVersion, operation.direction)
            }
            is ProposalOperation.UpdateTransaction -> {
                val row = records.transaction(operation.id) ?: fail(ProposalError.NotFound)
                if (row.version != operation.expectedVersion) fail(ProposalError.VersionConflict)
                account(operation.accountId, operation.accountVersion)
                category(operation.categoryId, operation.categoryVersion, operation.direction)
            }
            is ProposalOperation.CreateNote -> Unit
            is ProposalOperation.UpdateNote -> {
                if (note(operation.id).version != operation.expectedVersion) fail(ProposalError.VersionConflict)
            }
            is ProposalOperation.LinkRecords -> operation.pairs.forEach { endpoints(it) }
            is ProposalOperation.UnlinkRecords -> operation.pairs.forEach { endpoints(it) }
        }
    }

    private fun endpoints(pair: RecordPair) {
        endpoint(pair.transaction, pair.transactionVersion, transaction = true)
        endpoint(pair.note, pair.noteVersion, transaction = false)
    }

    private fun endpoint(ref: RecordRef, expectedVersion: Long, transaction: Boolean) {
        when (ref) {
            is RecordRef.Local -> if (expectedVersion != 1L) fail(ProposalError.Validation)
            is RecordRef.Transaction -> {
                if (!transaction) fail(ProposalError.Validation)
                val row = records.transaction(ref.id) ?: fail(ProposalError.NotFound)
                if (row.version != expectedVersion) fail(ProposalError.VersionConflict)
            }
            is RecordRef.Note -> {
                if (transaction) fail(ProposalError.Validation)
                if (note(ref.id).version != expectedVersion) fail(ProposalError.VersionConflict)
            }
        }
    }

    private fun account(id: String, expectedVersion: Long) {
        val row = records.account(id) ?: fail(ProposalError.NotFound)
        if (row.currency != "CNY" || row.name.isBlank() || row.version < 1) fail(ProposalError.RecoveryRequired)
        if (row.version != expectedVersion) fail(ProposalError.VersionConflict)
    }

    private fun category(id: String?, expectedVersion: Long?, direction: FinanceDirection) {
        if (id == null) {
            if (expectedVersion != null) fail(ProposalError.Validation)
            return
        }
        if (expectedVersion == null) fail(ProposalError.Validation)
        val row = records.category(id) ?: fail(ProposalError.NotFound)
        if (row.direction != direction.name) fail(ProposalError.Validation)
        if (row.version != expectedVersion) fail(ProposalError.VersionConflict)
    }

    private fun note(id: String): NoteEntity {
        records.visibleNote(id)?.let { return it }
        if (proposals.noteIncludingDeleted(id) != null) fail(ProposalError.NotVisible)
        fail(ProposalError.NotFound)
    }

    private fun ChangeProposalEntity.view(): ProposalView {
        val parsed = stateOf(state)
        if (revision < 1 || payloadFormatVersion != ProposalBudget.FORMAT_VERSION) fail(ProposalError.RecoveryRequired)
        if (parsed.occupiesActiveSlot != (activeSlot == 1)) fail(ProposalError.RecoveryRequired)
        if (createdAt < 0 || updatedAt < createdAt) fail(ProposalError.RecoveryRequired)
        for (identity in listOf(id, sessionId, originRunId, lastWriterRunId, creationRequestId, dataGeneration)) {
            if (!ProposalBudget.IDENTITY.matches(identity)) fail(ProposalError.RecoveryRequired)
        }
        if (ProposalCodec.digest(payloadJson) != payloadDigest) fail(ProposalError.RecoveryRequired)
        val operations = ProposalCodec.decode(payloadJson)
        ProposalCodec.decode(creationPayloadJson)
        return ProposalView(
            id = id,
            sessionId = sessionId,
            originRunId = originRunId,
            lastWriterRunId = lastWriterRunId,
            creationRequestId = creationRequestId,
            revision = revision,
            state = parsed,
            payloadFormatVersion = payloadFormatVersion,
            payloadDigest = payloadDigest,
            dataGeneration = dataGeneration,
            operations = operations,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun stateOf(value: String) = try {
        ProposalState.valueOf(value)
    } catch (error: IllegalArgumentException) {
        throw ProposalException(ProposalError.RecoveryRequired, error)
    }

    private fun fail(code: ProposalError): Nothing = throw ProposalException(code)

    private suspend fun mark(point: ProposalCheckpoint) {
        checkpoint(point)
        currentCoroutineContext().ensureActive()
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SQLiteFullException) {
            throw ProposalException(ProposalError.StorageFull, error)
        } catch (error: SQLiteException) {
            throw ProposalException(ProposalError.RecoverableIO, error)
        }
    }
}
