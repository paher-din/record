package com.paherdin.record.core.data.room

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.TransactionFaultHook
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import java.math.BigInteger

/** Full read-only snapshot of all six physical tables (test seam). */
internal data class FullSnapshot(
    val entityRefs: Set<EntityRef>,
    val transactions: Set<Transaction>,
    val accounts: Set<Account>,
    val accountEntries: Set<AccountEntry>,
    val notes: Set<Note>,
    val relations: Set<TransactionNoteRelation>,
) {
    /** Domain balance definition: sum of effective (non-soft-deleted) entries. */
    fun effectiveEntrySum(accountId: String): BigInteger {
        val deleted = entityRefs.filter { it.deletedAt != null }.map { it.id }.toSet()
        return accountEntries
            .filter { it.accountId == accountId && it.transactionId !in deleted }
            .fold(BigInteger.ZERO) { acc, e -> acc.add(e.amountMinor.toBigInteger()) }
    }
}

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

/**
 * Atomic six-table snapshot on one connection inside an explicit deferred
 * `BEGIN` transaction. In WAL mode the first read pins a read snapshot that
 * every later read on the connection shares, and the deferred transaction
 * does not block writers, so a racing reader observes a complete committed
 * state (old or new) — never a torn mix of two commits across tables. The
 * separate-query `snapshot()` above is correct only while writers are paused.
 */
internal fun consistentSnapshot(dbName: String, context: Context): FullSnapshot {
    val db = Room.databaseBuilder(context, RecordDatabase::class.java, dbName).build()
    try {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("BEGIN")
        return try {
            FullSnapshot(
                entityRefs = sql.query("SELECT * FROM EntityRef").use { rows ->
                    buildSet {
                        while (rows.moveToNext()) {
                            add(
                                EntityRef(
                                    id = rows.string("id"),
                                    entityType = EntityType.valueOf(rows.string("entityType")),
                                    createdAt = rows.long("createdAt"),
                                    updatedAt = rows.long("updatedAt"),
                                    deletedAt = rows.nullableLong("deletedAt"),
                                ),
                            )
                        }
                    }
                },
                transactions = sql.query("SELECT * FROM `Transaction`").use { rows ->
                    buildSet {
                        while (rows.moveToNext()) {
                            add(Transaction(rows.string("id"), TransactionType.valueOf(rows.string("type"))))
                        }
                    }
                },
                accounts = sql.query("SELECT * FROM Account").use { rows ->
                    buildSet {
                        while (rows.moveToNext()) {
                            add(Account(rows.string("id"), rows.string("name"), rows.string("currency")))
                        }
                    }
                },
                accountEntries = sql.query("SELECT * FROM AccountEntry").use { rows ->
                    buildSet {
                        while (rows.moveToNext()) {
                            add(
                                AccountEntry(
                                    id = rows.string("id"),
                                    transactionId = rows.string("transactionId"),
                                    accountId = rows.string("accountId"),
                                    amountMinor = rows.long("amountMinor"),
                                ),
                            )
                        }
                    }
                },
                notes = sql.query("SELECT * FROM Note").use { rows ->
                    buildSet {
                        while (rows.moveToNext()) add(Note(rows.string("id")))
                    }
                },
                relations = sql.query("SELECT * FROM TransactionNoteRelation").use { rows ->
                    buildSet {
                        while (rows.moveToNext()) {
                            add(TransactionNoteRelation(rows.string("transactionId"), rows.string("noteId")))
                        }
                    }
                },
            )
        } finally {
            sql.execSQL("COMMIT")
        }
    } finally {
        db.close()
    }
}

private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

private fun Cursor.nullableLong(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
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
