package com.paherdin.record.core.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.paherdin.record.core.data.room.Account
import com.paherdin.record.core.data.room.AccountEntry
import com.paherdin.record.core.data.room.EntityRef
import com.paherdin.record.core.data.room.Note
import com.paherdin.record.core.data.room.RecordDao
import com.paherdin.record.core.data.room.RecordDatabase
import com.paherdin.record.core.data.room.Transaction
import com.paherdin.record.core.data.room.TransactionNoteRelation
import com.paherdin.record.core.data.RepositoryResult.Failure.CommittedButResponseFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.DuplicateIdentity
import com.paherdin.record.core.data.RepositoryResult.Failure.EndpointRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.FinanceRejected
import com.paherdin.record.core.data.RepositoryResult.Failure.StorageExhausted
import com.paherdin.record.core.data.RepositoryResult.Failure.TransactionFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.Unexpected
import com.paherdin.record.core.data.RepositoryResult.Failure.ValidationRejected
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType
import java.io.Closeable
import kotlin.coroutines.cancellation.CancellationException

/** Same-type ID already exists; nothing committed. */
private class DuplicateIdentityException : IllegalStateException()

/** A referenced endpoint is missing or wrongly typed; nothing committed. */
private class EndpointRejectedException : IllegalStateException()

/** Hook-originated exception, attributed as a simulated fault. */
private class HookFaultException(val fromCommitResponse: Boolean) : RuntimeException()

/**
 * The only public write surface of `:core:data` (spec 5.1, ADR 0006).
 * Every write runs one `withTransaction` containing the proactive typed
 * checks (occupancy queries → XOR validator first → same-type duplicate →
 * account endpoints → finance-matrix validation → relation endpoints →
 * ordered child writes → `beforeCommit` last), then `afterCommitResponse`
 * after the durable commit. Catch priority per the plan: cancellation
 * rethrown, validation/finance/duplicate/endpoint rejections, storage full,
 * other SQLite exceptions, hook faults, then anything else — raw exceptions
 * never cross the boundary.
 */
class RecordRepository internal constructor() : Closeable {

    internal constructor(database: RecordDatabase, hook: TransactionFaultHook = TransactionFaultHook.NO_OP) : this() {
        this.database = database
        this.hook = hook
    }

    private lateinit var database: RecordDatabase
    private var hook: TransactionFaultHook = TransactionFaultHook.NO_OP
    private val dao: RecordDao get() = database.recordDao()
    private val financeValidator = FinanceMatrixValidator()

    companion object {
        fun open(context: Context): RepositoryResult<RecordRepository> =
            openWithBuilder(context, TransactionFaultHook.NO_OP) { ctx ->
                Room.databaseBuilder(ctx, RecordDatabase::class.java, DEFAULT_DB_NAME)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
            }

        /**
         * Internal unified mapping function: the typed `open()` boundary is
         * runtime-verified here. `CancellationException` is rethrown; any
         * other construction-stage exception (Room builder configuration
         * errors) maps to [RepositoryResult.Failure.Unexpected]. The database
         * file is created lazily by Room; storage failures belong to the typed
         * read/write methods.
         */
        internal fun openWithBuilder(
            context: Context,
            hook: TransactionFaultHook,
            builder: (Context) -> RecordDatabase,
        ): RepositoryResult<RecordRepository> = try {
            Success(RecordRepository(builder(context), hook))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Unexpected
        }

        private const val DEFAULT_DB_NAME = "record.db"
    }


    override fun close() {
        if (::database.isInitialized) {
            database.close()
        }
    }

