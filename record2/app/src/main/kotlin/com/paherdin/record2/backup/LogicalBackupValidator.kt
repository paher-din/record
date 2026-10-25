package com.paherdin.record2.backup

import com.paherdin.record2.proposals.AppliedOutcome
import com.paherdin.record2.proposals.CommitCodec
import com.paherdin.record2.proposals.CommitError
import com.paherdin.record2.proposals.CommitResultCodec
import com.paherdin.record2.proposals.CommitStatus
import com.paherdin.record2.proposals.FrozenCommit
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

internal object LogicalBackupValidator {
    private val DIRECTIONS = FinanceDirection.entries.map { it.name }.toSet()
    private val RUN_STATES = RunState.entries.map { it.name }.toSet()
    private val PROPOSAL_STATES = ProposalState.entries.map { it.name }.toSet()
    private val OPERATION_STATES = CommitStatus.entries.map { it.name }.toSet()
    private val ROLES = SessionRole.entries.map { it.name }.toSet()
    private val RESULT_KINDS = MessageResultKind.entries.map { it.name }.toSet()
    private val SAFE_ERRORS = SafeRunErrorCode.entries.map { it.name }.toSet()
    private val SHA256 = Regex("[0-9a-f]{64}")

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
        cancellationCheck()
        val account = validateAccounts(content.accounts, nowMillis)
        val categories = validateCategories(content.categories, nowMillis)
        val transactions = unique(content.transactions, "record_transaction.id") { it.id }
        val notes = unique(content.notes, "note.id") { it.id }
        transactions.values.forEach { cancellationCheck(); validateTransaction(it, account, categories, nowMillis) }
        notes.values.forEach { cancellationCheck(); validateNote(it, nowMillis) }
        validateLinks(content.links, transactions, notes)
        validateDraft(content.drafts, transactions, nowMillis)
        val sessions = unique(content.sessions, "agent_session.id") { it.id }
        sessions.values.forEach { cancellationCheck(); validateSession(it, nowMillis) }
        val runs = unique(content.runs, "agent_run.id") { it.id }
        val runRequests = HashSet<String>()
        runs.values.forEach {
            cancellationCheck()
            validateRun(it, sessions, nowMillis)
            if (!runRequests.add(it.proposalCreationRequestId)) {
                invalid("agent_run.proposalCreationRequestId is not unique")
            }
        }
        val proposals = unique(content.proposals, "change_proposal.id") { it.id }
        val creationRequests = HashSet<String>()
        validateProposals(proposals, runs, sessions, nowMillis, cancellationCheck) { proposal ->
            if (!creationRequests.add(proposal.creationRequestId)) {
                invalid("change_proposal.creationRequestId is not unique")
            }
        }
        val operations = unique(content.operations, "commit_operation.id") { it.id }
        val operationsByProposal = HashMap<String, OperationRow>()
        val frozenOperations = HashMap<String, FrozenCommit>()
        validateOperations(operations, transactions, notes, proposals, nowMillis, cancellationCheck) { operation, frozen ->
            if (operationsByProposal.put(operation.proposalId, operation) != null) {
                invalid("commit_operation.proposalId is not unique")
            }
            frozenOperations[operation.id] = frozen
        }
        validateProposalOperations(proposals, operationsByProposal, frozenOperations)
        val messages = unique(content.messages, "agent_message.id") { it.id }
        val messagesByRun = messages.values.groupBy { it.runId }
        val messagesBySession = messages.values.groupBy { it.sessionId }
        validateMessages(messages, messagesByRun, runs, sessions, proposals, nowMillis, cancellationCheck)
        validateReferences(content.references, messages, transactions, notes, cancellationCheck)
        validateRunMessages(runs, messagesByRun)
        validateSequences(sessions, messagesBySession)
        validateActiveSlots(runs, proposals)
        val totalRows = content.accounts.size + content.categories.size + content.transactions.size +
            content.notes.size + content.links.size + content.drafts.size +
            content.proposals.size + content.operations.size + content.sessions.size +
            content.runs.size + content.messages.size + content.references.size
        if (totalRows.toLong() > BackupLimits.MAX_ROWS_TOTAL) {
            invalid("backup row count exceeds 500,000")
        }
    }

    private fun validateAccounts(rows: List<AccountRow>, nowMillis: Long): AccountRow {
        if (rows.size != 1) invalid("backup must contain exactly one account")
        val row = rows.single()
        requireIdentity(row.id, "account.id")
        if (row.id != FinanceSeeds.ACCOUNT_ID) invalid("account identity is not the reserved seed")
        if (row.name.isBlank()) invalid("account.name must not be blank")
        if (row.currency != "CNY") invalid("account.currency must be CNY")
        requireVersion(row.version, "account.version")
        requireTimestamp(row.createdAt, "account.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "account.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("account.updatedAt is before createdAt")
        return row
    }

    private fun validateCategories(rows: List<CategoryRow>, nowMillis: Long): Map<String, CategoryRow> {
        if (rows.size != FinanceSeeds.categories.size) {
            invalid("backup must contain exactly the nine seed categories")
        }
        val byId = unique(rows, "category.id") { it.id }
        val expected = FinanceSeeds.categories.associateBy { it.id }
        if (byId.keys != expected.keys) invalid("category identity set is not the B1 seed")
        for (row in rows) {
            requireIdentity(row.id, "category.id")
            val seed = expected.getValue(row.id)
            if (row.direction != seed.direction.name) invalid("category direction does not match the seed")
            if (row.name.isBlank()) invalid("category.name must not be blank")
            requireVersion(row.version, "category.version")
            requireTimestamp(row.createdAt, "category.createdAt", nowMillis)
            requireTimestamp(row.updatedAt, "category.updatedAt", nowMillis)
            if (row.updatedAt < row.createdAt) invalid("category.updatedAt is before createdAt")
        }
        return byId
    }

    private fun validateTransaction(
        row: TransactionRow,
        account: AccountRow,
        categories: Map<String, CategoryRow>,
        nowMillis: Long,
    ) {
        requireIdentity(row.id, "record_transaction.id")
        if (row.direction !in DIRECTIONS) invalid("record_transaction.direction is unknown")
        if (row.amountMinor !in 1..Long.MAX_VALUE) invalid("record_transaction.amountMinor is not positive")
        if (row.currency != "CNY") invalid("record_transaction.currency must be CNY")
        if (row.accountId != account.id) invalid("record_transaction.accountId is not the seed account")
        row.categoryId?.let { categoryId ->
            requireIdentity(categoryId, "record_transaction.categoryId")
            val category = categories[categoryId] ?: invalid("record_transaction.categoryId is missing")
            if (category.direction != row.direction) invalid("record_transaction category direction mismatch")
        }
        requireVersion(row.version, "record_transaction.version")
        requireTimestamp(row.occurredAt, "record_transaction.occurredAt", nowMillis)
        requireTimestamp(row.createdAt, "record_transaction.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "record_transaction.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("record_transaction.updatedAt is before createdAt")
        if (row.occurredAt > row.updatedAt) invalid("record_transaction.occurredAt is after updatedAt")
    }

    private fun validateNote(row: NoteRow, nowMillis: Long) {
        requireIdentity(row.id, "note.id")
        if (row.body.isBlank()) invalid("note.body must not be blank")
        requireVersion(row.version, "note.version")
        requireTimestamp(row.occurredAt, "note.occurredAt", nowMillis)
        requireTimestamp(row.createdAt, "note.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "note.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("note.updatedAt is before createdAt")
        if (row.occurredAt > row.updatedAt) invalid("note.occurredAt is after updatedAt")
        row.deletedAt?.let {
            requireTimestamp(it, "note.deletedAt", nowMillis)
            if (it < row.createdAt || it > row.updatedAt) invalid("note.deletedAt is outside createdAt..updatedAt")
        }
    }

    private fun validateLinks(
        links: List<LinkRow>,
        transactions: Map<String, TransactionRow>,
        notes: Map<String, NoteRow>,
    ) {
        unique(links, "transaction_note_link pair") { it.transactionId to it.noteId }
        for (link in links) {
            requireIdentity(link.transactionId, "transaction_note_link.transactionId")
            requireIdentity(link.noteId, "transaction_note_link.noteId")
            if (link.transactionId !in transactions) invalid("transaction_note_link transaction is missing")
            if (link.noteId !in notes) invalid("transaction_note_link note is missing")
        }
    }

    private fun validateDraft(
        drafts: List<DraftRow>,
        transactions: Map<String, TransactionRow>,
        nowMillis: Long,
    ) {
        if (drafts.size > 1) invalid("backup may contain at most one native draft")
        val row = drafts.singleOrNull() ?: return
        if (row.slot != 1) invalid("native_finance_draft.slot must be 1")
        requireIdentity(row.draftId, "native_finance_draft.draftId")
        requireIdentity(row.recordId, "native_finance_draft.recordId")
        requireVersion(row.draftRevision, "native_finance_draft.draftRevision")
        if (row.state != "EDITING") invalid("only EDITING drafts may be exported")
        if (row.mode != "NEW" && row.mode != "EDIT") invalid("native_finance_draft.mode is unknown")
        if (row.mode == "NEW") {
            if (row.baseVersion != null) invalid("NEW draft must not have a baseVersion")
            if (row.recordId in transactions) invalid("NEW draft recordId already exists")
        } else {
            val base = row.baseVersion ?: invalid("EDIT draft must have a baseVersion")
            requireVersion(base, "native_finance_draft.baseVersion")
            if (row.recordId !in transactions) invalid("EDIT draft target is missing")
        }
        requireTimestamp(row.updatedAt, "native_finance_draft.updatedAt", nowMillis)
        validateDraftInput(row.inputJson)
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

    private fun validateSession(row: SessionRow, nowMillis: Long) {
        requireIdentity(row.id, "agent_session.id")
        row.title?.let { if (it.isEmpty()) invalid("agent_session.title must be null or non-empty") }
        requireTimestamp(row.createdAt, "agent_session.createdAt", nowMillis)
        requireTimestamp(row.updatedAt, "agent_session.updatedAt", nowMillis)
        if (row.updatedAt < row.createdAt) invalid("agent_session.updatedAt is before createdAt")
        requireVersion(row.historyVersion, "agent_session.historyVersion")
        requireNonNegativeBelowMax(row.nextMessageSequence, "agent_session.nextMessageSequence")
    }

    private fun validateRun(row: RunRow, sessions: Map<String, SessionRow>, nowMillis: Long) {
        requireIdentity(row.id, "agent_run.id")
        requireIdentity(row.sessionId, "agent_run.sessionId")
        val session = sessions[row.sessionId] ?: invalid("agent_run.sessionId is missing")
        requireIdentity(row.dataGeneration, "agent_run.dataGeneration")
        requireIdentity(row.processOwnerId, "agent_run.processOwnerId")
        requireNoControl(
            row.receiverIdentity,
            "agent_run.receiverIdentity",
            BackupLimits.MAX_RECEIVER_IDENTITY_CHARS,
        )
        requireVersion(row.authEpoch, "agent_run.authEpoch")
        requireIdentity(row.proposalCreationRequestId, "agent_run.proposalCreationRequestId")
        requireNonNegativeBelowMax(row.historyBoundarySequence, "agent_run.historyBoundarySequence")
        requireVersion(row.historyBoundaryVersion, "agent_run.historyBoundaryVersion")
        if (row.state !in RUN_STATES) invalid("agent_run.state is unknown")
        requireTimestamp(row.startedAt, "agent_run.startedAt", nowMillis)
        row.finishedAt?.let { requireTimestamp(it, "agent_run.finishedAt", nowMillis) }
        if (row.activeSlot != null && row.activeSlot != 1) invalid("agent_run.activeSlot must be 1 or null")
        if (row.state == RunState.RUNNING.name) {
            if (row.activeSlot != 1 || row.finishedAt != null) {
                invalid("RUNNING run must hold slot 1 and have no finishedAt")
            }
        } else {
            val finished = row.finishedAt ?: invalid("terminal run must have finishedAt")
            if (row.activeSlot != null) invalid("terminal run must not hold an active slot")
            if (finished < row.startedAt) invalid("agent_run.finishedAt is before startedAt")
        }
        when (row.state) {
            RunState.RUNNING.name, RunState.COMPLETED.name ->
                if (row.safeErrorCode != null) invalid("${row.state} run must have a null safeErrorCode")
            RunState.FAILED.name, RunState.CANCELLED.name ->
                if (row.safeErrorCode == null || row.safeErrorCode !in SAFE_ERRORS) {
                    invalid("${row.state} run requires a known safeErrorCode")
                }
            RunState.INTERRUPTED.name ->
                if (row.safeErrorCode != null && row.safeErrorCode !in SAFE_ERRORS) {
                    invalid("INTERRUPTED run has an unknown safeErrorCode")
                }
        }
        if (row.historyBoundarySequence > session.nextMessageSequence) {
            invalid("agent_run historyBoundarySequence exceeds the session sequence")
        }
        if (row.historyBoundaryVersion > session.historyVersion) {
            invalid("agent_run historyBoundaryVersion exceeds the session historyVersion")
        }
    }

    private fun validateProposals(
        proposals: Map<String, ProposalRow>,
        runs: Map<String, RunRow>,
        sessions: Map<String, SessionRow>,
        nowMillis: Long,
        cancellationCheck: () -> Unit,
        onCreationRequest: (ProposalRow) -> Unit,
    ) {
        for (row in proposals.values) {
            cancellationCheck()
            requireIdentity(row.id, "change_proposal.id")
            requireIdentity(row.sessionId, "change_proposal.sessionId")
            requireIdentity(row.originRunId, "change_proposal.originRunId")
            requireIdentity(row.lastWriterRunId, "change_proposal.lastWriterRunId")
            requireIdentity(row.creationRequestId, "change_proposal.creationRequestId")
            requireIdentity(row.dataGeneration, "change_proposal.dataGeneration")
            if (row.sessionId !in sessions) invalid("change_proposal.sessionId is missing")
            val origin = runs[row.originRunId] ?: invalid("change_proposal.originRunId is missing")
            val lastWriter = runs[row.lastWriterRunId] ?: invalid("change_proposal.lastWriterRunId is missing")
            if (origin.sessionId != row.sessionId || lastWriter.sessionId != row.sessionId) {
                invalid("change_proposal run belongs to a different session")
            }
            if (origin.proposalCreationRequestId != row.creationRequestId) {
                invalid("change_proposal creation identity is not bound to its origin run")
            }
            onCreationRequest(row)
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
            val creation = decodeCodec("change_proposal.creationPayloadJson") {
                ProposalCodec.decode(row.creationPayloadJson)
            }
            val payload = decodeCodec("change_proposal.payloadJson") {
                ProposalCodec.decode(row.payloadJson)
            }
            if (creation.isEmpty() || payload.isEmpty()) invalid("change_proposal payload is empty")
            if (ProposalCodec.digest(row.payloadJson) != row.payloadDigest) {
                invalid("change_proposal.payloadDigest does not match payloadJson")
            }
            if (row.revision == 1L && row.creationPayloadJson != row.payloadJson) {
                invalid("revision-1 proposal creation payload differs from payload")
            }
        }
    }

    private fun validateOperations(
        operations: Map<String, OperationRow>,
        transactions: Map<String, TransactionRow>,
        notes: Map<String, NoteRow>,
        proposals: Map<String, ProposalRow>,
        nowMillis: Long,
        cancellationCheck: () -> Unit,
        onOperation: (OperationRow, FrozenCommit) -> Unit,
    ) {
        for (row in operations.values) {
            cancellationCheck()
            requireIdentity(row.id, "commit_operation.id")
            requireIdentity(row.proposalId, "commit_operation.proposalId")
            requireVersion(row.confirmedRevision, "commit_operation.confirmedRevision")
            requireIdentity(row.createdGeneration, "commit_operation.createdGeneration")
            if (row.payloadFormatVersion != ProposalBudget.FORMAT_VERSION) {
                invalid("commit_operation.payloadFormatVersion is not 1")
            }
            if (row.state !in OPERATION_STATES) invalid("commit_operation.state is unknown")
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
            val status = CommitStatus.valueOf(row.state)
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
                                if (outcome.id !in transactions) invalid("Applied result transaction is missing")
                            is AppliedOutcome.UpdateTransaction ->
                                if (outcome.id !in transactions) invalid("Applied result transaction is missing")
                            is AppliedOutcome.CreateNote ->
                                if (outcome.id !in notes) invalid("Applied result note is missing")
                            is AppliedOutcome.UpdateNote ->
                                if (outcome.id !in notes) invalid("Applied result note is missing")
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
                    if (expected != status) invalid("commit_operation rejectionCode and state disagree")
                }
            }
            val proposal = proposals[row.proposalId]
            if (proposal != null) {
                if (proposal.payloadDigest != frozen.displayedDigest ||
                    proposal.revision != row.confirmedRevision
                ) {
                    invalid("operation does not freeze the displayed proposal revision")
                }
            }
            onOperation(row, frozen)
        }
    }

    private fun validateProposalOperations(
        proposals: Map<String, ProposalRow>,
        operationsByProposal: Map<String, OperationRow>,
        frozenOperations: Map<String, FrozenCommit>,
    ) {
        for (proposal in proposals.values) {
            val operation = operationsByProposal[proposal.id]
            when (ProposalState.valueOf(proposal.state)) {
                ProposalState.EDITABLE -> if (operation != null) invalid("EDITABLE proposal has an operation")
                ProposalState.LOCKED -> {
                    if (operation == null || operation.state != CommitStatus.Pending.name) {
                        invalid("LOCKED proposal must have exactly one Pending operation")
                    }
                }
                ProposalState.NEEDS_REVIEW -> {
                    if (operation != null && operation.state != CommitStatus.Pending.name) {
                        invalid("NEEDS_REVIEW proposal may only retain a Pending operation")
                    }
                }
                ProposalState.ABANDONED -> if (operation != null) invalid("ABANDONED proposal has an operation")
                ProposalState.APPLIED -> {
                    if (operation == null || operation.state != CommitStatus.Applied.name) {
                        invalid("APPLIED proposal must have exactly one Applied operation")
                    }
                }
                ProposalState.REJECTED_CONFLICT -> {
                    if (operation == null || operation.state != CommitStatus.RejectedConflict.name) {
                        invalid("REJECTED_CONFLICT proposal must have a matching operation")
                    }
                }
                ProposalState.REJECTED_INVALID -> {
                    if (operation == null || operation.state != CommitStatus.RejectedInvalid.name) {
                        invalid("REJECTED_INVALID proposal must have a matching operation")
                    }
                }
            }
            if (operation != null && frozenOperations[operation.id] == null) {
                invalid("operation was not validated")
            }
        }
    }

    private fun validateMessages(
        messages: Map<String, MessageRow>,
        messagesByRun: Map<String, List<MessageRow>>,
        runs: Map<String, RunRow>,
        sessions: Map<String, SessionRow>,
        proposals: Map<String, ProposalRow>,
        nowMillis: Long,
        cancellationCheck: () -> Unit,
    ) {
        val byRunRole = HashSet<Pair<String, String>>()
        val bySessionSequence = HashSet<Pair<String, Long>>()
        for (row in messages.values) {
            cancellationCheck()
            requireIdentity(row.id, "agent_message.id")
            requireIdentity(row.sessionId, "agent_message.sessionId")
            requireIdentity(row.runId, "agent_message.runId")
            if (row.sessionId !in sessions) invalid("agent_message.sessionId is missing")
            val run = runs[row.runId] ?: invalid("agent_message.runId is missing")
            if (run.sessionId != row.sessionId) invalid("agent_message and run belong to different sessions")
            requireNonNegativeBelowMax(row.sequence, "agent_message.sequence")
            if (row.role !in ROLES) invalid("agent_message.role is unknown")
            if (row.resultKind !in RESULT_KINDS) invalid("agent_message.resultKind is unknown")
            if (!byRunRole.add(row.runId to row.role)) invalid("agent_message (runId, role) is not unique")
            if (!bySessionSequence.add(row.sessionId to row.sequence)) {
                invalid("agent_message (sessionId, sequence) is not unique")
            }
            requireTimestamp(row.createdAt, "agent_message.createdAt", nowMillis)
            val runFinished = run.finishedAt
            if (row.createdAt < run.startedAt) invalid("message predates its run")
            if (runFinished != null && row.createdAt > runFinished) invalid("message postdates its run")
            val role = SessionRole.valueOf(row.role)
            val kind = MessageResultKind.valueOf(row.resultKind)
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
                    val proposal = proposals[row.proposalId!!]
                        ?: invalid("ASSISTANT proposal is missing")
                    if (proposal.sessionId != row.sessionId) {
                        invalid("ASSISTANT proposal belongs to a different session")
                    }
                }
            }
        }
        for (run in runs.values) {
            val runMessages = messagesByRun[run.id].orEmpty()
            val user = runMessages.count { it.role == SessionRole.USER.name }
            val assistant = runMessages.count { it.role == SessionRole.ASSISTANT.name }
            if (user != 1) invalid("each run must have exactly one USER message")
            if (run.state == RunState.COMPLETED.name) {
                if (assistant != 1) invalid("COMPLETED run must have exactly one ASSISTANT message")
            } else if (assistant != 0) {
                invalid("non-COMPLETED run must not have an ASSISTANT message")
            }
        }
    }

    private fun validateReferences(
        references: List<ReferenceRow>,
        messages: Map<String, MessageRow>,
        transactions: Map<String, TransactionRow>,
        notes: Map<String, NoteRow>,
        cancellationCheck: () -> Unit,
    ) {
        unique(references, "agent_message_reference (messageId, ordinal)") { it.messageId to it.ordinal }
        val byMessage = references.groupBy { it.messageId }
        for ((messageId, refs) in byMessage) {
            cancellationCheck()
            requireIdentity(messageId, "agent_message_reference.messageId")
            val message = messages[messageId] ?: invalid("agent_message_reference message is missing")
            if (message.role != SessionRole.ASSISTANT.name) invalid("only ASSISTANT messages may have references")
            refs.sortedBy { it.ordinal }.forEachIndexed { index, ref ->
                if (ref.ordinal != index) invalid("agent_message_reference ordinals are not contiguous")
                val hasTransaction = ref.transactionId != null
                val hasNote = ref.noteId != null
                if (hasTransaction == hasNote) {
                    invalid("each reference must have exactly one target")
                }
                ref.transactionId?.let {
                    requireIdentity(it, "agent_message_reference.transactionId")
                    if (it !in transactions) invalid("agent_message_reference transaction is missing")
                }
                ref.noteId?.let {
                    requireIdentity(it, "agent_message_reference.noteId")
                    if (it !in notes) invalid("agent_message_reference note is missing")
                }
            }
        }
        for (message in messages.values) {
            if (message.role == SessionRole.USER.name && byMessage.containsKey(message.id)) {
                invalid("USER messages must not have references")
            }
        }
    }

    private fun validateRunMessages(runs: Map<String, RunRow>, messagesByRun: Map<String, List<MessageRow>>) {
        for (run in runs.values) {
            val runMessages = messagesByRun[run.id].orEmpty()
            if (runMessages.count { it.role == SessionRole.USER.name } != 1) {
                invalid("run must have exactly one USER message")
            }
            val assistants = runMessages.filter { it.role == SessionRole.ASSISTANT.name }
            if (run.state == RunState.COMPLETED.name) {
                if (assistants.size != 1) invalid("COMPLETED run must have one ASSISTANT message")
            } else if (assistants.isNotEmpty()) {
                invalid("non-COMPLETED run must not have an ASSISTANT message")
            }
        }
    }

    private fun validateSequences(
        sessions: Map<String, SessionRow>,
        messagesBySession: Map<String, List<MessageRow>>,
    ) {
        for (session in sessions.values) {
            val rows = messagesBySession[session.id].orEmpty().sortedBy { it.sequence }
            if (rows.size.toLong() != session.nextMessageSequence) {
                invalid("agent_session.nextMessageSequence does not match its messages")
            }
            rows.forEachIndexed { index, row ->
                if (row.sequence != index.toLong()) invalid("agent_message sequences are not contiguous")
            }
        }
    }

    private fun validateActiveSlots(runs: Map<String, RunRow>, proposals: Map<String, ProposalRow>) {
        val runsBySession = runs.values.filter { it.activeSlot == 1 }.groupBy { it.sessionId }
        if (runsBySession.values.any { it.size > 1 }) invalid("a session has more than one active run")
        val proposalsBySession = proposals.values.filter { it.activeSlot == 1 }.groupBy { it.sessionId }
        if (proposalsBySession.values.any { it.size > 1 }) {
            invalid("a session has more than one active proposal")
        }
    }

    private fun validateEmbedded(value: String, maxBytes: Int, field: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maxBytes) {
            fail(BackupError.InvalidSource, "$field exceeds its byte budget")
        }
        StrictJson.decodeValue(bytes, maxBytes)
    }

    private fun <T> decodeCodec(label: String, block: () -> T): T = try {
        block()
    } catch (error: BackupException) {
        throw error
    } catch (error: Exception) {
        throw BackupException(BackupError.InvalidSource, "$label is not canonical", error)
    }

    private fun <T, K> unique(rows: List<T>, label: String, key: (T) -> K): Map<K, T> {
        val map = LinkedHashMap<K, T>()
        for (row in rows) {
            if (map.put(key(row), row) != null) invalid("duplicate $label")
        }
        return map
    }

    private fun invalid(message: String): Nothing =
        throw BackupException(BackupError.InvalidSource, message)
}
