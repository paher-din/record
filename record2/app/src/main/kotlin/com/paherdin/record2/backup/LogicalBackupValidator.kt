package com.paherdin.record2.backup

import com.paherdin.record2.proposals.AppliedOutcome
import com.paherdin.record2.proposals.CommitCodec
import com.paherdin.record2.proposals.CommitError
import com.paherdin.record2.proposals.CommitResultCodec
import com.paherdin.record2.proposals.CommitStatus
import com.paherdin.record2.proposals.ProposalBudget
import com.paherdin.record2.proposals.ProposalCodec
import com.paherdin.record2.proposals.ProposalState
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceSeeds
import com.paherdin.record2.sessions.MessageResultKind
import com.paherdin.record2.sessions.RunState
import com.paherdin.record2.sessions.SafeRunErrorCode
import com.paherdin.record2.sessions.SessionRole
import java.nio.charset.StandardCharsets

private val DIRECTIONS = FinanceDirection.entries.map { it.name }.toSet()
private val RUN_STATES = RunState.entries.map { it.name }.toSet()
private val PROPOSAL_STATES = ProposalState.entries.map { it.name }.toSet()
private val OPERATION_STATES = CommitStatus.entries.map { it.name }.toSet()
private val ROLES = SessionRole.entries.map { it.name }.toSet()
private val RESULT_KINDS = MessageResultKind.entries.map { it.name }.toSet()
private val SAFE_ERRORS = SafeRunErrorCode.entries.map { it.name }.toSet()
private val SHA256 = Regex("[0-9a-f]{64}")
private val SEED_CATEGORIES = FinanceSeeds.categories.associateBy { it.id }

internal object LogicalBackupValidator {
    fun validate(
        backup: LogicalBackup,
        nowMillis: Long,
        cancellationCheck: () -> Unit = {},
    ) {
        requireTimestamp(backup.manifest.exportedAt, "manifest.exportedAt", nowMillis)
        validateContent(backup.content, nowMillis, cancellationCheck)
    }

    fun validateContent(
        content: LogicalBackupContent,
        nowMillis: Long,
        cancellationCheck: () -> Unit = {},
    ) {
        val validator = StreamingBackupValidator(nowMillis)
        for (segment in LogicalSegment.entries) {
            cancellationCheck()
            for (row in content.rows(segment)) validator.accept(segment, row)
        }
        validator.finish(cancellationCheck)
    }

    fun validateManifest(manifest: BackupManifest, nowMillis: Long) {
        requireTimestamp(manifest.exportedAt, "manifest.exportedAt", nowMillis)
    }
}

/**
 * Single-pass validator for the G0 rules. It keeps only the compact ids and metadata needed by
 * later cross-segment checks, so the maximum legal package never retains note bodies, memos,
 * message bodies, proposal payloads or operation payloads.
 *
 * The fixed segment order lets each row reference only earlier segments; the few forward
 * references (proposals/operations before sessions/runs) are completed in [finish], which runs
 * after the last segment and before any restore transaction commits.
 */
internal class StreamingBackupValidator(private val nowMillis: Long) {
    private var account: AccountRow? = null
    private var accountRows = 0
    private val categories = HashMap<String, CategoryRow>()
    private var categoryRows = 0
    private val transactionIds = UuidSet()
    private val noteIds = UuidSet()
    private var draftRows = 0
    private val proposalTable = UuidTable()
    private val proposals = ArrayList<ProposalMeta>()
    private val proposalCreationRequests = UuidSet()
    private val proposalActiveBySession = UuidTable()
    private var multipleActiveProposals = false
    private val operationIds = UuidSet()
    private val operationByProposal = UuidTable()
    private val sessionTable = UuidTable()
    private val sessions = ArrayList<SessionMeta>()
    private val runTable = UuidTable()
    private val runs = ArrayList<RunMeta>()
    private val runRequests = UuidSet()
    private val runOwners = UuidSet()
    private val activeRunsBySession = UuidTable()
    private var multipleActiveRuns = false
    private val messageTable = UuidTable()
    private val referenceLists = UuidTable()
    private val references = ArrayList<LongList>()
    private val linkPairs = UuidPairSet()
    private var totalRows = 0L

    fun accept(segment: LogicalSegment, row: SegmentRow) {
        totalRows++
        if (totalRows > BackupLimits.MAX_ROWS_TOTAL) invalid("backup row count exceeds 500,000")
        when (segment) {
            LogicalSegment.ACCOUNT -> acceptAccount(row as AccountRow)
            LogicalSegment.CATEGORY -> acceptCategory(row as CategoryRow)
            LogicalSegment.TRANSACTION -> acceptTransaction(row as TransactionRow)
            LogicalSegment.NOTE -> acceptNote(row as NoteRow)
            LogicalSegment.LINK -> acceptLink(row as LinkRow)
            LogicalSegment.DRAFT -> acceptDraft(row as DraftRow)
            LogicalSegment.PROPOSAL -> acceptProposal(row as ProposalRow)
            LogicalSegment.OPERATION -> acceptOperation(row as OperationRow)
            LogicalSegment.SESSION -> acceptSession(row as SessionRow)
            LogicalSegment.RUN -> acceptRun(row as RunRow)
            LogicalSegment.MESSAGE -> acceptMessage(row as MessageRow)
            LogicalSegment.REFERENCE -> acceptReference(row as ReferenceRow)
        }
    }

