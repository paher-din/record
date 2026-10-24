package com.paherdin.record2.backup

import com.paherdin.record2.proposals.AppliedCommitResult
import com.paherdin.record2.proposals.AppliedOutcome
import com.paherdin.record2.proposals.CommitCodec
import com.paherdin.record2.proposals.CommitResultCodec
import com.paherdin.record2.proposals.ProposalBudget
import com.paherdin.record2.proposals.ProposalCodec
import com.paherdin.record2.proposals.ProposalOperation
import com.paherdin.record2.proposals.ProposalTime
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceSeeds
import java.time.LocalDateTime
import java.time.ZoneOffset

internal data class BackupTestData(
    val content: LogicalBackupContent,
    val generation: String,
    val owner: String,
    val appliedProposalId: String,
    val editableProposalId: String,
    val lockedProposalId: String,
    val appliedOperationId: String,
    val pendingOperationId: String,
    val completedRunId: String,
    val runningRunIds: List<String>,
)

internal object BackupTestDataFactory {
    const val GENERATION = "11111111-1111-4111-8111-111111111111"
    const val OWNER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val RECEIVER = "synthetic-provider"
    const val START = 1_700_000_000_000L
    const val FINISH = START + 1_000L

    const val SESSION_COMPLETED = "30000000-0000-4000-8000-000000000001"
    const val SESSION_EDITABLE = "30000000-0000-4000-8000-000000000002"
    const val SESSION_LOCKED = "30000000-0000-4000-8000-000000000003"
    const val RUN_COMPLETED = "40000000-0000-4000-8000-000000000001"
    const val RUN_EDITABLE = "40000000-0000-4000-8000-000000000002"
    const val RUN_LOCKED = "40000000-0000-4000-8000-000000000003"
    const val PROPOSAL_APPLIED = "50000000-0000-4000-8000-000000000001"
    const val PROPOSAL_EDITABLE = "50000000-0000-4000-8000-000000000002"
    const val PROPOSAL_LOCKED = "50000000-0000-4000-8000-000000000003"
    const val OPERATION_APPLIED = "60000000-0000-4000-8000-000000000001"
    const val OPERATION_PENDING = "60000000-0000-4000-8000-000000000003"
    const val NOTE_ACTIVE = "70000000-0000-4000-8000-000000000001"
    const val NOTE_DELETED = "70000000-0000-4000-8000-000000000002"
    const val NOTE_LOCKED = "70000000-0000-4000-8000-000000000003"
    const val TRANSACTION_ID = "80000000-0000-4000-8000-000000000001"
    const val DRAFT_ID = "90000000-0000-4000-8000-000000000001"
    const val DRAFT_RECORD_ID = "90000000-0000-4000-8000-000000000002"
    const val USER_COMPLETED = "c0000000-0000-4000-8000-000000000001"
    const val ASSISTANT_COMPLETED = "c0000000-0000-4000-8000-000000000002"
    const val USER_EDITABLE = "c0000000-0000-4000-8000-000000000003"
    const val USER_LOCKED = "c0000000-0000-4000-8000-000000000004"
    const val REQUEST_COMPLETED = "e0000000-0000-4000-8000-000000000001"
    const val REQUEST_EDITABLE = "e0000000-0000-4000-8000-000000000002"
    const val REQUEST_LOCKED = "e0000000-0000-4000-8000-000000000003"

