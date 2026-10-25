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

internal enum class NoteCheckpoint { NOTE_WRITTEN, NOTE_COMMITTED, LIFECYCLE_WRITTEN, DELETE_COMMITTED, RESTORE_COMMITTED }

internal class RoomNoteService(
    private val database: RecordDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val checkpoint: (NoteCheckpoint) -> Unit = {},
) : NoteService {
    internal var dataOperationGate: DataOperationGate = ProcessDataOperationGate.gate
    private val dao = database.records()

    override suspend fun save(generation: String, id: String, expectedVersion: Long?, body: String, occurredAt: Long): NoteRecord? = io {
        val result = write {
            val state = state(generation)
            identity(id)
            val old = dao.noteForLifecycle(id)?.checked()
            if (expectedVersion == null && old == null) {
                if (body.isBlank()) return@write null
                val now = clock.millis()
                if (occurredAt > now) fail(NoteError.Validation)
                dao.insertNote(NoteEntity(id, body, occurredAt, 1, now, now, null))
                advance(state)
                mark(NoteCheckpoint.NOTE_WRITTEN)
            } else {
                val current = old ?: fail(NoteError.NotFound)
                if (current.deletedAt != null) fail(NoteError.NotVisible)
                if (body.isBlank() || occurredAt != current.occurredAt) fail(NoteError.Validation)
                if (expectedVersion == null) {
                    // Retry a lost first-save response without creating another identity.
                    if (current.version != 1L || current.body != body) fail(NoteError.VersionConflict)
                } else {
                    if (expectedVersion < 1 || current.version != expectedVersion) fail(NoteError.VersionConflict)
                    if (current.body != body) {
                        if (dao.updateNoteBody(id, expectedVersion, body, clock.millis()) != 1) fail(NoteError.VersionConflict)
                        advance(state)
                        mark(NoteCheckpoint.NOTE_WRITTEN)
                    }
                }
            }
            dao.noteForLifecycle(id)!!.checked().dto()
        }
        if (result != null) committed(NoteCheckpoint.NOTE_COMMITTED)
        result
    }

    override suspend fun visible(generation: String, id: String): NoteRecord = io {
        database.withTransaction {
            state(generation)
            val note = note(id)
            if (note.deletedAt != null) fail(NoteError.NotVisible)
            note.dto()
        }
    }

    override suspend fun deleted(generation: String, id: String): NoteRecord = io {
        database.withTransaction {
            state(generation)
            val note = note(id)
            if (note.deletedAt == null) fail(NoteError.NotFound)
            note.dto()
        }
    }

    override suspend fun deletedPage(generation: String, limit: Int, after: DeletedNoteCursor?): DeletedNotePage = io {
        database.withTransaction {
            val state = state(generation)
            if (limit !in 1..100) fail(NoteError.Validation)
            after?.let {
                identity(it.id)
                if (it.generation != state.dataGeneration || it.businessRevision != state.businessRevision) fail(NoteError.CursorExpired)
            }
            val rows = dao.deletedNotes(limit + 1, after?.deletedAt, after?.id).map { it.checked().dto() }
            val page = rows.take(limit)
            val next = if (rows.size > limit) page.last().let {
                DeletedNoteCursor(it.deletedAt!!, it.id, state.dataGeneration, state.businessRevision)
            } else null
            DeletedNotePage(page, next)
        }
    }

    override suspend fun delete(generation: String, id: String, expectedVersion: Long): NoteTransition = transition(generation, id, expectedVersion, true)
    override suspend fun restore(generation: String, id: String, expectedVersion: Long): NoteTransition = transition(generation, id, expectedVersion, false)

    private suspend fun transition(generation: String, id: String, expectedVersion: Long, deleted: Boolean): NoteTransition = io {
        val result = write {
            val state = state(generation)
            val old = note(id)
            if (expectedVersion < 1) fail(NoteError.Validation)
            if ((old.deletedAt != null) == deleted) return@write NoteTransition(old.dto(), false)
            if (old.version != expectedVersion) fail(NoteError.VersionConflict)
            val now = clock.millis()
            if (now < old.createdAt) fail(NoteError.Validation)
            val changed = if (deleted) dao.softDeleteNote(id, expectedVersion, now, now) else dao.restoreNote(id, expectedVersion, now)
            if (changed != 1) fail(NoteError.VersionConflict)
            advance(state)
            mark(NoteCheckpoint.LIFECYCLE_WRITTEN)
            NoteTransition(note(id).dto(), true)
        }
        if (result.changed) committed(if (deleted) NoteCheckpoint.DELETE_COMMITTED else NoteCheckpoint.RESTORE_COMMITTED)
        result
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        database.withTransaction { dataOperationGate.withWrite { block() } }

    private fun state(generation: String): RecordDataStateEntity {
        val state = dao.dataState() ?: fail(NoteError.RecoveryRequired)
        if (state.businessRevision < 0 || runCatching { UUID.fromString(state.dataGeneration).toString() }.getOrNull() != state.dataGeneration) fail(NoteError.RecoveryRequired)
        if (state.dataGeneration != generation) fail(NoteError.GenerationMismatch)
        return state
    }

    private fun advance(state: RecordDataStateEntity) {
        if (dao.advanceBusinessRevision(state.businessRevision) != 1) fail(NoteError.VersionConflict)
    }

    private fun identity(id: String) {
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) fail(NoteError.Validation)
    }

    private fun note(id: String): NoteEntity {
        identity(id)
        return (dao.noteForLifecycle(id) ?: fail(NoteError.NotFound)).checked()
    }

    private fun NoteEntity.checked(): NoteEntity {
        if (body.isBlank() || version < 1 || (deletedAt != null && deletedAt < createdAt)) fail(NoteError.RecoveryRequired)
        return this
    }

    private fun NoteEntity.dto() = NoteRecord(id, body, occurredAt, version, createdAt, updatedAt, deletedAt)
    private fun fail(code: NoteError): Nothing = throw NoteException(code)
    private suspend fun mark(point: NoteCheckpoint) { checkpoint(point); currentCoroutineContext().ensureActive() }
    private suspend fun committed(point: NoteCheckpoint) {
        try { mark(point) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw NoteException(NoteError.OutcomeUnknown, error) }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: SQLiteFullException) { throw NoteException(NoteError.StorageFull, error) }
        catch (error: SQLiteException) { throw NoteException(NoteError.RecoverableIO, error) }
    }
}
