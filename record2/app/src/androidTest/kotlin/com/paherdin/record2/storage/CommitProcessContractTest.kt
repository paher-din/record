package com.paherdin.record2.storage

import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.proposals.AppliedCommitResult
import com.paherdin.record2.proposals.AppliedOutcome
import com.paherdin.record2.proposals.CommitCheckpoint
import com.paherdin.record2.proposals.CommitResultCodec
import com.paherdin.record2.proposals.CommitStatus
import com.paherdin.record2.proposals.ConfirmationCommand
import com.paherdin.record2.records.FinanceSeeds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-driven process termination at the durable boundaries of A and B. The host kills the
 * process while it parks at the gated checkpoint; verifyAfterHostRestart reopens the same file
 * read-only-first and reconciles by operation identity, never re-seeding or recreating.
 */
class CommitProcessContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val name get() = args.getString("databaseName") ?: error("Missing databaseName")
    private val scenario get() = args.getString("scenario") ?: error("Missing scenario")
    private val path get() = instrumentation.targetContext.getDatabasePath(name)

    @Test
    fun prepare() {
        runBlocking {
            check(!path.exists())
            val database = commitProcessDatabase(name)
            try {
                val fixture = ProposalFixture(database)
                fixture.seed()
                val proposals = RoomProposalService(database, proposalClock)
                val view = proposals.prepare(
                    fixture.context(fixture.run),
                    listOf(fixture.createTransaction(), fixture.createNote(), fixture.linkNew()),
                )
                val command = ConfirmationCommand(view.id, view.revision, view.payloadDigest, fixture.generation)
                var baseline = SchemaDiskObserver.snapshot(path)
                var armed: CommitCheckpoint? = null
                val commits = RoomCommitService(database, proposalClock, queuedIds(*CommitGolden.ids().toTypedArray())) { point, _ ->
                    if (point == armed) {
                        val encoded = Base64.encodeToString(JSONObject(baseline).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        instrumentation.sendStatus(2, Bundle().apply { putString("c1_ready", encoded) })
                        check(CountDownLatch(1).await(120, TimeUnit.SECONDS)) { "Host did not terminate gated process" }
                    }
                }
                armed = when (scenario) {
                    "A_MID" -> CommitCheckpoint.OPERATION_WRITTEN
                    "A_AFTER" -> CommitCheckpoint.A_COMMITTED
                    "B_MID" -> CommitCheckpoint.BUSINESS_WRITTEN
                    "B_AFTER" -> CommitCheckpoint.B_COMMITTED
                    else -> error("Unsupported scenario")
                }
                when (scenario) {
                    "A_MID", "A_AFTER" -> commits.confirm(command)
                    "B_MID", "B_AFTER" -> {
                        commits.confirm(command)
                        baseline = SchemaDiskObserver.snapshot(path)
                        commits.execute(CommitGolden.OPERATION)
                    }
                    else -> error("Unsupported scenario")
                }
                error("Expected process gate")
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun verifyAfterHostRestart() {
        runBlocking {
            check(path.isFile) { "Verification cannot create a database" }
            val baseline = decodeSnapshot(JSONObject(String(Base64.decode(args.getString("baseline") ?: error("Missing baseline"), Base64.DEFAULT), Charsets.UTF_8)))
            val observed = SchemaDiskObserver.snapshot(path)
            val database = commitProcessDatabase(name)
            try {
                val commits = RoomCommitService(database, proposalClock)
                val proposalRow = baseline.getValue("change_proposal").single()
                val generation = baseline.getValue("record_data_state").single()[1] as String
                val command = ConfirmationCommand(proposalRow[0] as String, proposalRow[6] as Long, proposalRow[8] as String, generation)
                when (scenario) {
                    "A_MID" -> {
                        // The killed A transaction rolled back completely; nothing was created.
                        assertEquals(baseline, observed)
                        assertTrue(observed.getValue("commit_operation").isEmpty())
                        // The recovery confirmation mints the same deterministic identities the
                        // killed attempt would have, so the fact assertions stay exact.
                        val recovering = RoomCommitService(database, proposalClock, queuedIds(*CommitGolden.ids().toTypedArray()))
                        val fresh = recovering.confirm(command)
                        assertTrue(fresh.fresh)
                        assertEquals(CommitStatus.Pending, fresh.status)
                        assertEquals(CommitGolden.OPERATION, fresh.operationId)
                        val applied = recovering.execute(CommitGolden.OPERATION)
                        assertEquals(CommitStatus.Applied, applied.status)
                        assertAppliedFacts(baseline)
                    }
                    "A_AFTER" -> {
                        // A committed before the kill: the frozen operation and the LOCKED proposal persist.
                        val expected = expectedAfterConfirm(baseline, generation, proposalRow)
                        assertEquals(expected, observed)
                        val verified = commits.confirm(command)
                        assertEquals(CommitGolden.OPERATION, verified.operationId)
                        assertTrue(!verified.fresh)
                        assertEquals(CommitStatus.Pending, verified.status)
                        assertEquals(expected, SchemaDiskObserver.snapshot(path))
                        val applied = commits.execute(CommitGolden.OPERATION)
                        assertEquals(CommitStatus.Applied, applied.status)
                        assertAppliedFacts(baseline)
                        assertEquals(1, SchemaDiskObserver.snapshot(path).getValue("commit_operation").size)
                    }
                    "B_MID" -> {
                        // The killed B transaction left the pre-B state untouched; the owner's
                        // same-id retry applies the whole payload exactly once.
                        assertEquals(baseline, observed)
                        assertEquals("Pending", observed.getValue("commit_operation").single()[7])
                        val applied = commits.execute(CommitGolden.OPERATION)
                        assertEquals(CommitStatus.Applied, applied.status)
                        assertAppliedFacts(baseline)
                        val after = SchemaDiskObserver.snapshot(path)
                        commits.execute(CommitGolden.OPERATION)
                        assertEquals(after, SchemaDiskObserver.snapshot(path))
                    }
                    "B_AFTER" -> {
                        // B committed before the kill: the full fact set and the Applied receipt persist.
                        val expected = expectedAfterExecute(expectedAfterConfirm(baseline, generation, proposalRow))
                        assertEquals(expected, observed)
                        val inspected = commits.inspect(CommitGolden.OPERATION)!!
                        assertEquals(CommitStatus.Applied, inspected.status)
                        val reexecuted = commits.execute(CommitGolden.OPERATION)
                        assertEquals(inspected, reexecuted)
                        assertEquals(observed, SchemaDiskObserver.snapshot(path))
                    }
                    else -> error("Unsupported scenario")
                }
            } finally {
                database.close()
            }
        }
    }

    private fun assertAppliedFacts(baseline: Map<String, List<List<Any?>>>) {
        val after = SchemaDiskObserver.snapshot(path)
        val created = after.getValue("record_transaction").single { it[0] == CommitGolden.NEW_TRANSACTION }
        assertEquals(2L, created[8])
        val createdNote = after.getValue("note").single { it[0] == CommitGolden.NEW_NOTE }
        assertEquals(2L, createdNote[3])
        assertEquals(1, after.getValue("transaction_note_link").size - baseline.getValue("transaction_note_link").size)
        assertEquals((baseline.getValue("record_data_state").single()[2] as Long) + 1, after.getValue("record_data_state").single()[2])
        val operation = after.getValue("commit_operation").single()
        assertEquals("Applied", operation[7])
        assertEquals(appliedReceipt(), operation[10])
        assertEquals("APPLIED", after.getValue("change_proposal").single()[11])
        assertNull(after.getValue("change_proposal").single()[12])
    }

    private fun appliedReceipt(): String = CommitResultCodec.canonical(
        AppliedCommitResult(
            2,
            listOf(
                AppliedOutcome.CreateTransaction(CommitGolden.NEW_TRANSACTION, 1),
                AppliedOutcome.CreateNote(CommitGolden.NEW_NOTE, 1),
                AppliedOutcome.LinkRecords(1),
            ),
        ),
    )

    private fun expectedAfterConfirm(
        baseline: Map<String, List<List<Any?>>>,
        generation: String,
        proposalRow: List<Any?>,
    ): Map<String, List<List<Any?>>> = baseline.toMutableMap().apply {
        put("change_proposal", listOf(proposalRow.toMutableList().apply { set(11, "LOCKED") }))
        put(
            "commit_operation",
            listOf(
                listOf(
                    CommitGolden.OPERATION, proposalRow[0], 1L, generation, CommitGolden.FROZEN_JSON,
                    CommitGolden.FROZEN_DIGEST, 1L, "Pending", proposalClock.millis(), null, null, null,
                ),
            ),
        )
    }

    private fun expectedAfterExecute(confirmed: Map<String, List<List<Any?>>>): Map<String, List<List<Any?>>> {
        val now = proposalClock.millis()
        // The independent observer reads every table ORDER BY its first two columns.
        fun ordered(rows: List<List<Any?>>) = rows.sortedBy { "${it[0]}|${it[1]}" }
        return confirmed.toMutableMap().apply {
            val created = listOf<Any?>(
                CommitGolden.NEW_TRANSACTION, "EXPENSE", 5800L, "CNY", FinanceSeeds.ACCOUNT_ID, null, "午饭",
                1_792_729_800_000L, 2L, now, now,
            )
            put("record_transaction", ordered(getValue("record_transaction") + listOf(created)))
            put("note", ordered(getValue("note") + listOf(listOf<Any?>(CommitGolden.NEW_NOTE, "买菜记录", 1_792_729_800_000L, 2L, now, now, null))))
            put(
                "transaction_note_link",
                ordered(getValue("transaction_note_link") + listOf(listOf<Any?>(CommitGolden.NEW_TRANSACTION, CommitGolden.NEW_NOTE))),
            )
            put("record_data_state", listOf(getValue("record_data_state").single().toMutableList().apply { set(2, 2L) }))
            put("change_proposal", listOf(getValue("change_proposal").single().toMutableList().apply { set(11, "APPLIED"); set(12, null) }))
            put(
                "commit_operation",
                listOf(
                    getValue("commit_operation").single().toMutableList().apply {
                        set(7, "Applied"); set(9, now); set(10, appliedReceipt()); set(11, null)
                    },
                ),
            )
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