    fun finish(cancellationCheck: () -> Unit = {}) {
        if (accountRows != 1) invalid("backup must contain exactly one account")
        if (categoryRows != FinanceSeeds.categories.size) {
            invalid("backup must contain exactly the nine seed categories")
        }
        if (categories.keys != SEED_CATEGORIES.keys) invalid("category identity set is not the B1 seed")

        for (proposal in proposals) {
            cancellationCheck()
            val session = sessionTable.get(proposal.sessionHi, proposal.sessionLo)
            if (session < 0) invalid("change_proposal.sessionId is missing")
            val origin = runTable.get(proposal.originHi, proposal.originLo)
            if (origin < 0) invalid("change_proposal.originRunId is missing")
            val lastWriter = runTable.get(proposal.lastWriterHi, proposal.lastWriterLo)
            if (lastWriter < 0) invalid("change_proposal.lastWriterRunId is missing")
            val originRun = runs[origin.toInt()]
            val writerRun = runs[lastWriter.toInt()]
            if (originRun.sessionHi != proposal.sessionHi || originRun.sessionLo != proposal.sessionLo ||
                writerRun.sessionHi != proposal.sessionHi || writerRun.sessionLo != proposal.sessionLo
            ) {
                invalid("change_proposal run belongs to a different session")
            }
            if (originRun.requestHi != proposal.creationHi || originRun.requestLo != proposal.creationLo) {
                invalid("change_proposal creation identity is not bound to its origin run")
            }
        }
        if (multipleActiveProposals) invalid("a session has more than one active proposal")

        for (proposal in proposals) {
            cancellationCheck()
            val operation = operationByProposal.get(proposal.idHi, proposal.idLo)
            when (proposal.state) {
                ProposalState.EDITABLE -> if (operation >= 0) invalid("EDITABLE proposal has an operation")
                ProposalState.LOCKED ->
                    if (operation < 0 || operation != CommitStatus.Pending.ordinal.toLong()) {
                        invalid("LOCKED proposal must have exactly one Pending operation")
                    }
                ProposalState.NEEDS_REVIEW ->
                    if (operation >= 0 && operation != CommitStatus.Pending.ordinal.toLong()) {
                        invalid("NEEDS_REVIEW proposal may only retain a Pending operation")
                    }
                ProposalState.ABANDONED -> if (operation >= 0) invalid("ABANDONED proposal has an operation")
                ProposalState.APPLIED ->
                    if (operation < 0 || operation != CommitStatus.Applied.ordinal.toLong()) {
                        invalid("APPLIED proposal must have exactly one Applied operation")
                    }
                ProposalState.REJECTED_CONFLICT ->
                    if (operation < 0 || operation != CommitStatus.RejectedConflict.ordinal.toLong()) {
                        invalid("REJECTED_CONFLICT proposal must have a matching operation")
                    }
                ProposalState.REJECTED_INVALID ->
                    if (operation < 0 || operation != CommitStatus.RejectedInvalid.ordinal.toLong()) {
                        invalid("REJECTED_INVALID proposal must have a matching operation")
                    }
            }
        }

        for (run in runs) {
            cancellationCheck()
            if (run.userMessages != 1) invalid("each run must have exactly one USER message")
            if (run.state == RunState.COMPLETED) {
                if (run.assistantMessages != 1) {
                    invalid("COMPLETED run must have exactly one ASSISTANT message")
                }
            } else if (run.assistantMessages != 0) {
                invalid("non-COMPLETED run must not have an ASSISTANT message")
            }
        }
        if (multipleActiveRuns) invalid("a session has more than one active run")

        for (session in sessions) {
            cancellationCheck()
            val sequences = session.sequences
            val count = sequences?.size ?: 0
            if (count.toLong() != session.nextMessageSequence) {
                invalid("agent_session.nextMessageSequence does not match its messages")
            }
            if (sequences != null) {
                sequences.sort()
                for (index in 0 until sequences.size) {
                    if (sequences[index] != index.toLong()) {
                        invalid("agent_message sequences are not contiguous")
                    }
                }
            }
        }

        for (entry in 0 until references.size) {
            cancellationCheck()
            val list = references[entry]
            list.sort()
            for (index in 0 until list.size) {
                if (list[index] != index.toLong()) {
                    invalid("agent_message_reference ordinals are not contiguous")
                }
            }
        }
    }

    /** True when the requested process owner id belongs to a run in this backup. */
    fun usesProcessOwner(value: String): Boolean {
        if (!isCanonicalUuid(value)) return false
        val bits = parseUuid(value)
        return runOwners.contains(bits.hi, bits.lo)
    }

