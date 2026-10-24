package com.paherdin.record2.records

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinkSelectionEditorTest {
    private val linked = NoteRecord("n1", "linked note", 100, 2, 100, 100, null)
    private val candidateA = NoteRecord("n2", "candidate a", 200, 4, 100, 100, null)
    private val candidateB = NoteRecord("n3", "candidate b", 300, 1, 100, 100, null)
    private val transaction = FinanceRecord("t1", FinanceDirection.EXPENSE, 5800, "account", null, "memo", 100, 7, 100, 100)

    private fun initial() = LinkSelection("generation", transaction, listOf(linked), listOf(candidateA, candidateB))

    private fun service(links: LinkSelection = initial()) = ControlledLinks(links)

    @Test fun cancelKeepsSelectionWithoutAnyServiceWrite() = runBlocking {
        val service = service()
        val editor = LinkSelectionEditor(service, "generation", "t1", service.links)
        assertTrue(editor.toggle("n2"))
        assertTrue(editor.cancel())
        assertTrue(editor.state.value.closed)
        assertFalse(editor.complete())
        assertTrue(service.calls.isEmpty())
        assertEquals(setOf("n1", "n2"), editor.state.value.selected)
    }

    @Test fun unchangedCompletionSendsEmptyDeltaAndKeepsVersions() = runBlocking {
        val service = service()
        service.result = LinkBatchResult("generation", transaction, listOf(linked), 1, 12, false)
        val editor = LinkSelectionEditor(service, "generation", "t1", service.links)
        assertTrue(editor.complete())
        assertEquals(listOf(ApplyCall(7, emptyList(), emptyList())), service.calls)
        assertTrue(editor.state.value.completed)
        assertEquals(7L, editor.state.value.transactionVersion)
        assertEquals(setOf("n1"), editor.state.value.notes.keys)
        assertFalse(editor.complete())
        assertFalse(editor.reloadDiscardingSelection())
        assertEquals(1, service.calls.size)
    }

    @Test fun incrementalCompletionUsesBaselineVersionsForOnlyChangedNotes() = runBlocking {
        val service = service()
        service.result = LinkBatchResult(
            "generation",
            transaction.copy(version = 8),
            listOf(candidateA.copy(version = 5), candidateB.copy(version = 2)),
            2,
            13,
            true,
        )
        val editor = LinkSelectionEditor(service, "generation", "t1", service.links)
        assertTrue(editor.toggle("n1"))
        assertTrue(editor.toggle("n2"))
        assertTrue(editor.toggle("n3"))
        assertTrue(editor.complete())
        assertEquals(
            listOf(ApplyCall(7, listOf(LinkNoteRef("n2", 4), LinkNoteRef("n3", 1)), listOf(LinkNoteRef("n1", 2)))),
            service.calls,
        )
        assertTrue(editor.state.value.completed)
        assertEquals(8L, editor.state.value.transactionVersion)
        assertEquals(setOf("n2", "n3"), editor.state.value.selected)
        assertEquals(setOf("n2", "n3"), editor.state.value.notes.keys)
        assertEquals(5L, editor.state.value.notes.getValue("n2").version)
        assertEquals(2L, editor.state.value.notes.getValue("n3").version)
    }

    @Test fun failedCompletionPreservesSelectionAndBlocksUntilExplicitReload() = runBlocking {
        val service = service()
        val editor = LinkSelectionEditor(service, "generation", "t1", service.links)
        assertTrue(editor.toggle("n2"))
        service.nextError = LinkError.VersionConflict
        assertFalse(editor.complete())
        assertEquals(LinkError.VersionConflict, editor.state.value.error)
        assertTrue(editor.state.value.requiresReload)
        assertEquals(setOf("n1", "n2"), editor.state.value.selected)
        assertFalse(editor.toggle("n3"))
        assertFalse(editor.complete())
        assertEquals(1, service.calls.size)

        service.links = LinkSelection(
            "generation",
            transaction.copy(version = 9),
            listOf(candidateA.copy(version = 5)),
            listOf(candidateB.copy(version = 2), linked.copy(version = 3)),
        )
        assertTrue(editor.reloadDiscardingSelection())
        assertEquals(setOf("n2"), editor.state.value.selected)
        assertEquals(9L, editor.state.value.transactionVersion)
        assertNull(editor.state.value.error)
        assertFalse(editor.state.value.requiresReload)
        assertEquals(1, service.selectionReads)
    }

    @Test fun mismatchedServiceResultIsRecoveryFailureAndIsNotAdopted() = runBlocking {
        val service = service()
        service.result = LinkBatchResult("other-generation", transaction, listOf(linked), 1, 12, true)
        val editor = LinkSelectionEditor(service, "generation", "t1", service.links)
        assertFalse(editor.complete())
        assertEquals(LinkError.RecoveryRequired, editor.state.value.error)
        assertTrue(editor.state.value.requiresReload)
        assertFalse(editor.state.value.completed)
        assertEquals(setOf("n1"), editor.state.value.selected)
        assertEquals(1, service.calls.size)
    }

    @Test fun cancellationWithUnknownOutcomeRequiresReloadAndKeepsSelection() = runBlocking {
        val cancellations = listOf(
            LinkCommittedCancellation(CancellationException("synthetic committed")),
            LinkOutcomeUnknownCancellation(CancellationException("synthetic rollback-or-commit")),
        )
        for (cancellation in cancellations) {
            val service = service()
            val editor = LinkSelectionEditor(service, "generation", "t1", service.links)
            assertTrue(editor.toggle("n2"))
            service.nextCancellation = cancellation
            val failure = runCatching { editor.complete() }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertEquals(LinkError.OutcomeUnknown, editor.state.value.error)
            assertTrue(editor.state.value.requiresReload)
            assertFalse(editor.state.value.completed)
            assertEquals(setOf("n1", "n2"), editor.state.value.selected)
            assertFalse(editor.toggle("n3"))
            assertFalse(editor.complete())
            assertEquals(1, service.calls.size)
        }
    }

    @Test fun reloadRejectsInvalidServiceStateAndKeepsCurrentSelection() = runBlocking {
        val service = service()
        val editor = LinkSelectionEditor(service, "generation", "t1", service.links)
        assertTrue(editor.toggle("n2"))
        val valid = service.links
        val invalid = listOf(
            valid.copy(generation = "other"),
            valid.copy(transaction = valid.transaction.copy(id = "other")),
            valid.copy(transaction = valid.transaction.copy(version = 0)),
            valid.copy(linked = listOf(linked.copy(version = 0))),
            valid.copy(linked = listOf(linked.copy(deletedAt = 100))),
            valid.copy(candidates = listOf(candidateA.copy(deletedAt = 100))),
            valid.copy(linked = listOf(linked, linked)),
            valid.copy(candidates = listOf(linked)),
        )
        for (candidate in invalid) {
            service.links = candidate
            assertFalse(editor.reloadDiscardingSelection())
            assertEquals(LinkError.RecoveryRequired, editor.state.value.error)
            assertTrue(editor.state.value.requiresReload)
            assertEquals(setOf("n1", "n2"), editor.state.value.selected)
        }
        service.links = valid
        assertTrue(editor.reloadDiscardingSelection())
        assertEquals(setOf("n1"), editor.state.value.selected)
        assertNull(editor.state.value.error)
    }

    @Test fun hiddenTargetFailureKeepsTheCurrentSelectionForRetryAfterReload() = runBlocking {
        val service = service()
        val editor = LinkSelectionEditor(service, "generation", "t1", service.links)
        assertTrue(editor.toggle("n2"))
        service.nextError = LinkError.NotVisible
        assertFalse(editor.complete())
        assertEquals(LinkError.NotVisible, editor.state.value.error)
        assertEquals(setOf("n1", "n2"), editor.state.value.selected)
        assertTrue(editor.state.value.requiresReload)
        assertFalse(editor.toggle("n3"))
    }

    private data class ApplyCall(val transactionVersion: Long, val add: List<LinkNoteRef>, val remove: List<LinkNoteRef>)

    private class ControlledLinks(var links: LinkSelection) : LinkService {
        val calls = mutableListOf<ApplyCall>()
        var selectionReads = 0
        var nextError: LinkError? = null
        var nextCancellation: CancellationException? = null
        var result = LinkBatchResult(links.generation, links.transaction, links.linked, links.linked.size, 1, false)

        override suspend fun transactionLinks(generation: String, transactionId: String): TransactionLinks = error("Not used by editor")
        override suspend fun noteLinks(generation: String, noteId: String): NoteLinks = error("Not used by editor")
        override suspend fun selection(generation: String, transactionId: String): LinkSelection {
            selectionReads++
            return links
        }

        override suspend fun apply(
            generation: String,
            transactionId: String,
            transactionExpectedVersion: Long,
            add: List<LinkNoteRef>,
            remove: List<LinkNoteRef>,
        ): LinkBatchResult {
            calls += ApplyCall(transactionExpectedVersion, add, remove)
            nextCancellation?.let { nextCancellation = null; throw it }
            nextError?.let { nextError = null; throw LinkException(it) }
            return result
        }
    }
}
