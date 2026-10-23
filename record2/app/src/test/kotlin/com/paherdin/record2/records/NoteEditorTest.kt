package com.paherdin.record2.records

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class NoteEditorTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(10)
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun cancelScopes() { scopes.forEach { it.cancel() } }
    private fun editor(service: ControlledNotes, initial: NoteRecord? = service.record): NoteEditor {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return NoteEditor(service, "generation", "note-id", 100, initial, scope)
    }

    @Test fun blankNewNoteLeavesWithoutFactOrFalseSavedStatus() = runBlocking {
        val service = ControlledNotes()
        val editor = editor(service)
        editor.edit(" \n\t")
        assertTrue(editor.leave())
        assertFalse(editor.state.value.isSaved)
        assertTrue(editor.state.value.closed)
        assertEquals(0, service.calls.size)
        assertNull(service.record)
    }

    @Test fun debounceSavesLatestTextOnceAfterQuietPeriod() = runBlocking {
        val service = ControlledNotes()
        val editor = editor(service)
        editor.edit("较早文本")
        editor.edit("最新\n**原样文本**  ")
        withTimeout(4000) {
            while (!editor.state.value.isSaved) delay(10)
        }
        assertEquals(listOf("最新\n**原样文本**  "), service.calls.map { it.body })
        assertEquals(2L, editor.state.value.persistedInputRevision)
        assertEquals(1L, editor.state.value.persisted?.version)
    }

    @Test fun delayedReceiptNeverOverwritesLaterInputOrMarksItSavedEarly() = runBlocking {
        val service = ControlledNotes()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        service.afterWrite = { if (service.calls.size == 1) { entered.complete(Unit); release.await() } }
        val editor = editor(service)
        editor.edit("first")
        val flushing = async { editor.flush() }
        entered.await()
        editor.edit("later")
        assertEquals("later", editor.state.value.body)
        assertFalse(editor.state.value.isSaved)
        release.complete(Unit)
        assertTrue(flushing.await())
        assertEquals(listOf(null, 1L), service.calls.map { it.version })
        assertEquals(listOf("first", "later"), service.calls.map { it.body })
        assertEquals("later", editor.state.value.body)
        assertEquals(2L, editor.state.value.persisted?.version)
        assertTrue(editor.state.value.isSaved)
    }

    @Test fun storageFailureRetainsInputAndRetryUsesLatestTextOnSameIdentity() = runBlocking {
        val service = ControlledNotes()
        service.nextError = NoteError.StorageFull
        val editor = editor(service)
        editor.edit("failed text")
        assertFalse(editor.flush())
        assertEquals("failed text", editor.state.value.body)
        assertNull(editor.state.value.persisted)
        assertFalse(editor.state.value.isSaved)
        editor.edit("newer text")
        assertTrue(editor.flush())
        assertEquals("newer text", service.record?.body)
        assertEquals(1L, service.record?.version)
        assertTrue(service.calls.all { it.id == "note-id" })
    }

    @Test fun conflictPreservesBufferAndDoesNotAdoptVersionUntilExplicitDiscardReload() = runBlocking {
        val initial = NoteRecord("note-id", "old", 100, 5, 100, 100, null)
        val service = ControlledNotes(initial)
        val editor = editor(service)
        service.record = initial.copy(body = "external", version = 6)
        editor.edit("local unsaved")
        assertFalse(editor.flush())
        editor.edit("new local input")
        assertFalse(editor.flush())
        assertEquals("new local input", editor.state.value.body)
        assertEquals(5L, editor.state.value.persisted?.version)
        assertEquals(NoteError.VersionConflict, editor.state.value.error)
        assertEquals(1, service.calls.size)
        assertEquals(0, service.reads)
        assertTrue(editor.reloadDiscardingInput())
        assertEquals("external", editor.state.value.body)
        assertEquals(6L, editor.state.value.persisted?.version)
        assertTrue(editor.state.value.isSaved)
        editor.edit("reviewed edit")
        assertTrue(editor.flush())
        assertEquals(6L, service.calls.last().version)
    }

    @Test fun deletedNoteAndUnknownOutcomeBlockAutomaticRetryWithoutLosingText() = runBlocking {
        for (error in listOf(NoteError.NotVisible, NoteError.OutcomeUnknown)) {
            val service = ControlledNotes(NoteRecord("note-id", "old", 100, 1, 100, 100, null))
            val editor = editor(service)
            service.nextError = error
            editor.edit("unsaved")
            assertFalse(editor.flush())
            editor.edit("new input after failure")
            assertFalse(editor.flush())
            assertEquals("new input after failure", editor.state.value.body)
            assertEquals(1, service.calls.size)
            assertFalse(editor.state.value.isSaved)
        }
    }

    @Test fun leaveWaitsForSaveAndRejectsDuplicateFinishingOrNewInput() = runBlocking {
        val service = ControlledNotes()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        service.afterWrite = { entered.complete(Unit); release.await() }
        val editor = editor(service)
        editor.edit("saved before leave")
        val leaving = async { editor.leave() }
        entered.await()
        assertFalse(editor.edit("too late"))
        assertFalse(editor.leave())
        assertFalse(leaving.isCompleted)
        release.complete(Unit)
        assertTrue(leaving.await())
        assertTrue(editor.state.value.closed)
        assertTrue(editor.state.value.isSaved)
        assertEquals(1, service.calls.size)
    }

    @Test fun explicitDiscardWaitsForInFlightWriteAndNeverDeletesSavedNote() = runBlocking {
        val service = ControlledNotes()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        service.afterWrite = { entered.complete(Unit); release.await() }
        val editor = editor(service)
        editor.edit("in flight")
        val flushing = async { editor.flush() }
        entered.await()
        editor.edit("discard this input")
        val discard = async { editor.discardAndLeave() }
        yield()
        assertFalse(discard.isCompleted)
        release.complete(Unit)
        flushing.await()
        discard.await()
        assertEquals("in flight", service.record?.body)
        assertEquals("in flight", editor.state.value.body)
        assertEquals(1, service.calls.size)
        assertTrue(editor.state.value.closed)
    }

    @Test fun explicitReloadLocksNewInputAndQueuedAutosaveUntilReadCompletes() = runBlocking {
        val initial = NoteRecord("note-id", "disk", 100, 1, 100, 100, null)
        val service = ControlledNotes(initial)
        val editor = editor(service)
        editor.edit("discard me")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        service.beforeRead = { entered.complete(Unit); release.await() }
        val reload = async { editor.reloadDiscardingInput() }
        entered.await()
        assertFalse(editor.edit("new input must not be silently lost"))
        assertFalse(editor.flush())
        assertFalse(editor.leave())
        release.complete(Unit)
        assertTrue(reload.await())
        assertEquals("disk", editor.state.value.body)
        assertTrue(service.calls.isEmpty())
    }

    private data class SaveCall(val id: String, val version: Long?, val body: String)

    private class ControlledNotes(var record: NoteRecord? = null) : NoteService {
        val calls = mutableListOf<SaveCall>()
        var reads = 0
        var nextError: NoteError? = null
        var afterWrite: suspend () -> Unit = {}
        var beforeRead: suspend () -> Unit = {}
        override suspend fun save(generation: String, id: String, expectedVersion: Long?, body: String, occurredAt: Long): NoteRecord? {
            calls += SaveCall(id, expectedVersion, body)
            nextError?.let { nextError = null; throw NoteException(it) }
            val old = record
            if (old?.deletedAt != null) throw NoteException(NoteError.NotVisible)
            if (old?.version != expectedVersion) throw NoteException(NoteError.VersionConflict)
            if (old == null && body.isBlank()) return null
            if (body.isBlank()) throw NoteException(NoteError.Validation)
            val saved = NoteRecord(id, body, occurredAt, (old?.version ?: 0) + 1, 100, 100, null)
            record = saved
            afterWrite()
            return saved
        }
        override suspend fun visible(generation: String, id: String): NoteRecord {
            reads++
            beforeRead()
            val current = record ?: throw NoteException(NoteError.NotFound)
            if (current.deletedAt != null) throw NoteException(NoteError.NotVisible)
            return current
        }
        override suspend fun deleted(generation: String, id: String): NoteRecord = error("Not used by editor")
        override suspend fun deletedPage(generation: String, limit: Int, after: DeletedNoteCursor?): DeletedNotePage = error("Not used by editor")
        override suspend fun delete(generation: String, id: String, expectedVersion: Long): NoteTransition = error("Not used by editor")
        override suspend fun restore(generation: String, id: String, expectedVersion: Long): NoteTransition = error("Not used by editor")
    }
}
