package com.paherdin.record2.storage

import android.database.sqlite.SQLiteFullException
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LinkServiceContractTest {
    private val name = "b3-contract-${schemaId()}.db"
    private val path get() = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)
    private lateinit var database: RecordDatabase
    private lateinit var links: RoomLinkService
    private lateinit var notes: RoomNoteService
    private lateinit var generation: String

    @Before fun open() = runBlocking {
        check(!path.exists())
        database = linkDatabase(name)
        generation = RoomFinanceService(database, linkClock).initialize().generation
        links = RoomLinkService(database, linkWriteClock)
        notes = RoomNoteService(database, linkClock)
    }

    @After fun close() { database.close() }

    private fun snapshot() = SchemaDiskObserver.snapshot(path)
    private fun rows(table: String) = snapshot().getValue(table)
    private fun row(table: String, id: String) = rows(table).single { it[0] == id }
    private fun revision() = rows("record_data_state").single()[2] as Long
    private fun linkRows(): Set<Pair<String, String>> =
        rows("transaction_note_link").map { (it[0] as String) to (it[1] as String) }.toSet()

    private fun createTransaction(occurredAt: Long, amountMinor: Long = 5800): String {
        val id = schemaId()
        database.records().insertTransaction(
            TransactionEntity(id, "EXPENSE", amountMinor, "CNY", FinanceSeeds.ACCOUNT_ID, null, "fixture", occurredAt, 1, linkClock.millis(), linkClock.millis()),
        )
        return id
    }

    private suspend fun createNote(occurredAt: Long, body: String): NoteRecord =
        notes.save(generation, schemaId(), null, body, occurredAt)!!

    private suspend fun add(transactionId: String, transactionVersion: Long, vararg notes: NoteRecord) =
        links.apply(generation, transactionId, transactionVersion, notes.map { LinkNoteRef(it.id, it.version) }, emptyList())

    private suspend fun remove(transactionId: String, transactionVersion: Long, vararg notes: NoteRecord) =
        links.apply(generation, transactionId, transactionVersion, emptyList(), notes.map { LinkNoteRef(it.id, it.version) })

    private fun reopen() {
        database.close()
        database = linkDatabase(name)
        links = RoomLinkService(database, linkWriteClock)
        notes = RoomNoteService(database, linkClock)
    }

    @Test fun crossDayLinksAreBidirectionalAndVersionEachAffectedEndpointOnce() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val t2 = createTransaction(linkDayTwo)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        val baseRevision = revision()

        val first = links.apply(
            generation, t1, 1,
            listOf(LinkNoteRef(n1.id, 1), LinkNoteRef(n2.id, 1)),
            emptyList(),
        )
        assertTrue(first.changed)
        assertEquals(2L, first.transaction.version)
        assertEquals(listOf(n2.id, n1.id), first.notes.map { it.id })
        assertEquals(2, first.visibleLinkCount)
        assertEquals(baseRevision + 1, first.businessRevision)
        assertEquals(2L, notes.visible(generation, n1.id).version)
        assertEquals(2L, notes.visible(generation, n2.id).version)

        val second = add(t2, 1, notes.visible(generation, n1.id))
        assertTrue(second.changed)
        assertEquals(2L, second.transaction.version)
        assertEquals(listOf(n1.id), second.notes.map { it.id })
        assertEquals(baseRevision + 2, second.businessRevision)
        assertEquals(3L, notes.visible(generation, n1.id).version)
        assertEquals(2L, notes.visible(generation, n2.id).version)

        val t1Links = links.transactionLinks(generation, t1)
        assertEquals(listOf(n2.id, n1.id), t1Links.notes.map { it.id })
        assertEquals(2, t1Links.visibleLinkCount)
        val n1Links = links.noteLinks(generation, n1.id)
        assertEquals(listOf(t2, t1), n1Links.transactions.map { it.id })
        assertEquals(2, n1Links.visibleLinkCount)
        assertEquals(listOf(t1), links.noteLinks(generation, n2.id).transactions.map { it.id })

        assertEquals(setOf(t1 to n1.id, t1 to n2.id, t2 to n1.id), linkRows())
        assertEquals(2L, row("record_transaction", t1)[8])
        assertEquals(2L, row("record_transaction", t2)[8])
        assertEquals(3L, row("note", n1.id)[3])
        assertEquals(2L, row("note", n2.id)[3])
        assertEquals(linkWriteClock.millis(), row("note", n1.id)[5])
        assertEquals(linkWriteClock.millis(), row("record_transaction", t1)[10])
        assertEquals(baseRevision + 2, revision())

        val before = snapshot()
        reopen()
        assertEquals(before, snapshot())
        assertEquals(listOf(n2.id, n1.id), links.transactionLinks(generation, t1).notes.map { it.id })
        assertEquals(listOf(t2, t1), links.noteLinks(generation, n1.id).transactions.map { it.id })
    }

    @Test fun unchangedCompletionAndCancelledSelectionAreZeroWrites() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        add(t1, 1, n1)
        val baseRevision = revision()
        val before = snapshot()
        val current = links.transactionLinks(generation, t1)

        val unchanged = links.apply(
            generation, t1, current.transaction.version,
            listOf(LinkNoteRef(n1.id, 2)),
            listOf(LinkNoteRef(n2.id, 1)),
        )
        assertFalse(unchanged.changed)
        assertEquals(2L, unchanged.transaction.version)
        assertEquals(baseRevision, unchanged.businessRevision)
        assertEquals(before, snapshot())

        val empty = links.apply(generation, t1, 2, emptyList(), emptyList())
        assertFalse(empty.changed)
        assertEquals(before, snapshot())

        listOf<suspend () -> Unit>(
            { links.apply(generation, t1, 2, listOf(LinkNoteRef(n1.id, 2), LinkNoteRef(n1.id, 2)), emptyList()) },
            { links.apply(generation, t1, 2, listOf(LinkNoteRef(n1.id, 2)), listOf(LinkNoteRef(n1.id, 2))) },
            { links.apply(generation, t1, 2, emptyList(), listOf(LinkNoteRef(n2.id, 1), LinkNoteRef(n2.id, 1))) },
            { links.apply(generation, t1, 0, emptyList(), emptyList()) },
            { links.apply(generation, t1, 2, listOf(LinkNoteRef("not-a-uuid", 1)), emptyList()) },
            { links.apply(generation, t1, 2, listOf(LinkNoteRef(n2.id, 0)), emptyList()) },
        ).forEach { action -> expectLinkError(LinkError.Validation) { action() } }

        expectLinkError(LinkError.VersionConflict) { links.apply(generation, t1, 9, emptyList(), emptyList()) }
        expectLinkError(LinkError.VersionConflict) { links.apply(generation, t1, 2, listOf(LinkNoteRef(n2.id, 9)), emptyList()) }
        expectLinkError(LinkError.VersionConflict) { links.apply(generation, t1, 2, emptyList(), listOf(LinkNoteRef(n1.id, 1))) }
        expectLinkError(LinkError.GenerationMismatch) { links.apply(schemaId(), t1, 2, emptyList(), emptyList()) }
        assertEquals(before, snapshot())
        reopen()
        assertEquals(before, snapshot())
    }

    @Test fun removingOneLinkKeepsRemainingRelationsAndOnlyBumpsAffectedEndpoints() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        add(t1, 1, n1, n2)
        val beforeLinks = linkRows()
        val baseRevision = revision()

        val removed = remove(t1, 2, notes.visible(generation, n2.id))
        assertTrue(removed.changed)
        assertEquals(3L, removed.transaction.version)
        assertEquals(listOf(n1.id), removed.notes.map { it.id })
        assertEquals(1, removed.visibleLinkCount)
        assertEquals(baseRevision + 1, removed.businessRevision)
        assertEquals(2L, notes.visible(generation, n1.id).version)
        assertEquals(3L, notes.visible(generation, n2.id).version)
        assertEquals(setOf(t1 to n1.id), linkRows())
        assertEquals(setOf(t1 to n1.id, t1 to n2.id), beforeLinks)

        val t1Links = links.transactionLinks(generation, t1)
        assertEquals(listOf(n1.id), t1Links.notes.map { it.id })
        assertEquals(1, t1Links.visibleLinkCount)
        assertEquals(0, links.noteLinks(generation, n2.id).transactions.size)
        assertEquals(0, links.noteLinks(generation, n2.id).visibleLinkCount)
        assertEquals(listOf(t1), links.noteLinks(generation, n1.id).transactions.map { it.id })
        assertEquals(3L, row("record_transaction", t1)[8])
        assertEquals(3L, row("note", n2.id)[3])
        assertEquals(2L, row("note", n1.id)[3])
        reopen()
        assertEquals(setOf(t1 to n1.id), linkRows())
    }

    @Test fun deletedNoteHidesNormalListCountAndCandidatesAndRestoreKeepsOriginalRelations() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        add(t1, 1, n1, n2)
        val n3 = createNote(linkDayThree, linkNoteThreeBody)
        notes.delete(generation, n1.id, notes.visible(generation, n1.id).version)
        val afterDelete = snapshot()
        assertEquals(3L, row("note", n1.id)[3])
        assertEquals(2L, row("record_transaction", t1)[8])
        assertEquals(setOf(t1 to n1.id, t1 to n2.id), linkRows())

        val normal = links.transactionLinks(generation, t1)
        assertEquals(listOf(n2.id), normal.notes.map { it.id })
        assertEquals(1, normal.visibleLinkCount)
        val selection = links.selection(generation, t1)
        assertEquals(listOf(n2.id), selection.linked.map { it.id })
        assertEquals(listOf(n3.id), selection.candidates.map { it.id })
        expectLinkError(LinkError.NotVisible) { links.noteLinks(generation, n1.id) }
        assertEquals(listOf(t1), links.noteLinks(generation, n2.id).transactions.map { it.id })

        listOf<suspend () -> Unit>(
            { links.apply(generation, t1, 2, listOf(LinkNoteRef(n1.id, 2)), emptyList()) },
            { links.apply(generation, t1, 2, emptyList(), listOf(LinkNoteRef(n1.id, 2))) },
            { links.apply(generation, t1, 2, emptyList(), listOf(LinkNoteRef(n1.id, 3))) },
        ).forEach { action -> expectLinkError(LinkError.NotVisible) { action() } }
        assertEquals(afterDelete, snapshot())

        notes.restore(generation, n1.id, 3)
        assertNull(notes.visible(generation, n1.id).deletedAt)
        assertEquals(linkNoteOneBody, notes.visible(generation, n1.id).body)
        assertEquals(linkDayOne, notes.visible(generation, n1.id).occurredAt)
        val restored = links.transactionLinks(generation, t1)
        assertEquals(setOf(n1.id, n2.id), restored.notes.map { it.id }.toSet())
        assertEquals(2, restored.visibleLinkCount)
        assertEquals(setOf(n1.id, n2.id), links.selection(generation, t1).linked.map { it.id }.toSet())
        assertEquals(listOf(t1), links.noteLinks(generation, n1.id).transactions.map { it.id })
        assertEquals(afterDelete - setOf("note", "record_data_state"), snapshot() - setOf("note", "record_data_state"))
        assertEquals(2L, row("record_transaction", t1)[8])
        assertEquals(setOf(t1 to n1.id, t1 to n2.id), linkRows())
        reopen()
        assertEquals(setOf(n1.id, n2.id), links.transactionLinks(generation, t1).notes.map { it.id }.toSet())
    }

    @Test fun staleSelectionIsRejectedWholesaleAfterAnotherConnectionChangesRelations() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        val n3 = createNote(linkDayThree, linkNoteThreeBody)
        add(t1, 1, n1)
        val baseline = links.selection(generation, t1)
        val editor = LinkSelectionEditor(links, generation, t1, baseline)
        val second = linkDatabase(name)
        try {
            val other = RoomLinkService(second, linkWriteClock)
            other.apply(generation, t1, 2, listOf(LinkNoteRef(n2.id, 1)), emptyList())
            val beforeAttempt = snapshot()
            assertTrue(editor.toggle(n3.id))
            assertFalse(editor.complete())
            assertEquals(LinkError.VersionConflict, editor.state.value.error)
            assertTrue(editor.state.value.requiresReload)
            assertEquals(setOf(n1.id, n3.id), editor.state.value.selected)
            assertEquals(beforeAttempt, snapshot())

            assertTrue(editor.reloadDiscardingSelection())
            assertEquals(setOf(n1.id, n2.id), editor.state.value.selected)
            assertEquals(3L, editor.state.value.transactionVersion)
            assertTrue(editor.toggle(n3.id))
            assertTrue(editor.complete())
            assertTrue(editor.state.value.completed)
            assertEquals(4L, editor.state.value.transactionVersion)
            assertEquals(setOf(t1 to n1.id, t1 to n2.id, t1 to n3.id), linkRows())
            assertEquals(3, links.transactionLinks(generation, t1).visibleLinkCount)
            assertEquals(2L, notes.visible(generation, n2.id).version)
            assertEquals(2L, notes.visible(generation, n3.id).version)
        } finally { second.close() }
    }

    @Test fun hiddenRelationIsNeverRemovedByAStaleSelectionAndReappearsOnRestore() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        add(t1, 1, n1, n2)
        val editor = LinkSelectionEditor(links, generation, t1, links.selection(generation, t1))
        val second = linkDatabase(name)
        try {
            val otherNotes = RoomNoteService(second, linkClock)
            otherNotes.delete(generation, n2.id, 2)
            val afterDelete = snapshot()
            assertEquals(setOf(t1 to n1.id, t1 to n2.id), linkRows())
            assertEquals(1, links.transactionLinks(generation, t1).visibleLinkCount)

            assertTrue(editor.toggle(n2.id))
            assertFalse(editor.complete())
            assertEquals(LinkError.NotVisible, editor.state.value.error)
            assertTrue(editor.state.value.requiresReload)
            assertEquals(setOf(n1.id), editor.state.value.selected)
            assertEquals(afterDelete, snapshot())
            assertEquals(setOf(t1 to n1.id, t1 to n2.id), linkRows())

            otherNotes.restore(generation, n2.id, 3)
            assertEquals(2, links.transactionLinks(generation, t1).visibleLinkCount)
            assertEquals(listOf(t1), links.noteLinks(generation, n2.id).transactions.map { it.id })
            assertTrue(editor.reloadDiscardingSelection())
            assertEquals(setOf(n1.id, n2.id), editor.state.value.selected)
        } finally { second.close() }
    }

    @Test fun mixedBatchWithOneInvalidTargetRejectsEveryChangeBeforeAnyWrite() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        val n3 = createNote(linkDayThree, linkNoteThreeBody)
        add(t1, 1, n1, n2)
        notes.delete(generation, n2.id, 2)
        val afterDelete = snapshot()

        expectLinkError(LinkError.NotVisible) {
            links.apply(
                generation, t1, 2,
                listOf(LinkNoteRef(n3.id, 1)),
                listOf(LinkNoteRef(n2.id, 2)),
            )
        }
        assertEquals(afterDelete, snapshot())
        assertEquals(setOf(t1 to n1.id, t1 to n2.id), linkRows())
        assertEquals(1L, row("note", n3.id)[3])

        expectLinkError(LinkError.VersionConflict) {
            links.apply(
                generation, t1, 2,
                listOf(LinkNoteRef(n3.id, 1)),
                listOf(LinkNoteRef(n1.id, 1)),
            )
        }
        assertEquals(afterDelete, snapshot())

        notes.restore(generation, n2.id, 3)
        val success = links.apply(
            generation, t1, 2,
            listOf(LinkNoteRef(n3.id, 1)),
            listOf(LinkNoteRef(n2.id, 4)),
        )
        assertTrue(success.changed)
        assertEquals(setOf(t1 to n1.id, t1 to n3.id), linkRows())
        assertEquals(2L, notes.visible(generation, n3.id).version)
    }

    @Test fun successfulDeltaKeepsHiddenRelationAndItReappearsOnRestore() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        val n3 = createNote(linkDayThree, linkNoteThreeBody)
        add(t1, 1, n1, n2)
        notes.delete(generation, n2.id, 2)
        val baseRevision = revision()
        val afterDelete = snapshot()

        val added = add(t1, 2, n3)
        assertTrue(added.changed)
        assertEquals(3L, added.transaction.version)
        assertEquals(baseRevision + 1, added.businessRevision)
        assertEquals(listOf(n3.id, n1.id), added.notes.map { it.id })
        assertEquals(2, added.visibleLinkCount)
        assertEquals(setOf(t1 to n1.id, t1 to n2.id, t1 to n3.id), linkRows())
        assertNotEquals(afterDelete, snapshot())

        notes.restore(generation, n2.id, 3)
        assertEquals(3, links.transactionLinks(generation, t1).visibleLinkCount)
        assertEquals(setOf(n1.id, n2.id, n3.id), links.transactionLinks(generation, t1).notes.map { it.id }.toSet())
        assertEquals(setOf(t1 to n1.id, t1 to n2.id, t1 to n3.id), linkRows())
        assertEquals(3L, row("record_transaction", t1)[8])
    }

    @Test fun twoConnectionsCompeteForOneBusinessCasWinnerAndStaleLoserRetriesCleanly() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        val baseRevision = revision()
        val second = linkDatabase(name)
        try {
            val other = RoomLinkService(second, linkWriteClock)
            val outcomes = coroutineScope {
                listOf(
                    async { runCatching { links.apply(generation, t1, 1, listOf(LinkNoteRef(n1.id, 1)), emptyList()) } },
                    async { runCatching { other.apply(generation, t1, 1, listOf(LinkNoteRef(n2.id, 1)), emptyList()) } },
                ).awaitAll()
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            val failure = outcomes.single { it.isFailure }.exceptionOrNull()
            assertTrue(failure is LinkException)
            assertEquals(LinkError.VersionConflict, (failure as LinkException).code)
            assertEquals(2L, row("record_transaction", t1)[8])
            assertEquals(1, linkRows().size)
            assertEquals(baseRevision + 1, revision())
            val winnerNote = linkRows().single().second
            assertEquals(2L, row("note", winnerNote)[3])
            assertEquals(1L, row("note", if (winnerNote == n1.id) n2.id else n1.id)[3])

            val loser = if (winnerNote == n1.id) n2 else n1
            val retry = add(t1, 2, notes.visible(generation, loser.id))
            assertTrue(retry.changed)
            assertEquals(3L, retry.transaction.version)
            assertEquals(2, retry.visibleLinkCount)
            assertEquals(baseRevision + 2, retry.businessRevision)
            assertEquals(setOf(t1 to n1.id, t1 to n2.id), linkRows())
        } finally { second.close() }
    }

    private suspend fun failAt(point: LinkCheckpoint, index: Int, action: suspend (LinkService) -> Unit) {
        val before = snapshot()
        repeat(2) {
            val failing = RoomLinkService(database, linkWriteClock) { changed, position ->
                if (changed == point && position == index) throw SQLiteFullException("Synthetic disk full")
            }
            expectLinkError(LinkError.StorageFull) { action(failing) }
            assertEquals(before, snapshot())
        }
        reopen()
        assertEquals(before, snapshot())
    }

    @Test fun storageFailureAtEveryLinkWriteRollsBackTheWholeBatch() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        val n3 = createNote(linkDayThree, linkNoteThreeBody)
        for (index in 1..3) {
            failAt(LinkCheckpoint.LINK_WRITTEN, index) { service ->
                service.apply(generation, t1, 1, listOf(LinkNoteRef(n1.id, 1), LinkNoteRef(n2.id, 1), LinkNoteRef(n3.id, 1)), emptyList())
            }
        }
        assertTrue(add(t1, 1, n1, n2, n3).changed)
        for (index in 1..2) {
            failAt(LinkCheckpoint.LINK_WRITTEN, index) { service ->
                service.apply(generation, t1, 2, emptyList(), listOf(LinkNoteRef(n1.id, 2), LinkNoteRef(n2.id, 2)))
            }
        }
        assertTrue(remove(t1, 2, notes.visible(generation, n1.id), notes.visible(generation, n2.id)).changed)
        assertEquals(setOf(t1 to n3.id), linkRows())
        assertTrue(add(t1, 3, notes.visible(generation, n1.id)).changed)

        val beforeCommit = snapshot()
        val failingCommit = RoomLinkService(database, linkWriteClock) { point, _ ->
            if (point == LinkCheckpoint.LINKS_COMMITTED) throw IllegalStateException("Synthetic lost receipt")
        }
        expectLinkError(LinkError.OutcomeUnknown) {
            failingCommit.apply(generation, t1, 4, emptyList(), listOf(LinkNoteRef(n1.id, 4)))
        }
        val afterCommit = snapshot()
        assertNotEquals(beforeCommit, afterCommit)
        assertEquals(setOf(t1 to n3.id), linkRows())
        assertEquals(5L, row("record_transaction", t1)[8])
        assertEquals(5L, row("note", n1.id)[3])
        expectLinkError(LinkError.VersionConflict) {
            links.apply(generation, t1, 4, emptyList(), listOf(LinkNoteRef(n1.id, 4)))
        }
        assertEquals(afterCommit, snapshot())
    }

    private suspend fun cancelAt(index: Int, action: suspend (LinkService) -> Unit) = coroutineScope {
        val before = snapshot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelling = RoomLinkService(database, linkWriteClock) { point, position ->
            if (point == LinkCheckpoint.LINK_WRITTEN && position == index) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
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

    @Test fun realCancellationAtEachLinkWriteRollsBackWithoutPartialRelations() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val n2 = createNote(linkDayTwo, linkNoteTwoBody)
        val n3 = createNote(linkDayThree, linkNoteThreeBody)
        for (index in 1..3) {
            cancelAt(index) { service ->
                service.apply(generation, t1, 1, listOf(LinkNoteRef(n1.id, 1), LinkNoteRef(n2.id, 1), LinkNoteRef(n3.id, 1)), emptyList())
            }
        }
        assertEquals(1L, row("record_transaction", t1)[8])
        assertEquals(emptySet<Pair<String, String>>(), linkRows())
        assertTrue(add(t1, 1, n1, n2).changed)
        cancelAt(1) { service ->
            service.apply(generation, t1, 2, emptyList(), listOf(LinkNoteRef(n1.id, 2), LinkNoteRef(n2.id, 2)))
        }
        assertEquals(2L, row("record_transaction", t1)[8])
        assertEquals(setOf(t1 to n1.id, t1 to n2.id), linkRows())
    }

    @Test fun cancellationAfterCommitLeavesCommittedRelationsAndReportsUnknown() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observed = CompletableDeferred<Throwable>()
        val gated = RoomLinkService(database, linkWriteClock) { point, _ ->
            if (point == LinkCheckpoint.LINKS_COMMITTED) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val job = launch(Dispatchers.IO) {
            try {
                gated.apply(generation, t1, 1, listOf(LinkNoteRef(n1.id, 1)), emptyList())
            } catch (error: Throwable) {
                observed.complete(error)
                throw error
            }
        }
        try {
            // Reaching this post-commit checkpoint already proves the batch is durable.
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            job.cancel()
        } finally {
            release.countDown()
            job.cancel()
        }
        job.join()
        assertTrue(job.isCancelled)
        val failure = withTimeout(5000) { observed.await() }
        assertTrue(failure is LinkCommittedCancellation)
        assertTrue(failure is CancellationException)
        assertEquals(setOf(t1 to n1.id), linkRows())
        assertEquals(2L, row("record_transaction", t1)[8])
        assertEquals(2L, row("note", n1.id)[3])
        val linksAfter = links.transactionLinks(generation, t1)
        assertEquals(setOf(n1.id), linksAfter.notes.map { it.id }.toSet())
        assertEquals(1, linksAfter.visibleLinkCount)
    }

    @Test fun versionRevisionOverflowValidationAndDuplicatePairingNeverPartiallyWrite() = runBlocking {
        val t1 = createTransaction(linkDayOne)
        val n1 = createNote(linkDayOne, linkNoteOneBody)
        val baseRevision = revision()
        database.openHelper.writableDatabase.execSQL("UPDATE record_transaction SET version=? WHERE id=?", arrayOf<Any>(Long.MAX_VALUE, t1))
        var before = snapshot()
        expectLinkError(LinkError.VersionConflict) {
            links.apply(generation, t1, Long.MAX_VALUE, listOf(LinkNoteRef(n1.id, 1)), emptyList())
        }
        assertEquals(before, snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE record_transaction SET version=1 WHERE id=?", arrayOf<Any>(t1))

        database.openHelper.writableDatabase.execSQL("UPDATE note SET version=? WHERE id=?", arrayOf<Any>(Long.MAX_VALUE, n1.id))
        before = snapshot()
        expectLinkError(LinkError.VersionConflict) {
            links.apply(generation, t1, 1, listOf(LinkNoteRef(n1.id, Long.MAX_VALUE)), emptyList())
        }
        assertEquals(before, snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE note SET version=1 WHERE id=?", arrayOf<Any>(n1.id))

        database.openHelper.writableDatabase.execSQL("UPDATE record_data_state SET businessRevision=?", arrayOf<Any>(Long.MAX_VALUE))
        before = snapshot()
        expectLinkError(LinkError.VersionConflict) {
            links.apply(generation, t1, 1, listOf(LinkNoteRef(n1.id, 1)), emptyList())
        }
        assertEquals(before, snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE record_data_state SET businessRevision=?", arrayOf<Any>(baseRevision))

        val first = add(t1, 1, notes.visible(generation, n1.id))
        assertTrue(first.changed)
        before = snapshot()
        val duplicate = links.apply(generation, t1, 2, listOf(LinkNoteRef(n1.id, 2)), emptyList())
        assertFalse(duplicate.changed)
        assertEquals(before, snapshot())
        assertEquals(
            listOf(t1 to n1.id),
            rows("transaction_note_link").map { (it[0] as String) to (it[1] as String) },
        )

        listOf<suspend () -> Unit>(
            { links.apply(generation, t1, 2, listOf(LinkNoteRef(n1.id, 2), LinkNoteRef(n1.id, 2)), emptyList()) },
            { links.apply(generation, t1, 2, listOf(LinkNoteRef(n1.id, 2)), listOf(LinkNoteRef(n1.id, 2))) },
            { links.apply(generation, t1, 2, listOf(LinkNoteRef(n1.id, 0)), emptyList()) },
            { links.apply(generation, t1, 2, listOf(LinkNoteRef("not-a-uuid", 1)), emptyList()) },
            { links.noteLinks(generation, "not-a-uuid") },
        ).forEach { action -> expectLinkError(LinkError.Validation) { action() } }
        expectLinkError(LinkError.NotFound) { links.transactionLinks(generation, schemaId()) }
        expectLinkError(LinkError.NotFound) { links.noteLinks(generation, schemaId()) }
        expectLinkError(LinkError.GenerationMismatch) { links.transactionLinks(schemaId(), t1) }
        assertEquals(before, snapshot())
        reopen()
        assertEquals(before, snapshot())
    }
}
