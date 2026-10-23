package com.paherdin.record2.storage

import android.database.sqlite.SQLiteFullException
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.*
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NoteServiceContractTest {
    private val name = "b2-contract-${schemaId()}.db"
    private val path get() = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)
    private lateinit var database: RecordDatabase
    private lateinit var notes: RoomNoteService
    private lateinit var generation: String

    @Before fun open() = runBlocking {
        check(!path.exists())
        database = noteDatabase(name)
        generation = RoomFinanceService(database, noteClock).initialize().generation
        notes = RoomNoteService(database, noteClock)
    }
    @After fun close() { database.close() }
    private fun snapshot() = SchemaDiskObserver.snapshot(path)
    private fun rows(table: String) = snapshot().getValue(table)
    private fun reopen() { database.close(); database = noteDatabase(name); notes = RoomNoteService(database, noteClock) }
    private suspend fun create(id: String = schemaId(), body: String = noteOriginalBody) = notes.save(generation, id, null, body, noteOccurredAt)!!

    @Test fun blankNewNeverCreatesFactAndRawTextPersistsOnOneIdentityAcrossReopen() = runBlocking {
        val id = schemaId()
        val empty = snapshot()
        for (body in listOf("", "  ", "\n\t")) assertNull(notes.save(generation, id, null, body, noteOccurredAt))
        assertEquals(empty, snapshot())
        val note = create(id)
        assertEquals(listOf(id, noteOriginalBody, noteOccurredAt, 1L, noteClock.millis(), noteClock.millis(), null), rows("note").single())
        assertEquals(note, notes.save(generation, id, null, noteOriginalBody, noteOccurredAt))
        val saved = notes.save(generation, id, 1, noteUpdatedBody, noteOccurredAt)!!
        assertEquals(2L, saved.version)
        assertEquals(note.createdAt, saved.createdAt)
        assertEquals(note.occurredAt, saved.occurredAt)
        val before = snapshot()
        reopen()
        assertEquals(saved, notes.visible(generation, id))
        assertEquals(before, snapshot())
        assertTrue(rows("native_finance_draft").isEmpty())
    }

    @Test fun invalidEmptyEditsFutureIdentityAndOldVersionNeverModifyFacts() = runBlocking {
        val note = create()
        val before = snapshot()
        for (body in listOf("", "\n \t")) expectNoteError(NoteError.Validation) { notes.save(generation, note.id, 1, body, noteOccurredAt) }
        expectNoteError(NoteError.Validation) { notes.save(generation, "not-an-id", null, "body", noteOccurredAt) }
        expectNoteError(NoteError.Validation) { notes.save(generation, schemaId(), null, "future", noteClock.millis() + 1) }
        expectNoteError(NoteError.Validation) { notes.save(generation, note.id, 1, "body", noteOccurredAt + 1) }
        expectNoteError(NoteError.NotFound) { notes.save(generation, schemaId(), 1, "body", noteOccurredAt) }
        expectNoteError(NoteError.VersionConflict) { notes.save(generation, note.id, 2, "body", noteOccurredAt) }
        expectNoteError(NoteError.VersionConflict) { notes.save(generation, note.id, null, "different create", noteOccurredAt) }
        expectNoteError(NoteError.GenerationMismatch) { notes.save(schemaId(), note.id, 1, "body", noteOccurredAt) }
        assertEquals(before, snapshot())
    }

    @Test fun deleteUndoRestoreKeepIdentityOriginalTimeBodyAndAllHiddenRelations() = runBlocking {
        val note = create()
        val transactionId = schemaId()
        database.records().insertTransaction(TransactionEntity(transactionId, "EXPENSE", 5800, "CNY", FinanceSeeds.ACCOUNT_ID, null, "fixture", noteOccurredAt, 1, noteClock.millis(), noteClock.millis()))
        database.records().insertLink(TransactionNoteLinkEntity(transactionId, note.id))
        val before = snapshot()
        val deleted = notes.delete(generation, note.id, 1)
        assertTrue(deleted.changed)
        assertEquals(2L, deleted.note.version)
        assertEquals(noteClock.millis(), deleted.note.deletedAt)
        assertEquals(note.body, deleted.note.body)
        assertEquals(noteOccurredAt, deleted.note.occurredAt)
        expectNoteError(NoteError.NotVisible) { notes.visible(generation, note.id) }
        assertNull(database.records().visibleNote(note.id))
        val afterDelete = snapshot()
        assertEquals(before - setOf("note", "record_data_state"), afterDelete - setOf("note", "record_data_state"))
        assertFalse(notes.delete(generation, note.id, 1).changed)
        assertEquals(afterDelete, snapshot())
        val restored = notes.restore(generation, note.id, 2)
        assertTrue(restored.changed)
        assertNull(restored.note.deletedAt)
        assertEquals(3L, restored.note.version)
        assertEquals(note.body, notes.visible(generation, note.id).body)
        assertFalse(notes.restore(generation, note.id, 2).changed)
        val afterRestore = snapshot()
        expectNoteError(NoteError.VersionConflict) { notes.delete(generation, note.id, 1) }
        assertEquals(afterRestore, snapshot())
        assertEquals(before["transaction_note_link"], rows("transaction_note_link"))
        assertEquals(before["record_transaction"], rows("record_transaction"))
        reopen()
        assertEquals(restored.note, notes.visible(generation, note.id))
    }

    @Test fun deletedListIsStablePagedAndSeparateFromNormalAccess() = runBlocking {
        val ids = List(5) { create().id }.sorted()
        val normal = create()
        for (id in ids) notes.delete(generation, id, 1)
        val found = mutableListOf<String>()
        var cursor: DeletedNoteCursor? = null
        do {
            val page = notes.deletedPage(generation, 2, cursor)
            found += page.notes.map { it.id }
            cursor = page.next
        } while (cursor != null)
        assertEquals(ids, found)
        assertEquals(noteOriginalBody, notes.deleted(generation, ids.first()).body)
        expectNoteError(NoteError.NotFound) { notes.deleted(generation, normal.id) }
        expectNoteError(NoteError.Validation) { notes.deletedPage(generation, 0) }
        expectNoteError(NoteError.Validation) { notes.deletedPage(generation, 101) }
        assertEquals(normal, notes.visible(generation, normal.id))
    }

    @Test fun twoDatabaseConnectionsCompeteWithOneBusinessCasWinner() = runBlocking {
        val note = create()
        val second = noteDatabase(name)
        try {
            val other = RoomNoteService(second, noteClock)
            val outcomes = coroutineScope {
                listOf(async { runCatching { notes.save(generation, note.id, 1, "A", noteOccurredAt) } },
                    async { runCatching { other.save(generation, note.id, 1, "B", noteOccurredAt) } }).awaitAll()
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(NoteError.VersionConflict, (outcomes.single { it.isFailure }.exceptionOrNull() as NoteException).code)
            val actual = notes.visible(generation, note.id)
            assertEquals(2L, actual.version)
            assertTrue(actual.body in setOf("A", "B"))
            assertEquals(1, rows("note").size)
        } finally { second.close() }
    }

    @Test fun deletedPageRejectsOldSnapshotAfterAnotherConnectionRestoresAndDeletes() = runBlocking {
        val ids = List(3) { create().id }
        for ((index, id) in ids.withIndex()) {
            RoomNoteService(database, Clock.offset(noteClock, Duration.ofSeconds(index.toLong()))).delete(generation, id, 1)
        }
        val first = notes.deletedPage(generation, 1)
        assertEquals(listOf(ids[2]), first.notes.map { it.id })
        val cursor = first.next!!
        assertEquals(listOf(ids[1]), notes.deletedPage(generation, 1, cursor).notes.map { it.id })
        expectNoteError(NoteError.CursorExpired) { notes.deletedPage(generation, 1, cursor.copy(generation = schemaId())) }
        val second = noteDatabase(name)
        try {
            val other = RoomNoteService(second, Clock.offset(noteClock, Duration.ofSeconds(3)))
            other.restore(generation, ids[1], 2)
            other.delete(generation, ids[1], 3)
            val beforeRead = snapshot()
            expectNoteError(NoteError.CursorExpired) { notes.deletedPage(generation, 1, cursor) }
            assertEquals(beforeRead, snapshot())
            val refreshed = notes.deletedPage(generation, 1)
            assertEquals(listOf(ids[1]), refreshed.notes.map { it.id })
            val rest = notes.deletedPage(generation, 2, refreshed.next)
            assertEquals(listOf(ids[2], ids[0]), rest.notes.map { it.id })
            assertNull(rest.next)
        } finally { second.close() }
    }

    @Test fun lateSaveCannotResurrectDeletedOrOverwriteRestoredNote() = runBlocking {
        val note = create()
        notes.delete(generation, note.id, 1)
        val deleted = snapshot()
        expectNoteError(NoteError.NotVisible) { notes.save(generation, note.id, 1, "late", noteOccurredAt) }
        assertEquals(deleted, snapshot())
        notes.restore(generation, note.id, 2)
        val restored = snapshot()
        expectNoteError(NoteError.VersionConflict) { notes.save(generation, note.id, 1, "late", noteOccurredAt) }
        assertEquals(restored, snapshot())
        val saved = notes.save(generation, note.id, 3, noteUpdatedBody, noteOccurredAt)!!
        expectNoteError(NoteError.VersionConflict) { notes.delete(generation, note.id, 3) }
        assertEquals(saved, notes.visible(generation, note.id))
    }

    private suspend fun rollback(point: NoteCheckpoint, action: suspend (NoteService) -> Unit) {
        val before = snapshot()
        repeat(2) {
            val failing = RoomNoteService(database, noteClock, checkpoint = { if (it == point) throw SQLiteFullException("Synthetic disk full") })
            expectNoteError(NoteError.StorageFull) { action(failing) }
            assertEquals(before, snapshot())
        }
        reopen()
        assertEquals(before, snapshot())
    }

    @Test fun createEditDeleteRestoreFailuresRollbackAndRestartRetryKeepsAllFacts() = runBlocking {
        val id = schemaId()
        rollback(NoteCheckpoint.NOTE_WRITTEN) { it.save(generation, id, null, noteOriginalBody, noteOccurredAt) }
        val note = create(id)
        rollback(NoteCheckpoint.NOTE_WRITTEN) { it.save(generation, id, 1, noteUpdatedBody, noteOccurredAt) }
        assertEquals(note, notes.visible(generation, id))
        rollback(NoteCheckpoint.LIFECYCLE_WRITTEN) { it.delete(generation, id, 1) }
        notes.delete(generation, id, 1)
        rollback(NoteCheckpoint.LIFECYCLE_WRITTEN) { it.restore(generation, id, 2) }
        val restored = notes.restore(generation, id, 2)
        assertEquals(3L, restored.note.version)
        assertEquals(noteOriginalBody, restored.note.body)
    }

    private suspend fun cancel(point: NoteCheckpoint, action: suspend (NoteService) -> Unit) = coroutineScope {
        val before = snapshot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelling = RoomNoteService(database, noteClock, checkpoint = {
            if (it == point) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        })
        val job = launch { action(cancelling) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            assertEquals(before, snapshot())
            job.cancel()
        } finally { release.countDown() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, snapshot())
    }

    @Test fun realCoroutineCancellationRollsBackEveryNoteMutation() = runBlocking {
        val id = schemaId()
        cancel(NoteCheckpoint.NOTE_WRITTEN) { it.save(generation, id, null, noteOriginalBody, noteOccurredAt) }
        create(id)
        cancel(NoteCheckpoint.NOTE_WRITTEN) { it.save(generation, id, 1, noteUpdatedBody, noteOccurredAt) }
        cancel(NoteCheckpoint.LIFECYCLE_WRITTEN) { it.delete(generation, id, 1) }
        notes.delete(generation, id, 1)
        cancel(NoteCheckpoint.LIFECYCLE_WRITTEN) { it.restore(generation, id, 2) }
        assertEquals(2L, notes.deleted(generation, id).version)
    }

    @Test fun unknownReceiptsAreReconciledByIdentityAndLifecycleRetriesAreTargets() = runBlocking {
        val id = schemaId()
        val failing = RoomNoteService(database, noteClock, checkpoint = {
            if (it in setOf(NoteCheckpoint.NOTE_COMMITTED, NoteCheckpoint.DELETE_COMMITTED, NoteCheckpoint.RESTORE_COMMITTED)) throw IllegalStateException("Synthetic lost receipt")
        })
        expectNoteError(NoteError.OutcomeUnknown) { failing.save(generation, id, null, noteOriginalBody, noteOccurredAt) }
        val first = notes.visible(generation, id)
        assertEquals(first, notes.save(generation, id, null, noteOriginalBody, noteOccurredAt))
        expectNoteError(NoteError.OutcomeUnknown) { failing.save(generation, id, 1, noteUpdatedBody, noteOccurredAt) }
        val edited = notes.visible(generation, id)
        assertEquals(2L, edited.version)
        expectNoteError(NoteError.VersionConflict) { notes.save(generation, id, 1, noteUpdatedBody, noteOccurredAt) }
        expectNoteError(NoteError.OutcomeUnknown) { failing.delete(generation, id, 2) }
        val deleted = snapshot()
        assertFalse(notes.delete(generation, id, 2).changed)
        assertEquals(deleted, snapshot())
        expectNoteError(NoteError.OutcomeUnknown) { failing.restore(generation, id, 3) }
        val restored = snapshot()
        assertFalse(notes.restore(generation, id, 3).changed)
        assertEquals(restored, snapshot())
        assertEquals(4L, notes.visible(generation, id).version)
        assertEquals(1, rows("note").size)
    }

    @Test fun versionAndGenerationBoundariesRejectWithoutChangingBodyOrDeletionState() = runBlocking {
        val note = create()
        database.openHelper.writableDatabase.execSQL("UPDATE note SET version=? WHERE id=?", arrayOf<Any>(Long.MAX_VALUE, note.id))
        val full = snapshot()
        expectNoteError(NoteError.VersionConflict) { notes.save(generation, note.id, Long.MAX_VALUE, "overflow", noteOccurredAt) }
        expectNoteError(NoteError.VersionConflict) { notes.delete(generation, note.id, Long.MAX_VALUE) }
        assertEquals(full, snapshot())
        assertEquals(Long.MAX_VALUE, notes.save(generation, note.id, Long.MAX_VALUE, noteOriginalBody, noteOccurredAt)?.version)
        database.openHelper.writableDatabase.execSQL("UPDATE note SET version=1 WHERE id=?", arrayOf(note.id))
        database.openHelper.writableDatabase.execSQL("UPDATE record_data_state SET businessRevision=?", arrayOf(Long.MAX_VALUE))
        val revisionFull = snapshot()
        expectNoteError(NoteError.VersionConflict) { notes.delete(generation, note.id, 1) }
        assertEquals(revisionFull, snapshot())
        expectNoteError(NoteError.GenerationMismatch) { notes.visible(schemaId(), note.id) }
        assertEquals(revisionFull, snapshot())
    }

    @Test fun nativeEditorPersistsLatestAndConflictRetainsBufferUntilExplicitReload() = runBlocking {
        val id = schemaId()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val editor = NoteEditor(notes, generation, id, noteOccurredAt, null, scope)
            editor.edit("first")
            editor.edit(noteOriginalBody)
            withTimeout(5000) { while (!editor.state.value.isSaved) delay(20) }
            assertEquals(1, rows("note").size)
            val first = notes.visible(generation, id)
            notes.save(generation, id, first.version, "external", noteOccurredAt)
            editor.edit("local text must survive")
            assertFalse(editor.flush())
            assertEquals(NoteError.VersionConflict, editor.state.value.error)
            assertEquals("local text must survive", editor.state.value.body)
            assertEquals(first.version, editor.state.value.persisted?.version)
            assertFalse(editor.leave())
            assertEquals("external", notes.visible(generation, id).body)
            assertTrue(editor.reloadDiscardingInput())
            assertEquals("external", editor.state.value.body)
            assertTrue(editor.state.value.isSaved)
        } finally { scope.cancel() }
    }

    @Test fun deleteWhileAnOldEditorExistsNeverRecreatesOrClearsItsUnsavedText() = runBlocking {
        val note = create()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val editor = NoteEditor(notes, generation, note.id, noteOccurredAt, note, scope)
            notes.delete(generation, note.id, 1)
            editor.edit("unsaved late text")
            val before = snapshot()
            assertFalse(editor.flush())
            assertEquals(NoteError.NotVisible, editor.state.value.error)
            assertEquals("unsaved late text", editor.state.value.body)
            assertFalse(editor.reloadDiscardingInput())
            assertEquals("unsaved late text", editor.state.value.body)
            assertEquals(before, snapshot())
        } finally { scope.cancel() }
    }
}