    fun create(): BackupTestData {
        val local = "2023-11-14T22:13:20"
        val time = ProposalTime(START, "UTC", local, 0)
        check(LocalDateTime.parse(local).toInstant(ZoneOffset.UTC).toEpochMilli() == START)

        val account = AccountRow(FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 2, START, FINISH)
        val categories = FinanceSeeds.categories.mapIndexed { index, seed ->
            CategoryRow(seed.id, seed.name, seed.direction.name, 1, START + index, FINISH)
        }
        val transaction = TransactionRow(
            TRANSACTION_ID, "EXPENSE", 1234, "CNY", FinanceSeeds.ACCOUNT_ID,
            FinanceSeeds.categories.first { it.direction == FinanceDirection.EXPENSE }.id,
            "memo\n原样", START, 1, START, FINISH,
        )
        val activeNote = NoteRow(NOTE_ACTIVE, "proposal note", START, 2, START, FINISH, null)
        val deletedNote = NoteRow(NOTE_DELETED, "deleted note", START, 1, START, FINISH, FINISH)
        val link = LinkRow(TRANSACTION_ID, NOTE_ACTIVE)
        val draft = DraftRow(
            1, DRAFT_ID, 1, "EDITING", "NEW", DRAFT_RECORD_ID, null,
            """{"formatVersion":1,"input":{"amountText":"12.34","direction":"EXPENSE","accountId":"${FinanceSeeds.ACCOUNT_ID}","accountVersion":2,"categoryId":null,"categoryVersion":null,"memo":"draft","localDateTime":"2023-11-14T22:13:20","zoneId":"UTC"}}""",
            FINISH,
        )
        val sessions = listOf(
            SessionRow(SESSION_COMPLETED, "completed", START, FINISH, 2, 2),
            SessionRow(SESSION_EDITABLE, "editable", START + 100, FINISH, 1, 1),
            SessionRow(SESSION_LOCKED, "locked", START + 100, FINISH, 1, 1),
        )
        val runs = listOf(
            RunRow(
                RUN_COMPLETED, SESSION_COMPLETED, GENERATION, OWNER, RECEIVER, 1,
                REQUEST_COMPLETED, 0, 1, "COMPLETED", null, START, FINISH, null,
            ),
            RunRow(
                RUN_EDITABLE, SESSION_EDITABLE, GENERATION, OWNER, RECEIVER, 1,
                REQUEST_EDITABLE, 0, 1, "RUNNING", 1, FINISH, null, null,
            ),
            RunRow(
                RUN_LOCKED, SESSION_LOCKED, GENERATION, OWNER, RECEIVER, 1,
                REQUEST_LOCKED, 0, 1, "RUNNING", 1, FINISH, null, null,
            ),
        )
        val appliedOperation = ProposalOperation.CreateNote("#a", "proposal note", time)
        val editableOperation = ProposalOperation.CreateNote("#e", "editable note", time)
        val lockedOperation = ProposalOperation.CreateNote("#l", "locked note", time)
        val appliedPayload = ProposalCodec.canonical(listOf(appliedOperation))
        val editablePayload = ProposalCodec.canonical(listOf(editableOperation))
        val lockedPayload = ProposalCodec.canonical(listOf(lockedOperation))
        val appliedDigest = ProposalCodec.digest(appliedPayload)
        val editableDigest = ProposalCodec.digest(editablePayload)
        val lockedDigest = ProposalCodec.digest(lockedPayload)
        val appliedFrozen = CommitCodec.freeze(listOf(appliedOperation), appliedDigest) { NOTE_ACTIVE }
        val lockedFrozen = CommitCodec.freeze(listOf(lockedOperation), lockedDigest) { NOTE_LOCKED }
        val appliedResult = CommitResultCodec.canonical(
            AppliedCommitResult(1, listOf(AppliedOutcome.CreateNote(NOTE_ACTIVE, 1))),
        )
        val proposals = listOf(
            ProposalRow(
                PROPOSAL_APPLIED, SESSION_COMPLETED, RUN_COMPLETED, RUN_COMPLETED, REQUEST_COMPLETED,
                appliedPayload, 1, appliedPayload, appliedDigest, ProposalBudget.FORMAT_VERSION,
                GENERATION, "APPLIED", null, START, FINISH,
            ),
            ProposalRow(
                PROPOSAL_EDITABLE, SESSION_EDITABLE, RUN_EDITABLE, RUN_EDITABLE, REQUEST_EDITABLE,
                editablePayload, 1, editablePayload, editableDigest, ProposalBudget.FORMAT_VERSION,
                GENERATION, "EDITABLE", 1, FINISH, FINISH,
            ),
            ProposalRow(
                PROPOSAL_LOCKED, SESSION_LOCKED, RUN_LOCKED, RUN_LOCKED, REQUEST_LOCKED,
                lockedPayload, 1, lockedPayload, lockedDigest, ProposalBudget.FORMAT_VERSION,
                GENERATION, "LOCKED", 1, FINISH, FINISH,
            ),
        )
        val operations = listOf(
            OperationRow(
                OPERATION_APPLIED, PROPOSAL_APPLIED, 1, GENERATION, appliedFrozen.canonicalJson,
                appliedFrozen.payloadDigest, ProposalBudget.FORMAT_VERSION, "Applied", START, FINISH,
                appliedResult, null,
            ),
            OperationRow(
                OPERATION_PENDING, PROPOSAL_LOCKED, 1, GENERATION, lockedFrozen.canonicalJson,
                lockedFrozen.payloadDigest, ProposalBudget.FORMAT_VERSION, "Pending", FINISH, null,
                null, null,
            ),
        )
        val messages = listOf(
            MessageRow(USER_COMPLETED, SESSION_COMPLETED, RUN_COMPLETED, 0, "USER", "completed input", START, "INPUT", null, null, null),
            MessageRow(ASSISTANT_COMPLETED, SESSION_COMPLETED, RUN_COMPLETED, 1, "ASSISTANT", "completed output", FINISH, "ProposalReady", null, PROPOSAL_APPLIED, 1),
            MessageRow(USER_EDITABLE, SESSION_EDITABLE, RUN_EDITABLE, 0, "USER", "editable input", FINISH, "INPUT", null, null, null),
            MessageRow(USER_LOCKED, SESSION_LOCKED, RUN_LOCKED, 0, "USER", "locked input", FINISH, "INPUT", null, null, null),
        )
        val references = listOf(ReferenceRow(ASSISTANT_COMPLETED, 0, null, NOTE_ACTIVE))
        return BackupTestData(
            content = LogicalBackupContent(
                accounts = listOf(account),
                categories = categories,
                transactions = listOf(transaction),
                notes = listOf(activeNote, deletedNote),
                links = listOf(link),
                drafts = listOf(draft),
                proposals = proposals,
                operations = operations,
                sessions = sessions,
                runs = runs,
                messages = messages,
                references = references,
            ),
            generation = GENERATION,
            owner = OWNER,
            appliedProposalId = PROPOSAL_APPLIED,
            editableProposalId = PROPOSAL_EDITABLE,
            lockedProposalId = PROPOSAL_LOCKED,
            appliedOperationId = OPERATION_APPLIED,
            pendingOperationId = OPERATION_PENDING,
            completedRunId = RUN_COMPLETED,
            runningRunIds = listOf(RUN_EDITABLE, RUN_LOCKED),
        )
    }

    fun seedOnly(): LogicalBackupContent {
        val account = AccountRow(FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 1, START, FINISH)
        val categories = FinanceSeeds.categories.mapIndexed { index, seed ->
            CategoryRow(seed.id, seed.name, seed.direction.name, 1, START + index, FINISH)
        }
        return LogicalBackupContent(
            accounts = listOf(account), categories = categories,
            transactions = emptyList(), notes = emptyList(), links = emptyList(), drafts = emptyList(),
            proposals = emptyList(), operations = emptyList(), sessions = emptyList(), runs = emptyList(),
            messages = emptyList(), references = emptyList(),
        )
    }
}
