package com.paherdin.record2.storage

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecordSchemaContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "schema-contract-${schemaId()}.db"
    private val path get() = context.getDatabasePath(name)
    private val fixture = SchemaTestFixtures()
    private lateinit var database: RecordDatabase

    private fun open(): RecordDatabase = Room.databaseBuilder(context, RecordDatabase::class.java, name).build()

    @Before fun setUp() {
        check(context.packageName == "com.paherdin.record2.debug")
        check(!path.exists()) { "Test database name collision" }
        database = open()
        fixture.seed(database)
    }

    @After fun close() {
        if (::database.isInitialized) database.close()
        // Retain task-owned databases; deletion is outside this execution authorization.
    }

    private fun constraint(message: String, block: () -> Unit) {
        val before = SchemaDiskObserver.snapshot(path)
        val failure = assertThrows(SQLiteConstraintException::class.java, block)
        assertTrue(failure.message, failure.message.orEmpty().contains(message, ignoreCase = true))
        assertEquals(before, SchemaDiskObserver.snapshot(path))
    }

    private fun completedRun(sessionId: String = fixture.session.id) = fixture.run.copy(
        id = schemaId(), sessionId = sessionId, proposalCreationRequestId = schemaId(),
    )

    @Test fun allFourteenTablesPersistEveryFieldAcrossCloseAndReopen() {
        val physical = database.openHelper.readableDatabase
        physical.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
            val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertEquals(SchemaDiskObserver.tables, names - setOf("android_metadata", "room_master_table"))
        }
        assertEquals(fixture.expectedRows(), SchemaDiskObserver.snapshot(path))
        assertEquals(fixture.transaction, database.records().transaction(fixture.transaction.id))
        assertEquals(fixture.note, database.records().visibleNote(fixture.note.id))
        assertEquals(fixture.draft, database.records().draft())
        assertEquals(fixture.proposal, database.proposals().proposal(fixture.proposal.id))
        assertEquals(fixture.operation, database.proposals().operationForProposal(fixture.proposal.id))
        assertEquals(fixture.run, database.sessions().run(fixture.run.id))
        assertEquals(fixture.reply, database.sessions().message(fixture.reply.id))
        assertEquals(fixture.control, database.aiControl().control())
        database.close()
        database = open()
        assertEquals(1, database.openHelper.readableDatabase.version)
        assertEquals(fixture.expectedRows(), SchemaDiskObserver.snapshot(path))
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun requiredFieldsPrimaryKeysAndSingletonKeysRejectDuplicates() {
        constraint("UNIQUE") { database.records().insertAccount(fixture.account.copy(name = "不覆盖改名")) }
        constraint("UNIQUE") { database.records().insertDataState(RecordDataStateEntity(1, schemaId(), 0, schemaId(), null)) }
        constraint("UNIQUE") { database.records().insertDraft(fixture.draft.copy(draftId = schemaId())) }
        constraint("UNIQUE") { database.aiControl().insertControl(fixture.control.copy(authEpoch = 2)) }
        constraint("NOT NULL") {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO note (id,body,occurredAt,version,createdAt,updatedAt) VALUES (?,NULL,?,1,?,?)",
                arrayOf<Any>(schemaId(), fixture.time, fixture.time, fixture.time),
            )
        }
    }

    @Test fun transactionForeignKeysCheckAccountAndCategoryDirectionWithoutRequiringCategory() {
        constraint("FOREIGN KEY") {
            database.records().insertTransaction(fixture.transaction.copy(id = schemaId(), accountId = schemaId()))
        }
        constraint("FOREIGN KEY") {
            database.records().insertTransaction(fixture.transaction.copy(id = schemaId(), direction = "INCOME"))
        }
        constraint("FOREIGN KEY") {
            database.records().insertTransaction(fixture.transaction.copy(id = schemaId(), categoryId = schemaId()))
        }
        val uncategorized = fixture.transaction.copy(id = schemaId(), categoryId = null, amountMinor = Long.MAX_VALUE)
        database.records().insertTransaction(uncategorized)
        assertEquals(uncategorized, database.records().transaction(uncategorized.id))
        assertEquals(Long.MAX_VALUE, SchemaDiskObserver.snapshot(path).getValue("record_transaction")
            .single { it[0] == uncategorized.id }[2])
    }

    @Test fun linkPairsAreUniqueIncrementalAndRestrictEndpointDeletion() {
        constraint("UNIQUE") { database.records().insertLink(TransactionNoteLinkEntity(fixture.transaction.id, fixture.note.id)) }
        constraint("FOREIGN KEY") { database.records().insertLink(TransactionNoteLinkEntity(fixture.transaction.id, schemaId())) }
        constraint("FOREIGN KEY") { database.records().insertLink(TransactionNoteLinkEntity(schemaId(), fixture.note.id)) }
        constraint("FOREIGN KEY") { database.openHelper.writableDatabase.execSQL("DELETE FROM note WHERE id = ?", arrayOf(fixture.note.id)) }
        constraint("FOREIGN KEY") { database.openHelper.writableDatabase.execSQL("DELETE FROM account WHERE id = ?", arrayOf(fixture.account.id)) }
        constraint("FOREIGN KEY") { database.openHelper.writableDatabase.execSQL("DELETE FROM category WHERE id = ?", arrayOf(fixture.category.id)) }
        val otherNote = fixture.note.copy(id = schemaId(), occurredAt = fixture.time - 86_400_000)
        database.records().insertNote(otherNote)
        database.records().insertLink(TransactionNoteLinkEntity(fixture.transaction.id, otherNote.id))
        assertEquals(1, database.records().removeLink(fixture.transaction.id, fixture.note.id))
        assertEquals(0, database.records().removeLink(fixture.transaction.id, fixture.note.id))
        assertEquals(listOf(listOf(fixture.transaction.id, otherNote.id)), SchemaDiskObserver.snapshot(path)["transaction_note_link"])
    }

    @Test fun noteCasRejectsStaleDeletedAndOverflowWritesWithoutOverwritingText() {
        val records = database.records()
        assertEquals(1, records.updateNoteBody(fixture.note.id, 7, "最新正文\n仍保留换行", fixture.time + 1))
        assertEquals(0, records.updateNoteBody(fixture.note.id, 7, "迟到旧正文", fixture.time + 2))
        assertEquals("最新正文\n仍保留换行", records.visibleNote(fixture.note.id)?.body)
        assertEquals(8L, records.visibleNote(fixture.note.id)?.version)
        val deleted = fixture.note.copy(id = schemaId(), deletedAt = fixture.time + 1)
        val fullVersion = fixture.note.copy(id = schemaId(), version = Long.MAX_VALUE)
        records.insertNote(deleted)
        records.insertNote(fullVersion)
        records.insertLink(TransactionNoteLinkEntity(fixture.transaction.id, deleted.id))
        val before = SchemaDiskObserver.snapshot(path)
        assertNull(records.visibleNote(deleted.id))
        assertEquals(0, records.updateNoteBody(deleted.id, 7, "不能复活", fixture.time + 2))
        assertEquals(0, records.updateNoteBody(fullVersion.id, Long.MAX_VALUE, "不能溢出", fixture.time + 2))
        assertEquals(before, SchemaDiskObserver.snapshot(path))
    }

    @Test fun runSlotsPermitHistoryAndDifferentSessionsButOnlyOneActivePerSession() {
        val sessions = database.sessions()
        sessions.insertRun(completedRun())
        sessions.insertRun(completedRun().copy(state = "FAILED"))
        val active = completedRun().copy(state = "RUNNING", activeSlot = 1, finishedAt = null)
        sessions.insertRun(active)
        constraint("UNIQUE") { sessions.insertRun(active.copy(id = schemaId(), proposalCreationRequestId = schemaId())) }
        constraint("UNIQUE") { sessions.insertRun(completedRun().copy(proposalCreationRequestId = fixture.run.proposalCreationRequestId)) }
        val otherSession = fixture.session.copy(id = schemaId())
        sessions.insertSession(otherSession)
        sessions.insertRun(active.copy(id = schemaId(), sessionId = otherSession.id, proposalCreationRequestId = schemaId()))
        constraint("FOREIGN KEY") { sessions.insertRun(completedRun(schemaId())) }
    }

    @Test fun proposalSlotsRequestIdentityAndBothRunOwnersAreConstrained() {
        val proposals = database.proposals()
        val nextRun = completedRun()
        database.sessions().insertRun(nextRun)
        val next = fixture.proposal.copy(
            id = schemaId(), originRunId = nextRun.id, lastWriterRunId = nextRun.id,
            creationRequestId = nextRun.proposalCreationRequestId,
        )
        constraint("UNIQUE") { proposals.insertProposal(next) }
        proposals.insertProposal(next.copy(state = "ABANDONED", activeSlot = null))
        constraint("UNIQUE") { proposals.insertProposal(fixture.proposal.copy(id = schemaId(), state = "ABANDONED", activeSlot = null)) }
        val otherSession = fixture.session.copy(id = schemaId())
        database.sessions().insertSession(otherSession)
        val otherRun = completedRun(otherSession.id)
        database.sessions().insertRun(otherRun)
        val otherProposal = fixture.proposal.copy(
            id = schemaId(), sessionId = otherSession.id, originRunId = otherRun.id,
            lastWriterRunId = otherRun.id, creationRequestId = otherRun.proposalCreationRequestId,
        )
        constraint("FOREIGN KEY") { proposals.insertProposal(otherProposal.copy(originRunId = fixture.run.id)) }
        constraint("FOREIGN KEY") { proposals.insertProposal(otherProposal.copy(lastWriterRunId = fixture.run.id)) }
        proposals.insertProposal(otherProposal)
    }

    @Test fun revisionCasPreservesOriginalCreationPayloadAndRejectsLockedOrOverflow() {
        val proposals = database.proposals()
        val nextRun = completedRun()
        database.sessions().insertRun(nextRun)
        database.openHelper.writableDatabase.execSQL("UPDATE change_proposal SET state='ABANDONED',activeSlot=NULL WHERE id=?", arrayOf(fixture.proposal.id))
        val editable = fixture.proposal.copy(
            id = schemaId(), originRunId = nextRun.id, lastWriterRunId = nextRun.id,
            creationRequestId = nextRun.proposalCreationRequestId, state = "EDITABLE",
        )
        proposals.insertProposal(editable)
        assertEquals(1, proposals.reviseProposal(editable.id, 1, nextRun.id, "new-payload", "new-digest", fixture.time + 1))
        assertEquals(0, proposals.reviseProposal(editable.id, 1, nextRun.id, "old-payload", "old-digest", fixture.time + 2))
        val revised = proposals.proposal(editable.id)!!
        assertEquals(2L, revised.revision)
        assertEquals("new-payload", revised.payloadJson)
        assertEquals(editable.creationPayloadJson, revised.creationPayloadJson)
        database.openHelper.writableDatabase.execSQL("UPDATE change_proposal SET state='LOCKED' WHERE id=?", arrayOf(editable.id))
        assertEquals(0, proposals.reviseProposal(editable.id, 2, nextRun.id, "forbidden", "forbidden", fixture.time + 3))
        database.openHelper.writableDatabase.execSQL("UPDATE change_proposal SET state='EDITABLE',revision=? WHERE id=?", arrayOf<Any>(Long.MAX_VALUE, editable.id))
        assertEquals(0, proposals.reviseProposal(editable.id, Long.MAX_VALUE, nextRun.id, "overflow", "overflow", fixture.time + 3))
    }

    @Test fun messagesAndReferencesRejectCrossSessionDuplicateAndOrphanIdentities() {
        val sessions = database.sessions()
        val other = fixture.session.copy(id = schemaId())
        sessions.insertSession(other)
        val nextRun = completedRun()
        sessions.insertRun(nextRun)
        constraint("FOREIGN KEY") { sessions.insertMessage(fixture.input.copy(id = schemaId(), sessionId = other.id, runId = nextRun.id)) }
        constraint("UNIQUE") { sessions.insertMessage(fixture.input.copy(id = schemaId(), runId = nextRun.id)) }
        constraint("UNIQUE") { sessions.insertMessage(fixture.input.copy(id = schemaId(), sequence = 3)) }
        constraint("UNIQUE") { sessions.insertReference(fixture.reference) }
        constraint("FOREIGN KEY") { sessions.insertReference(fixture.reference.copy(messageId = schemaId())) }
        constraint("FOREIGN KEY") { sessions.insertReference(fixture.reference.copy(ordinal = 1, noteId = schemaId())) }
        constraint("FOREIGN KEY") { sessions.insertReference(fixture.reference.copy(ordinal = 1, noteId = null, transactionId = schemaId())) }
    }

    @Test fun operationIdentityPayloadAndTerminalResultsSurviveParentHistoryRemoval() {
        val proposals = database.proposals()
        constraint("UNIQUE") { proposals.insertOperation(fixture.operation.copy(id = schemaId())) }
        assertEquals(0, proposals.finishOperation(fixture.operation.id, "wrong-digest", "Applied", "{}", null, fixture.time + 1))
        assertEquals(0, proposals.finishOperation(fixture.operation.id, fixture.operation.payloadDigest, "Pending", null, null, fixture.time + 1))
        assertEquals(1, proposals.finishOperation(fixture.operation.id, fixture.operation.payloadDigest, "Applied", "{\"ids\":[]}", null, fixture.time + 1))
        val applied = proposals.operationForProposal(fixture.proposal.id)!!
        assertEquals(fixture.operation.frozenPayloadJson, applied.frozenPayloadJson)
        assertEquals(fixture.operation.payloadDigest, applied.payloadDigest)
        assertEquals(0, proposals.finishOperation(applied.id, applied.payloadDigest, "RejectedConflict", null, "stale", fixture.time + 2))
        constraint("FOREIGN KEY") { database.openHelper.writableDatabase.execSQL("DELETE FROM agent_session WHERE id=?", arrayOf(fixture.session.id)) }
        val before = SchemaDiskObserver.snapshot(path)
        database.runInTransaction {
            val sql = database.openHelper.writableDatabase
            sql.execSQL("DELETE FROM agent_message_reference")
            sql.execSQL("DELETE FROM agent_message")
            sql.execSQL("DELETE FROM change_proposal")
            sql.execSQL("DELETE FROM agent_run")
            sql.execSQL("DELETE FROM agent_session")
        }
        assertEquals(applied, proposals.operationForProposal(fixture.proposal.id))
        val after = SchemaDiskObserver.snapshot(path)
        for (table in setOf("commit_operation", "record_transaction", "note", "transaction_note_link")) {
            assertEquals(table, before[table], after[table])
        }
        database.close()
        database = open()
        database.openHelper.readableDatabase
        assertEquals(applied, database.proposals().operationForProposal(fixture.proposal.id))
    }
}
