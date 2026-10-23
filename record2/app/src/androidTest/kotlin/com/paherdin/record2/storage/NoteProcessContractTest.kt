package com.paherdin.record2.storage

import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NoteProcessContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val name get() = args.getString("databaseName") ?: error("Missing databaseName")
    private val scenario get() = args.getString("scenario") ?: error("Missing scenario")
    private val id get() = args.getString("noteId") ?: error("Missing noteId")
    private val path get() = instrumentation.targetContext.getDatabasePath(name)

    @Test fun prepare() {
        runBlocking {
            check(!path.exists())
            val database = noteDatabase(name)
            try {
                val generation = RoomFinanceService(database, noteClock).initialize().generation
                database.records().insertTransaction(TransactionEntity(schemaId(), "EXPENSE", 5800, "CNY", FinanceSeeds.ACCOUNT_ID, null, "fixture", noteOccurredAt, 1, noteClock.millis(), noteClock.millis()))
                var armed: NoteCheckpoint? = null
                var baseline = SchemaDiskObserver.snapshot(path)
                val notes = RoomNoteService(database, noteClock, checkpoint = { point ->
                    if (point == armed) {
                        val encoded = Base64.encodeToString(JSONObject(baseline).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        instrumentation.sendStatus(2, Bundle().apply { putString("b2_ready", encoded) })
                        check(CountDownLatch(1).await(120, TimeUnit.SECONDS)) { "Host did not terminate gated process" }
                    }
                })
                if (!scenario.startsWith("CREATE")) {
                    notes.save(generation, id, null, noteOriginalBody, noteOccurredAt)
                    val transactionId = SchemaDiskObserver.snapshot(path).getValue("record_transaction").single()[0] as String
                    database.records().insertLink(TransactionNoteLinkEntity(transactionId, id))
                }
                if (scenario.startsWith("RESTORE")) notes.delete(generation, id, 1)
                baseline = SchemaDiskObserver.snapshot(path)
                armed = when (scenario) {
                    "CREATE_BEFORE", "EDIT_BEFORE" -> NoteCheckpoint.NOTE_WRITTEN
                    "CREATE_AFTER", "EDIT_AFTER" -> NoteCheckpoint.NOTE_COMMITTED
                    "DELETE_BEFORE", "RESTORE_BEFORE" -> NoteCheckpoint.LIFECYCLE_WRITTEN
                    "DELETE_AFTER" -> NoteCheckpoint.DELETE_COMMITTED
                    "RESTORE_AFTER" -> NoteCheckpoint.RESTORE_COMMITTED
                    else -> error("Unsupported scenario")
                }
                when {
                    scenario.startsWith("CREATE") -> notes.save(generation, id, null, noteOriginalBody, noteOccurredAt)
                    scenario.startsWith("EDIT") -> notes.save(generation, id, 1, noteUpdatedBody, noteOccurredAt)
                    scenario.startsWith("DELETE") -> notes.delete(generation, id, 1)
                    scenario.startsWith("RESTORE") -> notes.restore(generation, id, 2)
                }
                error("Expected process gate")
            } finally { database.close() }
        }
    }

    @Test fun verifyAfterHostRestart() {
        runBlocking {
            check(path.isFile) { "Verification cannot create a database" }
            val baseline = decodeSnapshot(JSONObject(String(Base64.decode(args.getString("baseline") ?: error("Missing baseline"), Base64.DEFAULT), Charsets.UTF_8)))
            val observed = SchemaDiskObserver.snapshot(path)
            val state = baseline.getValue("record_data_state").single()
            val generation = state[1] as String
            val database = noteDatabase(name)
            try {
                val notes = RoomNoteService(database, noteClock)
                if (scenario.endsWith("BEFORE")) {
                    assertEquals(baseline, observed)
                } else {
                    val expectedNote: List<Any?> = if (scenario.startsWith("CREATE")) {
                        listOf(id, noteOriginalBody, noteOccurredAt, 1L, noteClock.millis(), noteClock.millis(), null)
                    } else {
                        baseline.getValue("note").single().toMutableList().apply {
                            set(3, (get(3) as Long) + 1)
                            set(5, noteClock.millis())
                            if (scenario.startsWith("EDIT")) set(1, noteUpdatedBody)
                            if (scenario.startsWith("DELETE")) set(6, noteClock.millis())
                            if (scenario.startsWith("RESTORE")) set(6, null)
                        }
                    }
                    val expected = baseline.toMutableMap().apply {
                        put("note", listOf(expectedNote))
                        put("record_data_state", listOf(state.toMutableList().apply { set(2, (get(2) as Long) + 1) }))
                    }
                    assertEquals(expected, observed)
                    when {
                        scenario.startsWith("CREATE") -> assertEquals(1L, notes.save(generation, id, null, noteOriginalBody, noteOccurredAt)?.version)
                        scenario.startsWith("EDIT") -> expectNoteError(NoteError.VersionConflict) { notes.save(generation, id, 1, noteUpdatedBody, noteOccurredAt) }
                        scenario.startsWith("DELETE") -> assertFalse(notes.delete(generation, id, 1).changed)
                        scenario.startsWith("RESTORE") -> assertFalse(notes.restore(generation, id, 2).changed)
                    }
                    assertEquals(observed, SchemaDiskObserver.snapshot(path))
                }
                assertEquals(baseline["record_transaction"], observed["record_transaction"])
                assertEquals(baseline["transaction_note_link"], observed["transaction_note_link"])
                val deleted = observed.getValue("note").singleOrNull()?.get(6) != null
                if (scenario == "CREATE_BEFORE") {
                    expectNoteError(NoteError.NotFound) { notes.visible(generation, id) }
                } else if (deleted) {
                    expectNoteError(NoteError.NotVisible) { notes.visible(generation, id) }
                    assertEquals(noteOriginalBody, notes.deleted(generation, id).body)
                } else {
                    assertEquals(observed.getValue("note").single()[1], notes.visible(generation, id).body)
                    assertEquals(noteOccurredAt, notes.visible(generation, id).occurredAt)
                }
            } finally { database.close() }
        }
    }

    private fun decodeSnapshot(json: JSONObject): Map<String, List<List<Any?>>> = json.keys().asSequence().associateWith { table ->
        val rows = json.getJSONArray(table)
        (0 until rows.length()).map { index ->
            val row = rows.getJSONArray(index)
            (0 until row.length()).map { column ->
                when (val value = row.get(column)) {
                    JSONObject.NULL -> null
                    is Number -> value.toLong()
                    is String -> value
                    else -> error("Unexpected physical value")
                }
            }
        }
    }
}