    private fun acceptAccount(row: AccountRow) {
        accountRows++
        if (accountRows > 1) invalid("backup must contain exactly one account")
        identityBits(row.id, "account.id")
        if (row.id != FinanceSeeds.ACCOUNT_ID) invalid("account identity is not the reserved seed")
        if (row.name.isBlank()) invalid("account.name must not be blank")
        if (row.currency != "CNY") invalid("account.currency must be CNY")
        requireVersion(row.version, "account.version")
        requireTimestamp(row.createdAt, "account.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "account.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("account.updatedAt is before createdAt")
        account = row
    }

    private fun acceptCategory(row: CategoryRow) {
        categoryRows++
        if (categoryRows > FinanceSeeds.categories.size) {
            invalid("backup must contain exactly the nine seed categories")
        }
        identityBits(row.id, "category.id")
        val seed = SEED_CATEGORIES[row.id] ?: invalid("category identity set is not the B1 seed")
        if (row.direction != seed.direction.name) invalid("category direction does not match the seed")
        if (row.name.isBlank()) invalid("category.name must not be blank")
        requireVersion(row.version, "category.version")
        requireTimestamp(row.createdAt, "category.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "category.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("category.updatedAt is before createdAt")
        if (categories.put(row.id, row) != null) invalid("duplicate category.id")
    }

    private fun acceptTransaction(row: TransactionRow) {
        val id = identityBits(row.id, "record_transaction.id")
        if (transactionIds.contains(id.hi, id.lo)) invalid("duplicate record_transaction.id")
        val acc = account ?: invalid("record_transaction.accountId is not the seed account")
        if (row.direction !in DIRECTIONS) invalid("record_transaction.direction is unknown")
        if (row.amountMinor !in 1..Long.MAX_VALUE) invalid("record_transaction.amountMinor is not positive")
        if (row.currency != "CNY") invalid("record_transaction.currency must be CNY")
        if (row.accountId != acc.id) invalid("record_transaction.accountId is not the seed account")
        row.categoryId?.let { categoryId ->
            identityBits(categoryId, "record_transaction.categoryId")
            val category = categories[categoryId] ?: invalid("record_transaction.categoryId is missing")
            if (category.direction != row.direction) {
                invalid("record_transaction category direction mismatch")
            }
        }
        requireVersion(row.version, "record_transaction.version")
        requireTimestamp(row.occurredAt, "record_transaction.occurredAt", nowMillis)
        requireTimestamp(row.createdAt, "record_transaction.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "record_transaction.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("record_transaction.updatedAt is before createdAt")
        if (row.occurredAt > row.updatedAt) invalid("record_transaction.occurredAt is after updatedAt")
        transactionIds.add(id.hi, id.lo)
    }

    private fun acceptNote(row: NoteRow) {
        val id = identityBits(row.id, "note.id")
        if (noteIds.contains(id.hi, id.lo)) invalid("duplicate note.id")
        if (row.body.isBlank()) invalid("note.body must not be blank")
        requireVersion(row.version, "note.version")
        requireTimestamp(row.occurredAt, "note.occurredAt", nowMillis)
        requireTimestamp(row.createdAt, "note.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "note.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("note.updatedAt is before createdAt")
        if (row.occurredAt > row.updatedAt) invalid("note.occurredAt is after updatedAt")
        row.deletedAt?.let {
            requireTimestamp(it, "note.deletedAt", nowMillis)
            if (it < row.createdAt || it > row.updatedAt) {
                invalid("note.deletedAt is outside createdAt..updatedAt")
            }
        }
        noteIds.add(id.hi, id.lo)
    }

    private fun acceptLink(row: LinkRow) {
        val transaction = identityBits(row.transactionId, "transaction_note_link.transactionId")
        val note = identityBits(row.noteId, "transaction_note_link.noteId")
        if (!transactionIds.contains(transaction.hi, transaction.lo)) {
            invalid("transaction_note_link transaction is missing")
        }
        if (!noteIds.contains(note.hi, note.lo)) invalid("transaction_note_link note is missing")
        if (!linkPairs.add(transaction.hi, transaction.lo, note.hi, note.lo)) {
            invalid("duplicate transaction_note_link pair")
        }
    }

    private fun acceptDraft(row: DraftRow) {
        draftRows++
        if (draftRows > 1) invalid("backup may contain at most one native draft")
        if (row.slot != 1) invalid("native_finance_draft.slot must be 1")
        identityBits(row.draftId, "native_finance_draft.draftId")
        identityBits(row.recordId, "native_finance_draft.recordId")
        requireVersion(row.draftRevision, "native_finance_draft.draftRevision")
        if (row.state != "EDITING") invalid("only EDITING drafts may be exported")
        if (row.mode != "NEW" && row.mode != "EDIT") invalid("native_finance_draft.mode is unknown")
        if (row.mode == "NEW") {
            if (row.baseVersion != null) invalid("NEW draft must not have a baseVersion")
            if (knownTransaction(row.recordId)) invalid("NEW draft recordId already exists")
        } else {
            val base = row.baseVersion ?: invalid("EDIT draft must have a baseVersion")
            requireVersion(base, "native_finance_draft.baseVersion")
            if (!knownTransaction(row.recordId)) invalid("EDIT draft target is missing")
        }
        requireTimestamp(row.updatedAt, "native_finance_draft.updatedAt", nowMillis)
        validateDraftInput(row.inputJson)
    }

    private fun acceptProposal(row: ProposalRow) {
        val id = identityBits(row.id, "change_proposal.id")
        if (proposalTable.get(id.hi, id.lo) >= 0) invalid("duplicate change_proposal.id")
        val session = identityBits(row.sessionId, "change_proposal.sessionId")
        val origin = identityBits(row.originRunId, "change_proposal.originRunId")
        val lastWriter = identityBits(row.lastWriterRunId, "change_proposal.lastWriterRunId")
        val creation = identityBits(row.creationRequestId, "change_proposal.creationRequestId")
        identityBits(row.dataGeneration, "change_proposal.dataGeneration")
        if (!proposalCreationRequests.add(creation.hi, creation.lo)) {
            invalid("change_proposal.creationRequestId is not unique")
        }
        requireVersion(row.revision, "change_proposal.revision")
        if (row.payloadFormatVersion != ProposalBudget.FORMAT_VERSION) {
            invalid("change_proposal.payloadFormatVersion is not 1")
        }
        if (row.createdAt < 0) invalid("change_proposal.createdAt is negative")
        requireTimestamp(row.createdAt, "change_proposal.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "change_proposal.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("change_proposal.updatedAt is before createdAt")
        if (row.state !in PROPOSAL_STATES) invalid("change_proposal.state is unknown")
        val state = ProposalState.valueOf(row.state)
        if (state.occupiesActiveSlot) {
            if (row.activeSlot != 1) invalid("${row.state} proposal must hold slot 1")
        } else if (row.activeSlot != null) {
            invalid("${row.state} proposal must not hold an active slot")
        }
        if (!SHA256.matches(row.payloadDigest)) invalid("change_proposal.payloadDigest is not hex64")
        validateEmbedded(row.creationPayloadJson, ProposalBudget.MAX_PAYLOAD_BYTES, "creationPayloadJson")
        validateEmbedded(row.payloadJson, ProposalBudget.MAX_PAYLOAD_BYTES, "payloadJson")
        val creationPayload = decodeCodec("change_proposal.creationPayloadJson") {
            ProposalCodec.decode(row.creationPayloadJson)
        }
        val payload = decodeCodec("change_proposal.payloadJson") {
            ProposalCodec.decode(row.payloadJson)
        }
        if (creationPayload.isEmpty() || payload.isEmpty()) invalid("change_proposal payload is empty")
        if (ProposalCodec.digest(row.payloadJson) != row.payloadDigest) {
            invalid("change_proposal.payloadDigest does not match payloadJson")
        }
        if (row.revision == 1L && row.creationPayloadJson != row.payloadJson) {
            invalid("revision-1 proposal creation payload differs from payload")
        }
        val index = proposals.size
        proposalTable.put(id.hi, id.lo, index.toLong())
        proposals.add(
            ProposalMeta(
                idHi = id.hi, idLo = id.lo,
                sessionHi = session.hi, sessionLo = session.lo,
                originHi = origin.hi, originLo = origin.lo,
                lastWriterHi = lastWriter.hi, lastWriterLo = lastWriter.lo,
                creationHi = creation.hi, creationLo = creation.lo,
                state = state,
                revision = row.revision,
                digest = row.payloadDigest,
            ),
        )
        if (row.activeSlot == 1) {
            val previous = proposalActiveBySession.get(session.hi, session.lo)
            if (previous >= 0) {
                multipleActiveProposals = true
            }
            proposalActiveBySession.put(session.hi, session.lo, 1)
        }
    }

    private fun acceptOperation(row: OperationRow) {
        val id = identityBits(row.id, "commit_operation.id")
        if (operationIds.contains(id.hi, id.lo)) invalid("duplicate commit_operation.id")
        val proposalId = identityBits(row.proposalId, "commit_operation.proposalId")
        requireVersion(row.confirmedRevision, "commit_operation.confirmedRevision")
        identityBits(row.createdGeneration, "commit_operation.createdGeneration")
        if (row.payloadFormatVersion != ProposalBudget.FORMAT_VERSION) {
            invalid("commit_operation.payloadFormatVersion is not 1")
        }
        if (row.state !in OPERATION_STATES) invalid("commit_operation.state is unknown")
        val status = CommitStatus.valueOf(row.state)
        if (row.createdAt < 0) invalid("commit_operation.createdAt is negative")
        requireTimestamp(row.createdAt, "commit_operation.createdAt", nowMillis)
        row.finishedAt?.let { requireTimestamp(it, "commit_operation.finishedAt", nowMillis) }
        if (!SHA256.matches(row.payloadDigest)) invalid("commit_operation.payloadDigest is not hex64")
        validateEmbedded(row.frozenPayloadJson, ProposalBudget.MAX_PAYLOAD_BYTES, "frozenPayloadJson")
        if (CommitCodec.digest(row.frozenPayloadJson) != row.payloadDigest) {
            invalid("commit_operation.payloadDigest does not match frozenPayloadJson")
        }
        val frozen = decodeCodec("commit_operation.frozenPayloadJson") {
            CommitCodec.decode(row.frozenPayloadJson)
        }
        when (status) {
            CommitStatus.Pending -> {
                if (row.finishedAt != null || row.resultJson != null || row.rejectionCode != null) {
                    invalid("Pending operation must not have result, rejection or finishedAt")
                }
            }
            CommitStatus.Applied -> {
                if (row.finishedAt == null || row.resultJson == null || row.rejectionCode != null) {
                    invalid("Applied operation must have result and finishedAt, without rejectionCode")
                }
                if (row.finishedAt < row.createdAt) invalid("operation finishedAt is before createdAt")
                val resultJson = row.resultJson
                validateEmbedded(resultJson, BackupLimits.MAX_LINE_BYTES, "resultJson")
                val result = decodeCodec("commit_operation.resultJson") {
                    CommitResultCodec.decode(resultJson)
                }
                decodeCodec("commit_operation.resultJson semantics") {
                    CommitResultCodec.validateAgainst(result, frozen.operations)
                }
                for (outcome in result.outcomes) {
                    when (outcome) {
                        is AppliedOutcome.CreateTransaction ->
                            if (!knownTransaction(outcome.id)) invalid("Applied result transaction is missing")
                        is AppliedOutcome.UpdateTransaction ->
                            if (!knownTransaction(outcome.id)) invalid("Applied result transaction is missing")
                        is AppliedOutcome.CreateNote ->
                            if (!knownNote(outcome.id)) invalid("Applied result note is missing")
                        is AppliedOutcome.UpdateNote ->
                            if (!knownNote(outcome.id)) invalid("Applied result note is missing")
                        is AppliedOutcome.LinkRecords, is AppliedOutcome.UnlinkRecords -> Unit
                    }
                }
            }
            CommitStatus.RejectedConflict, CommitStatus.RejectedInvalid -> {
                if (row.finishedAt == null || row.resultJson != null || row.rejectionCode.isNullOrBlank()) {
                    invalid("Rejected operation must have rejectionCode and finishedAt, without result")
                }
                if (row.finishedAt < row.createdAt) invalid("operation finishedAt is before createdAt")
                val cause = runCatching { CommitError.valueOf(row.rejectionCode!!) }.getOrNull()
                    ?: invalid("commit_operation.rejectionCode is unknown")
                val expected = when (cause) {
                    CommitError.NotFound, CommitError.NotVisible, CommitError.VersionConflict,
                    CommitError.IdOccupied -> CommitStatus.RejectedConflict
                    CommitError.Validation -> CommitStatus.RejectedInvalid
                    else -> invalid("commit_operation.rejectionCode is not a deterministic rejection")
                }
                if (expected != status) invalid("commit_operation.rejectionCode and state disagree")
            }
        }
        val proposalIndex = proposalTable.get(proposalId.hi, proposalId.lo)
        if (proposalIndex >= 0) {
            val proposal = proposals[proposalIndex.toInt()]
            if (proposal.digest != frozen.displayedDigest ||
                proposal.revision != row.confirmedRevision
            ) {
                invalid("operation does not freeze the displayed proposal revision")
            }
        }
        operationIds.add(id.hi, id.lo)
        if (operationByProposal.put(proposalId.hi, proposalId.lo, status.ordinal.toLong()) >= 0) {
            invalid("commit_operation.proposalId is not unique")
        }
    }

    private fun acceptSession(row: SessionRow) {
        val id = identityBits(row.id, "agent_session.id")
        if (sessionTable.get(id.hi, id.lo) >= 0) invalid("duplicate agent_session.id")
        row.title?.let { if (it.isEmpty()) invalid("agent_session.title must be null or non-empty") }
        requireTimestamp(row.createdAt, "agent_session.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "agent_session.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("agent_session.updatedAt is before createdAt")
        requireVersion(row.historyVersion, "agent_session.historyVersion")
        requireNonNegativeBelowMax(row.nextMessageSequence, "agent_session.nextMessageSequence")
        val index = sessions.size
        sessionTable.put(id.hi, id.lo, index.toLong())
        sessions.add(SessionMeta(row.nextMessageSequence, row.historyVersion))
    }

    private fun acceptRun(row: RunRow) {
        val id = identityBits(row.id, "agent_run.id")
        if (runTable.get(id.hi, id.lo) >= 0) invalid("duplicate agent_run.id")
        val sessionId = identityBits(row.sessionId, "agent_run.sessionId")
        identityBits(row.dataGeneration, "agent_run.dataGeneration")
        val owner = identityBits(row.processOwnerId, "agent_run.processOwnerId")
        requireNoControl(row.receiverIdentity, "agent_run.receiverIdentity", BackupLimits.MAX_RECEIVER_IDENTITY_CHARS)
        requireVersion(row.authEpoch, "agent_run.authEpoch")
        val request = identityBits(row.proposalCreationRequestId, "agent_run.proposalCreationRequestId")
        requireNonNegativeBelowMax(row.historyBoundarySequence, "agent_run.historyBoundarySequence")
        requireVersion(row.historyBoundaryVersion, "agent_run.historyBoundaryVersion")
        if (row.state !in RUN_STATES) invalid("agent_run.state is unknown")
        val state = RunState.valueOf(row.state)
        requireTimestamp(row.startedAt, "agent_run.startedAt", nowMillis)
        row.finishedAt?.let { requireTimestamp(it, "agent_run.finishedAt", nowMillis) }
        if (row.activeSlot != null && row.activeSlot != 1) invalid("agent_run.activeSlot must be 1 or null")
        if (state == RunState.RUNNING) {
            if (row.activeSlot != 1 || row.finishedAt != null) {
                invalid("RUNNING run must hold slot 1 and have no finishedAt")
            }
        } else {
            val finished = row.finishedAt ?: invalid("terminal run must have finishedAt")
            if (row.activeSlot != null) invalid("terminal run must not hold an active slot")
            if (finished < row.startedAt) invalid("agent_run.finishedAt is before startedAt")
        }
        when (state) {
            RunState.RUNNING, RunState.COMPLETED ->
                if (row.safeErrorCode != null) invalid("${row.state} run must have a null safeErrorCode")
            RunState.FAILED, RunState.CANCELLED ->
                if (row.safeErrorCode == null || row.safeErrorCode !in SAFE_ERRORS) {
                    invalid("${row.state} run requires a known safeErrorCode")
                }
            RunState.INTERRUPTED ->
                if (row.safeErrorCode != null && row.safeErrorCode !in SAFE_ERRORS) {
                    invalid("INTERRUPTED run has an unknown safeErrorCode")
                }
        }
        val sessionIndex = sessionTable.get(sessionId.hi, sessionId.lo)
        if (sessionIndex < 0) invalid("agent_run.sessionId is missing")
        val session = sessions[sessionIndex.toInt()]
        if (row.historyBoundarySequence > session.nextMessageSequence) {
            invalid("agent_run historyBoundarySequence exceeds the session sequence")
        }
        if (row.historyBoundaryVersion > session.historyVersion) {
            invalid("agent_run historyBoundaryVersion exceeds the session historyVersion")
        }
        if (!runRequests.add(request.hi, request.lo)) {
            invalid("agent_run.proposalCreationRequestId is not unique")
        }
        runOwners.add(owner.hi, owner.lo)
        val index = runs.size
        runTable.put(id.hi, id.lo, index.toLong())
        runs.add(
            RunMeta(
                sessionHi = sessionId.hi, sessionLo = sessionId.lo,
                requestHi = request.hi, requestLo = request.lo,
                state = state,
                startedAt = row.startedAt,
                finishedAt = row.finishedAt,
            ),
        )
        if (row.activeSlot == 1) {
            val previous = activeRunsBySession.get(sessionId.hi, sessionId.lo)
            if (previous >= 0) multipleActiveRuns = true
            activeRunsBySession.put(sessionId.hi, sessionId.lo, 1)
        }
    }

    private fun acceptMessage(row: MessageRow) {
        val id = identityBits(row.id, "agent_message.id")
        if (messageTable.get(id.hi, id.lo) >= 0) invalid("duplicate agent_message.id")
        val sessionId = identityBits(row.sessionId, "agent_message.sessionId")
        val runId = identityBits(row.runId, "agent_message.runId")
        val sessionIndex = sessionTable.get(sessionId.hi, sessionId.lo)
        if (sessionIndex < 0) invalid("agent_message.sessionId is missing")
        val runIndex = runTable.get(runId.hi, runId.lo)
        if (runIndex < 0) invalid("agent_message.runId is missing")
        val run = runs[runIndex.toInt()]
        if (run.sessionHi != sessionId.hi || run.sessionLo != sessionId.lo) {
            invalid("agent_message and run belong to different sessions")
        }
        requireNonNegativeBelowMax(row.sequence, "agent_message.sequence")
        if (row.role !in ROLES) invalid("agent_message.role is unknown")
        if (row.resultKind !in RESULT_KINDS) invalid("agent_message.resultKind is unknown")
        val role = SessionRole.valueOf(row.role)
        val kind = MessageResultKind.valueOf(row.resultKind)
        if (role == SessionRole.USER) {
            if (++run.userMessages > 1) invalid("agent_message (runId, role) is not unique")
        } else {
            if (++run.assistantMessages > 1) invalid("agent_message (runId, role) is not unique")
        }
        requireTimestamp(row.createdAt, "agent_message.createdAt", nowMillis)
        if (row.createdAt < run.startedAt) invalid("message predates its run")
        run.finishedAt?.let { if (row.createdAt > it) invalid("message postdates its run") }
        if (role == SessionRole.USER) {
            if (row.body.isEmpty()) invalid("USER message body must not be empty")
            if (kind != MessageResultKind.INPUT) invalid("USER message resultKind must be INPUT")
            if (row.resultJson != null) invalid("USER message must not have a result")
            if (row.proposalId != null || row.proposalRevision != null) {
                invalid("USER message must not have proposal fields")
            }
        } else {
            if (kind == MessageResultKind.INPUT) invalid("ASSISTANT message cannot have INPUT resultKind")
            if ((row.proposalId == null) != (row.proposalRevision == null)) {
                invalid("ASSISTANT proposal fields must both be null or both be non-null")
            }
            if (row.proposalRevision != null) {
                requireVersion(row.proposalRevision, "agent_message.proposalRevision")
                val proposalId = row.proposalId!!
                val proposalIndex = if (isCanonicalUuid(proposalId)) {
                    val bits = parseUuid(proposalId)
                    proposalTable.get(bits.hi, bits.lo)
                } else {
                    -1
                }
                if (proposalIndex < 0) invalid("ASSISTANT proposal is missing")
                val proposal = proposals[proposalIndex.toInt()]
                if (proposal.sessionHi != sessionId.hi || proposal.sessionLo != sessionId.lo) {
                    invalid("ASSISTANT proposal belongs to a different session")
                }
            }
        }
        sessions[sessionIndex.toInt()].addSequence(row.sequence)
        messageTable.put(id.hi, id.lo, role.ordinal.toLong())
    }

    private fun acceptReference(row: ReferenceRow) {
        val messageId = identityBits(row.messageId, "agent_message_reference.messageId")
        val role = messageTable.get(messageId.hi, messageId.lo)
        if (role < 0) invalid("agent_message_reference message is missing")
        if (role != SessionRole.ASSISTANT.ordinal.toLong()) {
            invalid("only ASSISTANT messages may have references")
        }
        val hasTransaction = row.transactionId != null
        val hasNote = row.noteId != null
        if (hasTransaction == hasNote) invalid("each reference must have exactly one target")
        row.transactionId?.let {
            identityBits(it, "agent_message_reference.transactionId")
            if (!knownTransaction(it)) invalid("agent_message_reference transaction is missing")
        }
        row.noteId?.let {
            identityBits(it, "agent_message_reference.noteId")
            if (!knownNote(it)) invalid("agent_message_reference note is missing")
        }
        var listIndex = referenceLists.get(messageId.hi, messageId.lo)
        if (listIndex < 0) {
            listIndex = references.size.toLong()
            referenceLists.put(messageId.hi, messageId.lo, listIndex)
            references.add(LongList())
        }
        references[listIndex.toInt()].add(row.ordinal.toLong())
    }

    private fun validateDraftInput(inputJson: String) {
        val bytes = inputJson.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > BackupLimits.MAX_LINE_BYTES) invalid("native_finance_draft.inputJson is too large")
        val root = StrictJson.decodeObject(bytes, BackupLimits.MAX_LINE_BYTES)
        root.exactFields(listOf("formatVersion", "input"), "native_finance_draft.inputJson")
        if (root.int("formatVersion", "native_finance_draft.inputJson") != 1) {
            invalid("native_finance_draft.inputJson formatVersion is not 1")
        }
        val input = root.fields["input"] as? JsonValue.ObjectValue
            ?: invalid("native_finance_draft.inputJson.input must be an object")
        input.exactFields(
            listOf(
                "amountText", "direction", "accountId", "accountVersion", "categoryId",
                "categoryVersion", "memo", "localDateTime", "zoneId",
            ),
            "native_finance_draft.inputJson.input",
        )
        input.string("amountText", "draft.input")
        val direction = input.string("direction", "draft.input")
        if (direction !in DIRECTIONS) invalid("draft.input.direction is unknown")
        input.string("accountId", "draft.input")
        input.long("accountVersion", "draft.input")
        input.nullableString("categoryId", "draft.input")
        input.nullableLong("categoryVersion", "draft.input")
        input.string("memo", "draft.input")
        input.string("localDateTime", "draft.input")
        input.string("zoneId", "draft.input")
    }

    private fun knownTransaction(value: String): Boolean {
        if (!isCanonicalUuid(value)) return false
        val bits = parseUuid(value)
        return transactionIds.contains(bits.hi, bits.lo)
    }

    private fun knownNote(value: String): Boolean {
        if (!isCanonicalUuid(value)) return false
        val bits = parseUuid(value)
        return noteIds.contains(bits.hi, bits.lo)
    }

    private class SessionMeta(
        val nextMessageSequence: Long,
        val historyVersion: Long,
    ) {
        var sequences: LongList? = null
            private set

        fun addSequence(value: Long) {
            val list = sequences ?: LongList().also { sequences = it }
            list.add(value)
        }
    }

    private class RunMeta(
        val sessionHi: Long,
        val sessionLo: Long,
        val requestHi: Long,
        val requestLo: Long,
        val state: RunState,
        val startedAt: Long,
        val finishedAt: Long?,
        var userMessages: Int = 0,
        var assistantMessages: Int = 0,
    )

    private class ProposalMeta(
        val idHi: Long,
        val idLo: Long,
        val sessionHi: Long,
        val sessionLo: Long,
        val originHi: Long,
        val originLo: Long,
        val lastWriterHi: Long,
        val lastWriterLo: Long,
        val creationHi: Long,
        val creationLo: Long,
        val state: ProposalState,
        val revision: Long,
        val digest: String,
    )
}

private class UuidBits(val hi: Long, val lo: Long)

private fun identityBits(value: String, field: String): UuidBits {
    if (!isCanonicalUuid(value)) {
        invalid("$field must be a canonical lowercase UUID")
    }
    return parseUuid(value)
}

private fun isCanonicalUuid(value: String): Boolean {
    if (value.length != 36) return false
    for (index in 0 until 36) {
        val char = value[index]
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            if (char != '-') return false
        } else if (char !in '0'..'9' && char !in 'a'..'f') {
            return false
        }
    }
    return true
}

private fun parseUuid(value: String): UuidBits {
    var hi = 0L
    var lo = 0L
    var digits = 0
    for (char in value) {
        if (char == '-') continue
        val digit = if (char in '0'..'9') char - '0' else char - 'a' + 10
        if (digits < 16) {
            hi = (hi shl 4) or digit.toLong()
        } else {
            lo = (lo shl 4) or digit.toLong()
        }
        digits++
    }
    return UuidBits(hi, lo)
}

private fun validateEmbedded(value: String, maxBytes: Int, field: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > maxBytes) fail(BackupError.InvalidSource, "$field exceeds its byte budget")
    StrictJson.decodeValue(bytes, maxBytes)
}

private fun <T> decodeCodec(label: String, block: () -> T): T = try {
    block()
} catch (error: BackupException) {
    throw error
} catch (error: Exception) {
    throw BackupException(BackupError.InvalidSource, "$label is not canonical", error)
}

private fun invalid(message: String): Nothing =
    throw BackupException(BackupError.InvalidSource, message)

private class LongList {
    private var data = LongArray(4)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Long = data[index]

    fun sort() = java.util.Arrays.sort(data, 0, size)
}

/** Open-addressed UUID-to-non-negative-long map; -1 marks absence. */
private class UuidTable(initialCapacity: Int = 64) {
    private var capacity = initialCapacity
    private var hi = LongArray(capacity)
    private var lo = LongArray(capacity)
    private var values = LongArray(capacity)
    private var used = BooleanArray(capacity)
    private var size = 0

    fun get(hi: Long, lo: Long): Long {
        var index = mix(hi, lo) and (capacity - 1)
        while (used[index]) {
            if (this.hi[index] == hi && this.lo[index] == lo) return values[index]
            index = (index + 1) and (capacity - 1)
        }
        return -1
    }

    /** Returns the previous value, or -1 after insertion. */
    fun put(hi: Long, lo: Long, value: Long): Long {
        var index = mix(hi, lo) and (capacity - 1)
        while (used[index]) {
            if (this.hi[index] == hi && this.lo[index] == lo) {
                val previous = values[index]
                values[index] = value
                return previous
            }
            index = (index + 1) and (capacity - 1)
        }
        used[index] = true
        this.hi[index] = hi
        this.lo[index] = lo
        values[index] = value
        size++
        if (size * 4 >= capacity * 3) grow()
        return -1
    }

    private fun grow() {
        val oldHi = hi
        val oldLo = lo
        val oldValues = values
        val oldUsed = used
        capacity *= 2
        hi = LongArray(capacity)
        lo = LongArray(capacity)
        values = LongArray(capacity)
        used = BooleanArray(capacity)
        size = 0
        for (index in oldUsed.indices) {
            if (oldUsed[index]) put(oldHi[index], oldLo[index], oldValues[index])
        }
    }

    private fun mix(hi: Long, lo: Long): Int {
        var value = hi * -7046029254386353131L xor lo * -4658895280553007687L
        value = value xor (value ushr 32)
        return value.toInt()
    }
}

private class UuidSet {
    private val table = UuidTable()

    fun add(hi: Long, lo: Long): Boolean {
        if (table.get(hi, lo) >= 0) return false
        table.put(hi, lo, 1)
        return true
    }

    fun contains(hi: Long, lo: Long): Boolean = table.get(hi, lo) >= 0
}

/** Open-addressed set of ordered UUID pairs. */
private class UuidPairSet(initialCapacity: Int = 64) {
    private var capacity = initialCapacity
    private var aHi = LongArray(capacity)
    private var aLo = LongArray(capacity)
    private var bHi = LongArray(capacity)
    private var bLo = LongArray(capacity)
    private var used = BooleanArray(capacity)
    private var size = 0

    fun add(aHigh: Long, aLow: Long, bHigh: Long, bLow: Long): Boolean {
        var index = mix(aHigh, aLow, bHigh, bLow) and (capacity - 1)
        while (used[index]) {
            if (aHi[index] == aHigh && aLo[index] == aLow && bHi[index] == bHigh && bLo[index] == bLow) {
                return false
            }
            index = (index + 1) and (capacity - 1)
        }
        used[index] = true
        aHi[index] = aHigh
        aLo[index] = aLow
        bHi[index] = bHigh
        bLo[index] = bLow
        size++
        if (size * 4 >= capacity * 3) grow()
        return true
    }

    private fun grow() {
        val oldAHi = aHi
        val oldALo = aLo
        val oldBHi = bHi
        val oldBLo = bLo
        val oldUsed = used
        capacity *= 2
        aHi = LongArray(capacity)
        aLo = LongArray(capacity)
        bHi = LongArray(capacity)
        bLo = LongArray(capacity)
        used = BooleanArray(capacity)
        size = 0
        for (index in oldUsed.indices) {
            if (oldUsed[index]) add(oldAHi[index], oldALo[index], oldBHi[index], oldBLo[index])
        }
    }

    private fun mix(aHigh: Long, aLow: Long, bHigh: Long, bLow: Long): Int {
        var value = aHigh * -7046029254386353131L xor aLow * -4658895280553007687L
        value = value xor (bHigh * -4417276706812531889L) xor (bLow * 1609587929392839161L)
        value = value xor (value ushr 32)
        return value.toInt()
    }
}
