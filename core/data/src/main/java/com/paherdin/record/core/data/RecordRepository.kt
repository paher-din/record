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
import com.paherdin.record.core.data.RepositoryResult.Failure.AttachmentCorrupted
import com.paherdin.record.core.data.RepositoryResult.Failure.AttachmentIoFailed
import com.paherdin.record.core.data.RepositoryResult.Failure.AttachmentRejected
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
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Same-type ID already exists; nothing committed. */
private class DuplicateIdentityException : IllegalStateException()

/** A referenced endpoint is missing or wrongly typed; nothing committed. */
private class EndpointRejectedException : IllegalStateException()

/** Hook-originated exception, attributed as a simulated fault. */
internal class HookFaultException(val fromCommitResponse: Boolean) : RuntimeException()

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

    internal constructor(
        database: RecordDatabase,
        hook: TransactionFaultHook = TransactionFaultHook.NO_OP,
        filesDir: File? = null,
        attachmentHook: AttachmentFaultHook = AttachmentFaultHook.NO_OP,
    ) : this() {
        this.database = database
        this.hook = hook
        if (filesDir != null) {
            val io = AttachmentIo(attachmentHook)
            val store = AttachmentContentStore(filesDir, io)
            attachmentIo = io
            attachmentStore = store
            attachmentIngestor = AttachmentIngestor(
                store,
                io,
                attachmentLocks,
                database.recordDao(),
                clock = { System.currentTimeMillis() },
                hook = attachmentHook,
            )
            attachmentLinkManager = AttachmentLinkManager(
                store,
                attachmentLocks,
                database.recordDao(),
                clock = { System.currentTimeMillis() },
                hook = attachmentHook,
            )
            attachmentCleaner = AttachmentCleaner(
                store,
                attachmentLocks,
                database.recordDao(),
                clock = { System.currentTimeMillis() },
                hook = attachmentHook,
            )
        }
    }

    private lateinit var database: RecordDatabase
    private var hook: TransactionFaultHook = TransactionFaultHook.NO_OP
    private val dao: RecordDao get() = database.recordDao()
    private val financeValidator = FinanceMatrixValidator()
    private val attachmentLocks = AttachmentHashLockRegistry()
    private var attachmentIo: AttachmentIo? = null
    private var attachmentStore: AttachmentContentStore? = null
    private var attachmentIngestor: AttachmentIngestor? = null
    private var attachmentLinkManager: AttachmentLinkManager? = null
    private var attachmentCleaner: AttachmentCleaner? = null

    companion object {
        fun open(context: Context): RepositoryResult<RecordRepository> {
            // Coordinator gate (Task 3, ADR 0007): resolve the recovery record and
            // publish a verified snapshot for a v1 live database BEFORE any Room
            // object exists. A blocked or failed gate maps to TransactionFailed;
            // raw JSON/SQLite exceptions never cross the public boundary.
            val coordinator = MigrationCoordinator(
                context,
                MigrationRecoveryRecordStore(recoveryRecordFile(context)),
            )
            val gate = try {
                coordinator.startupGate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MigrationGateException) {
                return TransactionFailed
            } catch (e: Throwable) {
                return Unexpected
            }
            if (gate is Blocked) return TransactionFailed
            val store = MigrationRecoveryRecordStore(recoveryRecordFile(context))
            val result = openWithBuilder(
                context,
                TransactionFaultHook.NO_OP,
                { ctx ->
                    Room.databaseBuilder(ctx, RecordDatabase::class.java, DEFAULT_DB_NAME)
                        .addMigrations(RecordDatabase.MIGRATION_1_2)
                        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                        .build()
                },
            )
            if (result is Success) {
                // Migration/open completed: normalize the recovery record to
                // healthy. A failure here is retried on the next startup (the
                // gate revalidates the still-valid snapshot and Room reopens
                // v2 without migrating).
                try {
                    store.write(MigrationRecord(MigrationRecord.RECORD_VERSION, MigrationRecoveryState.MIGRATION_HEALTHY, null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Best-effort normalization; the next startup retries it.
                }
            }
            return result
        }

        /** The app-private activation/recovery record (data and storage, plan Task 3). */
        internal fun recoveryRecordFile(context: Context): File =
            File(context.filesDir, "recovery/activation-recovery-v1.json")

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
            attachmentHook: AttachmentFaultHook = AttachmentFaultHook.NO_OP,
        ): RepositoryResult<RecordRepository> = try {
            val database = builder(context)
            // Task 3: force the writable database before reporting success so a
            // lazy Room builder cannot report success before migration/open
            // completes (plan Task 3 startup order).
            database.openHelper.writableDatabase
            Success(RecordRepository(database, hook, context.filesDir, attachmentHook))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteFullException) {
            StorageExhausted
        } catch (e: SQLiteException) {
            TransactionFailed
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

    /**
     * Ingests an image file (plan Task 5, spec 4.7): copies to app-private
     * TEMP, validates declared/decoded MIME, size, and decodability, computes
     * SHA-256, deduplicates under per-hash serialization, publishes the blob
     * atomically, and commits the zero-link canonical row with
     * `deletedAt = ingestedAt`. Returns the canonical `contentIdentity` only
     * after the row/blob pair is durably prepared.
     */
    suspend fun ingestAttachment(source: File, declaredMimeType: String): RepositoryResult<String> = try {
        val ingestor = checkNotNull(attachmentIngestor) { "attachment pipeline not assembled" }
        when (val outcome = ingestor.ingest(source, declaredMimeType)) {
            is IngestSuccess -> Success(outcome.contentIdentity)
            is IngestRejected -> AttachmentRejected(outcome.violation)
            is IngestCorrupted -> AttachmentCorrupted(outcome.detail)
            is IngestIoFailed -> AttachmentIoFailed(outcome.stage)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Unexpected
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
                if (tx.attachmentContentIds.isNotEmpty()) {
                    attachmentLinkManager!!.linkAll(tx.attachmentContentIds, tx.id)
                    runHook { hook.afterChildWrite(3 + tx.entries.size) }
                }
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
        } catch (e: AttachmentRejectedException) {
            AttachmentRejected(e.violation)
        } catch (e: AttachmentCorruptedException) {
            AttachmentCorrupted(e.detail)
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
                if (note.attachmentContentIds.isNotEmpty()) {
                    attachmentLinkManager!!.linkAll(note.attachmentContentIds, note.id)
                    runHook { hook.afterChildWrite(3) }
                }
                runHook { hook.beforeCommit() }
            }
            runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
            Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XorViolationException) {
            ValidationRejected(e.violation)
        } catch (e: AttachmentRejectedException) {
            AttachmentRejected(e.violation)
        } catch (e: AttachmentCorruptedException) {
            AttachmentCorrupted(e.detail)
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

    /**
     * Links an existing Transaction/Note owner to canonical content (spec
     * 4.7): under the shared per-hash serialization, rechecks the target,
     * verifies the actual bytes, clears the zero-link cleanup marker, and
     * commits the link in one Room transaction. Unknown content, missing
     * bytes, or corruption rejects with nothing committed; a non-owner
     * endpoint is `EndpointRejected`.
     */
    suspend fun linkAttachment(ownerEntityRefId: String, contentIdentity: String): RepositoryResult<Unit> = try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
            if (!isValidAttachmentOwner(ownerEntityRefId)) throw EndpointRejectedException()
            // Per-hash serialization is global (registry doc): the dedicated
            // link path must hold the same lock as owner-save linking and the
            // cleaner, or the cleaner could delete the blob of a row this link
            // is about to revive.
            attachmentLocks.withLock(contentIdentity) {
                attachmentLinkManager!!.linkOne(contentIdentity, ownerEntityRefId)
            }
            runHook { hook.beforeCommit() }
        }
        runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
        Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AttachmentRejectedException) {
        AttachmentRejected(e.violation)
    } catch (e: AttachmentCorruptedException) {
        AttachmentCorrupted(e.detail)
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

    /**
     * Removes the owner link in one transaction; at zero global link count
     * the canonical Attachment enters cleanup pending (`deletedAt =
     * clock.now()`) in the same transaction. Idempotent: unlinking content
     * or a link that does not exist is a no-op success.
     */
    suspend fun unlinkAttachment(ownerEntityRefId: String, contentIdentity: String): RepositoryResult<Unit> = try {
        runHook { hook.beforeTransaction() }
        db.withTransaction {
            if (!isValidAttachmentOwner(ownerEntityRefId)) throw EndpointRejectedException()
            attachmentLinkManager!!.unlinkOne(contentIdentity, ownerEntityRefId)
            runHook { hook.beforeCommit() }
        }
        runHook(afterCommitResponse = true) { hook.afterCommitResponse() }
        Success(Unit)
    } catch (e: CancellationException) {
        throw e
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

    /** Internal test seam: runs the cleaner and returns its report. */
    internal suspend fun runAttachmentCleanup(): AttachmentCleaner.Report =
        checkNotNull(attachmentCleaner) { "attachment pipeline not assembled" }.run()

    /** The owner endpoint must exist and be a Transaction or Note EntityRef (ADR 0002). */
    private suspend fun isValidAttachmentOwner(ownerEntityRefId: String): Boolean {
        val ref = dao.getEntityRef(ownerEntityRefId) ?: return false
        return when (ref.entityType) {
            EntityType.TRANSACTION -> dao.getTransaction(ownerEntityRefId) != null
            EntityType.NOTE -> dao.getNote(ownerEntityRefId) != null
            EntityType.PROJECT -> false
        }
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
    attachmentHook: AttachmentFaultHook = AttachmentFaultHook.NO_OP,
): RecordRepository = when (val result = RecordRepository.openWithBuilder(
    context,
    hook,
    { ctx ->
        Room.databaseBuilder(ctx, RecordDatabase::class.java, dbName)
            .addMigrations(RecordDatabase.MIGRATION_1_2)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    },
    attachmentHook,
)) {
    is Success -> result.value
    is RepositoryResult.Failure -> error("test open of '$dbName' failed with $result")
}
