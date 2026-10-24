package com.paherdin.record2.storage

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.paherdin.record2.backup.DataOperationGate
import com.paherdin.record2.backup.ProcessDataOperationGate
import com.paherdin.record2.records.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class LinkCheckpoint { LINK_WRITTEN, LINKS_COMMITTED }

internal class RoomLinkService(
    private val database: RecordDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val checkpoint: (LinkCheckpoint, Int) -> Unit = { _, _ -> },
) : LinkService {
    internal var dataOperationGate: DataOperationGate = ProcessDataOperationGate.gate
    private val dao = database.records()

    override suspend fun transactionLinks(generation: String, transactionId: String): TransactionLinks = io {
        database.withTransaction {
            state(generation)
            val transaction = transactionRow(transactionId)
            val notes = dao.visibleNotesForTransaction(transactionId).map { it.checkedNote().dto() }
            TransactionLinks(transaction.dto(), notes, dao.visibleLinkCount(transactionId))
        }
    }

    override suspend fun noteLinks(generation: String, noteId: String): NoteLinks = io {
        database.withTransaction {
            state(generation)
            val note = noteForRead(noteId)
            val transactions = dao.transactionsForNote(noteId).map { it.checkedTransaction().dto() }
            NoteLinks(note.dto(), transactions, transactions.size)
        }
    }

    override suspend fun selection(generation: String, transactionId: String): LinkSelection = io {
        database.withTransaction {
            state(generation)
            val transaction = transactionRow(transactionId)
            val linked = dao.visibleNotesForTransaction(transactionId).map { it.checkedNote().dto() }
            val candidates = dao.visibleUnlinkedNoteCandidates(transactionId).map { it.checkedNote().dto() }
            LinkSelection(generation, transaction.dto(), linked, candidates)
        }
    }

    override suspend fun apply(
        generation: String,
        transactionId: String,
        transactionExpectedVersion: Long,
        add: List<LinkNoteRef>,
        remove: List<LinkNoteRef>,
    ): LinkBatchResult {
        val result = try {
            io {
                write {
                    val state = state(generation)
                    val transaction = transactionRow(transactionId)
                    if (transactionExpectedVersion < 1) fail(LinkError.Validation)
                    if (transaction.version != transactionExpectedVersion) fail(LinkError.VersionConflict)
                    validateRefs(add, remove)
                    val linked = dao.linkedNoteIds(transactionId).toSet()
                    val addRows = add.map { it to noteForLink(it) }
                    val removeRows = remove.map { it to noteForLink(it) }
                    val adds = addRows.filter { (ref, _) -> ref.id !in linked }.map { (_, row) -> row }
                    val removes = removeRows.filter { (ref, _) -> ref.id in linked }.map { (_, row) -> row }
                    val changed = adds.isNotEmpty() || removes.isNotEmpty()
                    val now = clock.millis()
                    if (changed) {
                        var index = 0
                        for (row in adds) {
                            if (dao.touchNote(row.id, row.version, now) != 1) fail(LinkError.VersionConflict)
                            dao.insertLink(TransactionNoteLinkEntity(transactionId, row.id))
                            mark(LinkCheckpoint.LINK_WRITTEN, ++index)
                        }
                        for (row in removes) {
                            if (dao.touchNote(row.id, row.version, now) != 1) fail(LinkError.VersionConflict)
                            if (dao.removeLink(transactionId, row.id) != 1) fail(LinkError.VersionConflict)
                            mark(LinkCheckpoint.LINK_WRITTEN, ++index)
                        }
                        if (dao.touchTransaction(transactionId, transactionExpectedVersion, now) != 1) fail(LinkError.VersionConflict)
                        advance(state)
                    }
                    val updated = transactionRow(transactionId)
                    val notes = dao.visibleNotesForTransaction(transactionId).map { it.checkedNote().dto() }
                    LinkBatchResult(
                        generation = generation,
                        transaction = updated.dto(),
                        notes = notes,
                        visibleLinkCount = dao.visibleLinkCount(transactionId),
                        businessRevision = if (changed) state.businessRevision + 1 else state.businessRevision,
                        changed = changed,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw LinkOutcomeUnknownCancellation(error)
        }
        if (result.changed) {
            try { mark(LinkCheckpoint.LINKS_COMMITTED, 0) }
            catch (error: CancellationException) { throw LinkCommittedCancellation(error) }
            catch (error: Exception) { throw LinkException(LinkError.OutcomeUnknown, error) }
        }
        return result
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        database.withTransaction { dataOperationGate.withWrite { block() } }

    private fun validateRefs(add: List<LinkNoteRef>, remove: List<LinkNoteRef>) {
        val addIds = add.map { it.id }
        val removeIds = remove.map { it.id }
        if (addIds.size != addIds.toSet().size || removeIds.size != removeIds.toSet().size) fail(LinkError.Validation)
        val removed = removeIds.toSet()
        if (addIds.any { it in removed }) fail(LinkError.Validation)
        (addIds + removeIds).forEach(::identity)
        (add + remove).forEach { if (it.version < 1) fail(LinkError.Validation) }
    }

    private fun state(generation: String): RecordDataStateEntity {
        val state = dao.dataState() ?: fail(LinkError.RecoveryRequired)
        if (state.businessRevision < 0 || runCatching { UUID.fromString(state.dataGeneration).toString() }.getOrNull() != state.dataGeneration) fail(LinkError.RecoveryRequired)
        if (state.dataGeneration != generation) fail(LinkError.GenerationMismatch)
        return state
    }

    private fun advance(state: RecordDataStateEntity) {
        if (dao.advanceBusinessRevision(state.businessRevision) != 1) fail(LinkError.VersionConflict)
    }

    private fun identity(id: String) {
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) fail(LinkError.Validation)
    }

    private fun transactionRow(id: String): TransactionEntity {
        identity(id)
        return (dao.transaction(id) ?: fail(LinkError.NotFound)).checkedTransaction()
    }

    private fun noteForRead(id: String): NoteEntity {
        identity(id)
        val note = (dao.noteForLifecycle(id) ?: fail(LinkError.NotFound)).checkedNote()
        if (note.deletedAt != null) fail(LinkError.NotVisible)
        return note
    }

    private fun noteForLink(ref: LinkNoteRef): NoteEntity {
        identity(ref.id)
        if (ref.version < 1) fail(LinkError.Validation)
        val note = (dao.noteForLifecycle(ref.id) ?: fail(LinkError.NotFound)).checkedNote()
        if (note.deletedAt != null) fail(LinkError.NotVisible)
        if (note.version != ref.version) fail(LinkError.VersionConflict)
        return note
    }

    private fun TransactionEntity.checkedTransaction(): TransactionEntity {
        if (currency != "CNY" || amountMinor <= 0 || version < 1) fail(LinkError.RecoveryRequired)
        parseDirection(direction)
        return this
    }

    private fun NoteEntity.checkedNote(): NoteEntity {
        if (body.isBlank() || version < 1 || (deletedAt != null && deletedAt < createdAt)) fail(LinkError.RecoveryRequired)
        return this
    }

    private fun TransactionEntity.dto() = FinanceRecord(id, parseDirection(direction), amountMinor, accountId, categoryId, memo, occurredAt, version, createdAt, updatedAt)
    private fun NoteEntity.dto() = NoteRecord(id, body, occurredAt, version, createdAt, updatedAt, deletedAt)
    private fun parseDirection(value: String) = try { FinanceDirection.valueOf(value) }
    catch (error: IllegalArgumentException) { throw LinkException(LinkError.RecoveryRequired, error) }
    private fun fail(code: LinkError): Nothing = throw LinkException(code)
    private suspend fun mark(point: LinkCheckpoint, index: Int) { checkpoint(point, index); currentCoroutineContext().ensureActive() }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: SQLiteFullException) { throw LinkException(LinkError.StorageFull, error) }
        catch (error: SQLiteException) { throw LinkException(LinkError.RecoverableIO, error) }
    }
}