    suspend fun createAccount(account: NewAccount): RepositoryResult<Unit> = try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
                if (dao.getAccount(account.id) != null) throw DuplicateIdentityException()
                dao.insertAccount(Account(account.id, account.name, account.currency))
                runHook { hook.afterChildWrite(0) }
                runHook { hook.beforeCommit() }
            }
            runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XorViolationException) {
            ValidationRejected(e.violation)
        } catch (e: DuplicateIdentityException) {
            DuplicateIdentity
        } catch (e: EndpointRejectedException) {
            EndpointRejected
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
        } catch (e: HookFaultException) {
            if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
        } catch (e: Throwable) {
            Unexpected
        }

    suspend fun saveTransactionAggregate(tx: NewTransaction): RepositoryResult<Unit> = try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
                val transactionExists = dao.getTransaction(tx.id) != null
                val noteExists = dao.getNote(tx.id) != null
                // XOR validator first, on the candidate occupancy this write produces.
                XorInvariantValidator.validate(EntityType.TRANSACTION, true, noteExists)
                if (transactionExists || dao.getEntityRef(tx.id) != null) throw DuplicateIdentityException()
                // Step 4: resolve every entry account endpoint (deduplicated) and
                // build the stored-currency map; any missing account is EndpointRejected
                // before any finance classification.
                val currencyByAccountId = tx.entries.map { it.accountId }.distinct().associateWith { accountId ->
                    dao.getAccount(accountId)?.currency ?: throw EndpointRejectedException()
                }
                // Step 5: finance-matrix validation with deterministic precedence.
                financeValidator.validate(tx.type, tx.entries, currencyByAccountId)
                if (tx.relationNoteIds.any { dao.getNote(it) == null }) throw EndpointRejectedException()
                dao.insertEntityRef(EntityRef(tx.id, EntityType.TRANSACTION, tx.createdAt, tx.updatedAt, tx.deletedAt))
                runHook { hook.afterChildWrite(0) }
                dao.insertTransaction(Transaction(tx.id, tx.type))
                runHook { hook.afterChildWrite(1) }
                // Step 7: deterministic entry order. After validation a transfer has
                // exactly one negative and one positive amount, so sorting ascending
                // writes the negative source at ordinal 2 before the positive
                // destination at ordinal 3 regardless of input iteration order.
                // Single-entry types write their one entry at ordinal 2, keeping the
                // P1 ordinals; the relation stage follows at 2 + entries.size.
                tx.entries.sortedBy { it.amountMinor }.forEachIndexed { index, entry ->
                    dao.insertAccountEntry(AccountEntry(entry.id, tx.id, entry.accountId, entry.amountMinor))
                    runHook { hook.afterChildWrite(index + 2) }
                }
                if (tx.relationNoteIds.isNotEmpty()) {
                    dao.insertAllRelations(tx.relationNoteIds.map { TransactionNoteRelation(tx.id, it) })
                }
                runHook { hook.afterChildWrite(2 + tx.entries.size) }
                runHook { hook.beforeCommit() }
            }
            runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XorViolationException) {
            ValidationRejected(e.violation)
        } catch (e: FinanceViolationException) {
            FinanceRejected(e.violation)
        } catch (e: DuplicateIdentityException) {
            DuplicateIdentity
        } catch (e: EndpointRejectedException) {
            EndpointRejected
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
        } catch (e: HookFaultException) {
            if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
        } catch (e: Throwable) {
            Unexpected
        }

    suspend fun saveNoteAggregate(note: NewNote): RepositoryResult<Unit> = try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
                val transactionExists = dao.getTransaction(note.id) != null
                val noteExists = dao.getNote(note.id) != null
                XorInvariantValidator.validate(EntityType.NOTE, transactionExists, true)
                if (noteExists || dao.getEntityRef(note.id) != null) throw DuplicateIdentityException()
                if (note.relationTransactionIds.any { dao.getTransaction(it) == null }) throw EndpointRejectedException()
                dao.insertEntityRef(EntityRef(note.id, EntityType.NOTE, note.createdAt, note.updatedAt, note.deletedAt))
                runHook { hook.afterChildWrite(0) }
                dao.insertNote(Note(note.id))
                runHook { hook.afterChildWrite(1) }
                if (note.relationTransactionIds.isNotEmpty()) {
                    dao.insertAllRelations(note.relationTransactionIds.map { TransactionNoteRelation(it, note.id) })
                }
                runHook { hook.afterChildWrite(2) }
                runHook { hook.beforeCommit() }
            }
            runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XorViolationException) {
            ValidationRejected(e.violation)
        } catch (e: DuplicateIdentityException) {
            DuplicateIdentity
        } catch (e: EndpointRejectedException) {
            EndpointRejected
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
        } catch (e: HookFaultException) {
            if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
        } catch (e: Throwable) {
            Unexpected
        }

    suspend fun replaceTransactionNoteRelations(transactionId: String, noteIds: Set<String>): RepositoryResult<Unit> = try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
                if (dao.getTransaction(transactionId) == null) throw EndpointRejectedException()
                if (noteIds.any { dao.getNote(it) == null }) throw EndpointRejectedException()
                dao.deleteRelationsForTransaction(transactionId)
                runHook { hook.afterChildWrite(0) }
                if (noteIds.isNotEmpty()) {
                    dao.insertAllRelations(noteIds.map { TransactionNoteRelation(transactionId, it) })
                }
                runHook { hook.afterChildWrite(1) }
                runHook { hook.beforeCommit() }
            }
            runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XorViolationException) {
            ValidationRejected(e.violation)
        } catch (e: DuplicateIdentityException) {
            DuplicateIdentity
        } catch (e: EndpointRejectedException) {
            EndpointRejected
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
        } catch (e: HookFaultException) {
            if (e.fromCommitResponse) CommittedButResponseFailed else TransactionFailed
        } catch (e: Throwable) {
            Unexpected
        }

    suspend fun getTransactionView(id: String): RepositoryResult<TransactionView?> = try {
        db.withTransaction {
            val ref = dao.getEntityRef(id)
            val row = dao.getTransactionWithEntries(id)
            if (ref == null || row == null) {
                null
            } else {
                TransactionView(
                    id = row.transaction.id,
                    type = row.transaction.type,
                    createdAt = ref.createdAt,
                    updatedAt = ref.updatedAt,
                    deletedAt = ref.deletedAt,
                    entries = row.entries.map { NewAccountEntry(it.id, it.accountId, it.amountMinor) },
                    relationNoteIds = dao.getRelationsForTransaction(id).map { it.noteId }.toSet(),
                )
            }
        }.let { Success(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SQLiteFullException) {
        StorageExhausted
    } catch (e: Throwable) {
        Unexpected
    }

    suspend fun getNoteView(id: String): RepositoryResult<NoteView?> = try {
        db.withTransaction {
            val ref = dao.getEntityRef(id)
            if (ref == null || dao.getNote(id) == null) {
                null
            } else {
                NoteView(
                    id = id,
                    createdAt = ref.createdAt,
                    updatedAt = ref.updatedAt,
                    deletedAt = ref.deletedAt,
                    relationTransactionIds = dao.getRelationsForNote(id).map { it.transactionId }.toSet(),
                )
            }
        }.let { Success(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SQLiteFullException) {
        StorageExhausted
    } catch (e: Throwable) {
        Unexpected
    }

    private fun runHook(afterCommitResponse: Boolean = false, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw HookFaultException(afterCommitResponse)
        }
    }

    private inline val db: RecordDatabase get() = database
}

/**
 * Internal test factory: same unified mapping function as the public `open()`,
 * with an injectable test builder and fault hook on an isolated `dbName`.
 */
internal fun openForTest(
    context: Context,
    dbName: String,
    hook: TransactionFaultHook = TransactionFaultHook.NO_OP,
): RecordRepository = when (val result = RecordRepository.openWithBuilder(context, hook) { ctx ->
    Room.databaseBuilder(ctx, RecordDatabase::class.java, dbName)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()
}) {
    is Success -> result.value
    is RepositoryResult.Failure -> error("test open of '$dbName' failed with $result")
}
