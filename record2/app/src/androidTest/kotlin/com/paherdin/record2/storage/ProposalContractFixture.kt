package com.paherdin.record2.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.proposals.ProposalError
import com.paherdin.record2.proposals.ProposalException
import com.paherdin.record2.proposals.ProposalIntent
import com.paherdin.record2.proposals.ProposalRunContext
import com.paherdin.record2.proposals.RecordPair
import com.paherdin.record2.proposals.RecordRef
import com.paherdin.record2.records.FinanceDirection
import com.paherdin.record2.records.FinanceInput
import com.paherdin.record2.records.FinanceSeeds
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal val proposalClock: Clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)

internal fun proposalDatabase(name: String): RecordDatabase {
    check(Regex("c0-contract-[a-z0-9-]+\\.db").matches(name))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "com.paherdin.record2.debug")
    return Room.databaseBuilder(context, RecordDatabase::class.java, name).build()
}

internal suspend fun expectProposalError(code: ProposalError, block: suspend () -> Unit) {
    val error = try {
        block()
        error("Expected $code")
    } catch (error: ProposalException) {
        error
    }
    org.junit.Assert.assertEquals(code, error.code)
}

internal fun sha256(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Synthetic session, run and business context. Only the run identity and state fields that C0
 * re-validates are populated; run lifecycle and user history remain E1/E2 behaviour.
 */
internal class ProposalFixture(private var database: RecordDatabase) {
    val clock: Clock = proposalClock
    val generation = UUID.randomUUID().toString()
    val staleGeneration = UUID.randomUUID().toString()
    val owner = UUID.randomUUID().toString()
    val receiver = "synthetic-provider"
    val time = clock.millis()
    val occurredAt = 1_792_729_800_000L

    val account = AccountEntity(FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 1, time, time)
    val expense = CategoryEntity(FinanceSeeds.categories[0].id, "餐饮", "EXPENSE", 1, time, time)
    val income = CategoryEntity(FinanceSeeds.categories[6].id, "生活费", "INCOME", 1, time, time)
    val transaction = TransactionEntity(UUID.randomUUID().toString(), "EXPENSE", 5800, "CNY", account.id, expense.id, "午饭\n原样保留  ", occurredAt, 3, time, time)
    val note = NoteEntity(UUID.randomUUID().toString(), "中文\n**符号** 与空格  ", occurredAt, 7, time, time, null)
    val deletedNote = NoteEntity(UUID.randomUUID().toString(), "已软删除的正文", occurredAt, 2, time, time, time + 1)
    val link = TransactionNoteLinkEntity(transaction.id, note.id)
    val draft = NativeFinanceDraftEntity(1, UUID.randomUUID().toString(), 4, "EDITING", "NEW", UUID.randomUUID().toString(), null, """{"formatVersion":1}""", time, null)
    val session = AgentSessionEntity(UUID.randomUUID().toString(), "合成会话", time, time, 1, 1)
    val secondSession = AgentSessionEntity(UUID.randomUUID().toString(), "第二个合成会话", time, time, 1, 1)
    val run = runRow(session)
    val secondRun = runRow(secondSession)

    fun seed() = database.runInTransaction {
        with(database.records()) {
            insertDataState(RecordDataStateEntity(1, generation, 1, owner, null))
            insertAccount(account)
            insertCategory(expense)
            insertCategory(income)
            insertTransaction(transaction)
            insertNote(note)
            insertNote(deletedNote)
            insertLink(link)
            insertDraft(draft)
        }
        with(database.sessions()) {
            insertSession(session)
            insertSession(secondSession)
            insertRun(run)
            insertRun(secondRun)
        }
    }

    fun context(run: AgentRunEntity) =
        ProposalRunContext(run.sessionId, run.id, run.processOwnerId, run.receiverIdentity, run.authEpoch)

    fun rebind(database: RecordDatabase) {
        this.database = database
    }

    fun newRun(
        session: AgentSessionEntity = this.session,
        state: String = "RUNNING",
        activeSlot: Int? = 1,
        generation: String = this.generation,
        owner: String = this.owner,
        receiver: String = this.receiver,
        authEpoch: Long = 1,
    ): AgentRunEntity = runRow(session, state, activeSlot, generation, owner, receiver, authEpoch)
        .also { database.sessions().insertRun(it) }

    private fun runRow(
        session: AgentSessionEntity,
        state: String = "RUNNING",
        activeSlot: Int? = 1,
        generation: String = this.generation,
        owner: String = this.owner,
        receiver: String = this.receiver,
        authEpoch: Long = 1,
    ) = AgentRunEntity(
        UUID.randomUUID().toString(), session.id, generation, owner, receiver, authEpoch,
        UUID.randomUUID().toString(), 0, 1, state, activeSlot, time, if (activeSlot == null) time else null, null,
    )

    fun input(
        amount: String = "58.00",
        memo: String = "午饭",
        direction: FinanceDirection = FinanceDirection.EXPENSE,
        categoryId: String? = null,
        categoryVersion: Long? = null,
        accountId: String = account.id,
        accountVersion: Long = 1,
        localDateTime: String = "2026-10-23T12:30:00",
        zoneId: String = "Asia/Shanghai",
    ) = FinanceInput(amount, direction, accountId, accountVersion, categoryId, categoryVersion, memo, localDateTime, zoneId)

    fun createTransaction(
        localRef: String = "#t1",
        amount: String = "58.00",
        memo: String = "午饭",
        categoryId: String? = null,
        categoryVersion: Long? = null,
        direction: FinanceDirection = FinanceDirection.EXPENSE,
    ) = ProposalIntent.CreateTransaction(localRef, input(amount, memo, direction, categoryId, categoryVersion))

    fun createNote(localRef: String = "#n1", body: String = "买菜记录") =
        ProposalIntent.CreateNote(localRef, body, "2026-10-23T12:30:00", "Asia/Shanghai")

    fun updateTransaction(amount: String = "60.50", expectedVersion: Long = 3, memo: String = "改写说明") =
        ProposalIntent.UpdateTransaction(transaction.id, expectedVersion, input(amount, memo))

    fun updateNote(body: String = "修订正文", expectedVersion: Long = 7) =
        ProposalIntent.UpdateNote(note.id, expectedVersion, body, "2026-10-23T12:30:00", "Asia/Shanghai")

    fun linkNew() = ProposalIntent.LinkRecords(listOf(RecordPair(RecordRef.Local("#t1"), 1, RecordRef.Local("#n1"), 1)))

    fun linkExisting(transactionVersion: Long = 3, noteVersion: Long = 7) = ProposalIntent.LinkRecords(
        listOf(RecordPair(RecordRef.Transaction(transaction.id), transactionVersion, RecordRef.Note(note.id), noteVersion)),
    )

    fun unlinkExisting() = ProposalIntent.UnlinkRecords(
        listOf(RecordPair(RecordRef.Transaction(transaction.id), 3, RecordRef.Note(note.id), 7)),
    )

    fun unlinkDeletedNote() = ProposalIntent.UnlinkRecords(
        listOf(RecordPair(RecordRef.Transaction(transaction.id), 3, RecordRef.Note(deletedNote.id), 2)),
    )
}
