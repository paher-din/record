package com.paherdin.record2.storage

import java.util.UUID

internal fun schemaId(): String = UUID.randomUUID().toString()

internal class SchemaTestFixtures {
    val time = 1_700_000_000_000L
    val generation = schemaId()
    val owner = schemaId()
    val account = AccountEntity(schemaId(), "日常账户", "CNY", 1, time, time)
    val category = CategoryEntity(schemaId(), "合成支出分类", "EXPENSE", 1, time, time)
    val transaction = TransactionEntity(
        schemaId(), "EXPENSE", 5800, "CNY", account.id, category.id,
        "午饭\n原样保留", time, 1, time, time,
    )
    val note = NoteEntity(schemaId(), "中文\n**符号** 与空格  ", time, 7, time, time, null)
    val draft = NativeFinanceDraftEntity(
        1, schemaId(), 3, "EDITING", "EDIT", transaction.id, 1,
        """{"amountText":"60.50","formatVersion":1}""", time, null,
    )
    val session = AgentSessionEntity(schemaId(), "合成会话", time, time, 2, 3)
    val run = AgentRunEntity(
        schemaId(), session.id, generation, owner, "synthetic-provider", 1, schemaId(),
        0, 1, "COMPLETED", null, time, time + 1, null,
    )
    val payload = """{"operations":[{"type":"UPDATE_NOTE","id":"${note.id}","expectedVersion":7,"body":"修订正文"}]}"""
    val displayDigest = "a".repeat(64)
    val proposal = ChangeProposalEntity(
        schemaId(), session.id, run.id, run.id, run.proposalCreationRequestId, payload,
        1, payload, displayDigest, 1, generation, "LOCKED", 1, time, time,
    )
    val frozen = """{"confirmedDisplayDigest":"$displayDigest","changes":$payload}"""
    val operation = CommitOperationEntity(
        schemaId(), proposal.id, 1, generation, frozen, "b".repeat(64), 1,
        "Pending", time, null, null, null,
    )
    val input = AgentMessageEntity(
        schemaId(), session.id, run.id, 1, "USER", "修改这条笔记", time,
        "INPUT", null, null, null,
    )
    val reply = AgentMessageEntity(
        schemaId(), session.id, run.id, 2, "ASSISTANT", "提案已准备", time + 1,
        "ProposalReady", null, proposal.id, 1,
    )
    val reference = AgentMessageReferenceEntity(reply.id, 0, null, note.id)
    val control = AiControlEntity(1, "OFF", null, null, null, null, null, 1)

    fun seed(database: RecordDatabase) = database.runInTransaction {
        with(database.records()) {
            insertDataState(RecordDataStateEntity(1, generation, 1, owner, null))
            insertAccount(account)
            insertCategory(category)
            insertTransaction(transaction)
            insertNote(note)
            insertLink(TransactionNoteLinkEntity(transaction.id, note.id))
            insertDraft(draft)
        }
        with(database.sessions()) {
            insertSession(session)
            insertRun(run)
        }
        with(database.proposals()) {
            insertProposal(proposal)
            insertOperation(operation)
        }
        with(database.sessions()) {
            insertMessage(input)
            insertMessage(reply)
            insertReference(reference)
        }
        database.aiControl().insertControl(control)
    }

    // Explicit physical values are independent of Room's generated entity readers.
    fun expectedRows(): Map<String, List<List<Any?>>> = mapOf(
        "record_data_state" to listOf(listOf(1L, generation, 1L, owner, null)),
        "account" to listOf(listOf(account.id, "日常账户", "CNY", 1L, time, time)),
        "category" to listOf(listOf(category.id, "合成支出分类", "EXPENSE", 1L, time, time)),
        "record_transaction" to listOf(listOf(
            transaction.id, "EXPENSE", 5800L, "CNY", account.id, category.id,
            "午饭\n原样保留", time, 1L, time, time,
        )),
        "note" to listOf(listOf(note.id, "中文\n**符号** 与空格  ", time, 7L, time, time, null)),
        "transaction_note_link" to listOf(listOf(transaction.id, note.id)),
        "native_finance_draft" to listOf(listOf(
            1L, draft.draftId, 3L, "EDITING", "EDIT", transaction.id, 1L,
            """{"amountText":"60.50","formatVersion":1}""", time, null,
        )),
        "agent_session" to listOf(listOf(session.id, "合成会话", time, time, 2L, 3L)),
        "agent_run" to listOf(listOf(
            run.id, session.id, generation, owner, "synthetic-provider", 1L,
            run.proposalCreationRequestId, 0L, 1L, "COMPLETED", null, time, time + 1, null,
        )),
        "agent_message" to listOf(
            listOf(input.id, session.id, run.id, 1L, "USER", "修改这条笔记", time, "INPUT", null, null, null),
            listOf(reply.id, session.id, run.id, 2L, "ASSISTANT", "提案已准备", time + 1, "ProposalReady", null, proposal.id, 1L),
        ).sortedBy { it[0].toString() },
        "agent_message_reference" to listOf(listOf(reply.id, 0L, null, note.id)),
        "change_proposal" to listOf(listOf(
            proposal.id, session.id, run.id, run.id, run.proposalCreationRequestId,
            payload, 1L, payload, displayDigest, 1L, generation, "LOCKED", 1L, time, time,
        )),
        "commit_operation" to listOf(listOf(
            operation.id, proposal.id, 1L, generation, frozen, "b".repeat(64),
            1L, "Pending", time, null, null, null,
        )),
        "ai_control" to listOf(listOf(1L, "OFF", null, null, null, null, null, 1L)),
    )
}
