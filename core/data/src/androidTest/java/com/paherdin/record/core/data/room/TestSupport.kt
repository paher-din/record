package com.paherdin.record.core.data.room

import android.content.Context
import androidx.room.Room
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.TransactionFaultHook
import com.paherdin.record.core.data.openForTest

/** Full read-only snapshot of all six physical tables (test seam). */
internal data class FullSnapshot(
    val entityRefs: Set<EntityRef>,
    val transactions: Set<Transaction>,
    val accounts: Set<Account>,
    val accountEntries: Set<AccountEntry>,
    val notes: Set<Note>,
    val relations: Set<TransactionNoteRelation>,
)

/** Reads a consistent six-table snapshot on a fresh connection. */
internal suspend fun snapshot(dbName: String, context: Context): FullSnapshot {
    val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
    return try {
        val dao = db.recordDao()
        FullSnapshot(
            entityRefs = dao.allEntityRefs().toSet(),
            transactions = dao.allTransactions().toSet(),
            accounts = dao.allAccounts().toSet(),
            accountEntries = dao.allAccountEntries().toSet(),
            notes = dao.allNotes().toSet(),
            relations = dao.allRelations().toSet(),
        )
    } finally {
        db.close()
    }
}

/** Repository on its own connection for one dbName, with cleanup. */
internal class TestDb(private val context: Context, val dbName: String) {
    val repository: RecordRepository = openForTest(context, dbName)

    fun close() {
        repository.close()
    }

    fun delete() {
        context.deleteDatabase(dbName)
    }

    companion object {
        fun create(context: Context, tag: String): TestDb = TestDb(context, "test_$tag.db")
    }
}

/** Fault-injection hook: pauses on a latch at one stage/ordinal, then throws. */
internal enum class FaultStage { BEFORE_TRANSACTION, CHILD, BEFORE_COMMIT, AFTER_COMMIT_RESPONSE }

internal class LatchFaultHook(private val injection: Injection?) : TransactionFaultHook {
    private val entered = java.util.concurrent.CountDownLatch(1)
    private val release = java.util.concurrent.CountDownLatch(1)

    fun awaitEntered() {
        check(entered.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            "writer never reached the fault stage $injection"
        }
    }

    fun release() {
        release.countDown()
    }

    private fun maybePause(stage: FaultStage, ordinal: Int? = null) {
        val injection = injection ?: return
        if (injection.stage != stage || injection.ordinal != ordinal) return
        entered.countDown()
        check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "writer release timed out" }
        throw RuntimeException("simulated fault at $stage${ordinal?.let { "/$it" } ?: ""}")
    }

    override fun beforeTransaction() = maybePause(FaultStage.BEFORE_TRANSACTION)

    override fun afterChildWrite(childIndex: Int) = maybePause(FaultStage.CHILD, childIndex)

    override fun beforeCommit() = maybePause(FaultStage.BEFORE_COMMIT)

    override fun afterCommitResponse() = maybePause(FaultStage.AFTER_COMMIT_RESPONSE)

    data class Injection(val stage: FaultStage, val ordinal: Int? = null)
}
