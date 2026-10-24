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

class LinkProcessContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val name get() = args.getString("databaseName") ?: error("Missing databaseName")
    private val scenario get() = args.getString("scenario") ?: error("Missing scenario")
    private val transactionId get() = args.getString("transactionId") ?: error("Missing transactionId")
    private val note1Id get() = args.getString("note1Id") ?: error("Missing note1Id")
    private val note2Id get() = args.getString("note2Id") ?: error("Missing note2Id")
    private val note3Id get() = args.getString("note3Id") ?: error("Missing note3Id")
    private val path get() = instrumentation.targetContext.getDatabasePath(name)

    @Test fun prepare() {
        runBlocking {
            check(!path.exists())
            val database = linkDatabase(name)
            try {
                val generation = RoomFinanceService(database, linkClock).initialize().generation
                database.records().insertTransaction(
                    TransactionEntity(transactionId, "EXPENSE", 5800, "CNY", FinanceSeeds.ACCOUNT_ID, null, "fixture", linkDayOne, 1, linkClock.millis(), linkClock.millis()),
                )
                val notes = RoomNoteService(database, linkClock)
                notes.save(generation, note1Id, null, linkNoteOneBody, linkDayOne)
                notes.save(generation, note2Id, null, linkNoteTwoBody, linkDayTwo)
                notes.save(generation, note3Id, null, linkNoteThreeBody, linkDayThree)
                if (scenario.startsWith("REMOVE")) {
                    RoomLinkService(database, linkWriteClock).apply(
                        generation, transactionId, 1,
                        listOf(LinkNoteRef(note1Id, 1), LinkNoteRef(note2Id, 1), LinkNoteRef(note3Id, 1)),
                        emptyList(),
                    )
                }
                val baseline = SchemaDiskObserver.snapshot(path)
                var armed: LinkCheckpoint? = null
                var gateIndex = 0
                val links = RoomLinkService(database, linkWriteClock) { point, index ->
                    if (point == armed && index == gateIndex) {
                        val encoded = Base64.encodeToString(JSONObject(baseline).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        instrumentation.sendStatus(2, Bundle().apply { putString("b3_ready", encoded) })
                        check(CountDownLatch(1).await(120, TimeUnit.SECONDS)) { "Host did not terminate gated process" }
                    }
                }
                if (scenario.endsWith("_BEFORE")) {
                    armed = LinkCheckpoint.LINK_WRITTEN
                    gateIndex = 1
                } else {
                    armed = LinkCheckpoint.LINKS_COMMITTED
                    gateIndex = 0
                }
                if (scenario.startsWith("LINK")) {
                    links.apply(
                        generation, transactionId, 1,
                        listOf(LinkNoteRef(note1Id, 1), LinkNoteRef(note2Id, 1), LinkNoteRef(note3Id, 1)),
                        emptyList(),
                    )
                } else {
                    links.apply(
                        generation, transactionId, 2, emptyList(),
                        listOf(LinkNoteRef(note1Id, 2), LinkNoteRef(note2Id, 2)),
                    )
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
            val expected = expectedSnapshot(baseline)
            val database = linkDatabase(name)
            try {
                val links = RoomLinkService(database, linkWriteClock)
                if (scenario.endsWith("_BEFORE")) {
                    assertEquals(baseline, observed)
                    assertEquals(baseline, SchemaDiskObserver.snapshot(path))
                } else {
                    assertEquals(expected, observed)
                    expectLinkError(LinkError.VersionConflict) {
                        links.apply(
                            generation, transactionId, version(baseline, "record_transaction", transactionId), emptyList(),
                            listOf(LinkNoteRef(note1Id, version(baseline, "note", note1Id))),
                        )
                    }
                    expectLinkError(LinkError.VersionConflict) {
                        links.apply(
                            generation, transactionId, version(baseline, "record_transaction", transactionId) + 1, emptyList(),
                            listOf(LinkNoteRef(note1Id, version(baseline, "note", note1Id))),
                        )
                    }
                    assertEquals(expected, SchemaDiskObserver.snapshot(path))
                }
                val transactionLinks = links.transactionLinks(generation, transactionId)
                when (scenario) {
                    "LINK_BEFORE" -> {
                        assertEquals(emptyList<String>(), transactionLinks.notes.map { it.id })
                        assertEquals(0, transactionLinks.visibleLinkCount)
                        assertEquals(0, links.noteLinks(generation, note1Id).transactions.size)
                    }
                    "REMOVE_BEFORE", "LINK_AFTER" -> {
                        assertEquals(setOf(note1Id, note2Id, note3Id), transactionLinks.notes.map { it.id }.toSet())
                        assertEquals(3, transactionLinks.visibleLinkCount)
                        assertEquals(listOf(transactionId), links.noteLinks(generation, note1Id).transactions.map { it.id })
                    }
                    "REMOVE_AFTER" -> {
                        assertEquals(listOf(note3Id), transactionLinks.notes.map { it.id })
                        assertEquals(1, transactionLinks.visibleLinkCount)
                        assertEquals(0, links.noteLinks(generation, note1Id).transactions.size)
                        assertEquals(listOf(transactionId), links.noteLinks(generation, note3Id).transactions.map { it.id })
                    }
                }
            } finally { database.close() }
        }
    }

    private fun version(snapshot: Map<String, List<List<Any?>>>, table: String, id: String): Long {
        val row = snapshot.getValue(table).single { it[0] == id }
        return (if (table == "note") row[3] else row[8]) as Long
    }

    private fun expectedSnapshot(baseline: Map<String, List<List<Any?>>>): Map<String, List<List<Any?>>> {
        val changedNotes = if (scenario.startsWith("LINK")) setOf(note1Id, note2Id, note3Id) else setOf(note1Id, note2Id)
        val transaction = baseline.getValue("record_transaction").single().toMutableList().apply {
            set(8, (get(8) as Long) + 1)
            set(10, linkWriteClock.millis())
        }
        val notes = baseline.getValue("note").map { row ->
            if ((row[0] as String) in changedNotes) row.toMutableList().apply {
                set(3, (get(3) as Long) + 1)
                set(5, linkWriteClock.millis())
            } else row
        }
        val links = baseline.getValue("transaction_note_link").toMutableList().apply {
            if (scenario.startsWith("LINK")) {
                add(listOf<Any?>(transactionId, note1Id))
                add(listOf<Any?>(transactionId, note2Id))
                add(listOf<Any?>(transactionId, note3Id))
            } else {
                removeAll { it[0] == transactionId && (it[1] == note1Id || it[1] == note2Id) }
            }
            sortWith(compareBy({ it[0].toString() }, { it[1].toString() }))
        }
        val state = baseline.getValue("record_data_state").single().toMutableList().apply {
            set(2, (get(2) as Long) + 1)
        }
        return baseline.toMutableMap().apply {
            put("record_transaction", listOf(transaction))
            put("note", notes)
            put("transaction_note_link", links)
            put("record_data_state", listOf(state))
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
