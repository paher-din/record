package com.paherdin.record2.storage

import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.records.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FinanceProcessContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val name get() = args.getString("databaseName") ?: error("Missing databaseName")
    private val scenario get() = args.getString("scenario") ?: error("Missing scenario")
    private val path get() = instrumentation.targetContext.getDatabasePath(name)

    @Test fun prepare() {
        runBlocking {
            check(!path.exists()) { "Prepare cannot overwrite an existing database" }
            val database = financeDatabase(name)
            try {
                database.openHelper.writableDatabase
                var baseline = SchemaDiskObserver.snapshot(path)
                var armed: FinanceCheckpoint? = if (scenario == "INIT") FinanceCheckpoint.ACCOUNT_SEEDED else null
                val service = RoomFinanceService(database, financeClock, checkpoint = { point ->
                    if (point == armed) {
                        val encoded = Base64.encodeToString(JSONObject(baseline).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        instrumentation.sendStatus(2, Bundle().apply { putString("b1_ready", encoded) })
                        check(CountDownLatch(1).await(120, TimeUnit.SECONDS)) { "Host did not terminate gated process" }
                    }
                })
                val catalog = service.initialize()
                var draft = service.beginNewDraft(catalog.generation, financeInput(catalog))
                if (scenario == "EDIT_WRITE") {
                    service.saveDraft(catalog.generation, draft.id, draft.revision)
                    draft = service.beginEditDraft(catalog.generation, draft.recordId, 1, "Asia/Shanghai")
                    draft = service.updateDraft(catalog.generation, draft.id, draft.revision, draft.input.copy(amountText = "60.50"))
                }
                baseline = SchemaDiskObserver.snapshot(path)
                armed = when (scenario) {
                    "DRAFT_BEFORE" -> FinanceCheckpoint.DRAFT_WRITTEN
                    "DRAFT_AFTER" -> FinanceCheckpoint.DRAFT_COMMITTED
                    "SAVE_WRITE", "EDIT_WRITE" -> FinanceCheckpoint.TRANSACTION_WRITTEN
                    "SAVE_CONSUME" -> FinanceCheckpoint.DRAFT_CONSUMED
                    "SAVE_AFTER" -> FinanceCheckpoint.SAVE_COMMITTED
                    else -> error("Unsupported process scenario")
                }
                if (scenario.startsWith("DRAFT_")) {
                    service.updateDraft(catalog.generation, draft.id, draft.revision, draft.input.copy(amountText = "60.50"))
                } else {
                    service.saveDraft(catalog.generation, draft.id, draft.revision)
                }
                error("Expected process gate")
            } finally { database.close() }
        }
    }

    @Test fun verifyAfterHostRestart() {
        runBlocking {
            check(path.isFile) { "Verify must not create a missing database" }
            val baselineJson = String(Base64.decode(args.getString("baseline") ?: error("Missing baseline"), Base64.DEFAULT), Charsets.UTF_8)
            val baseline = decodeSnapshot(JSONObject(baselineJson))
            val observed = SchemaDiskObserver.snapshot(path)
            val database = financeDatabase(name)
            try {
                val service = RoomFinanceService(database, financeClock)
                if (scenario == "INIT") {
                    assertEquals(baseline, observed)
                    assertTrue(observed.values.all { it.isEmpty() })
                    assertEquals(9, service.initialize().categories.size)
                    return@runBlocking
                }
                val oldDraft = baseline.getValue("native_finance_draft").single()
                val generation = baseline.getValue("record_data_state").single()[1] as String
                val id = oldDraft[1] as String
                val revision = oldDraft[2] as Long
                val recordId = oldDraft[5] as String
                if (scenario == "DRAFT_AFTER") {
                    assertEquals(baseline - "native_finance_draft", observed - "native_finance_draft")
                    val newDraft = observed.getValue("native_finance_draft").single()
                    assertEquals(revision + 1, newDraft[2])
                    val expectedPayload = JSONObject(oldDraft[7] as String)
                    expectedPayload.getJSONObject("input").put("amountText", "60.50")
                    assertEquals(jsonValue(expectedPayload), jsonValue(JSONObject(newDraft[7] as String)))
                    assertEquals(oldDraft.toMutableList().apply { set(2, revision + 1); set(7, newDraft[7]) }, newDraft)
                    assertEquals(recordId, service.readDraft(generation)?.recordId)
                    assertEquals(revision + 1, service.readDraft(generation)?.revision)
                } else if (scenario == "SAVE_AFTER") {
                    assertEquals(baseline - setOf("record_transaction", "native_finance_draft", "record_data_state"), observed - setOf("record_transaction", "native_finance_draft", "record_data_state"))
                    assertEquals(listOf(recordId, "EXPENSE", 5800L, "CNY", FinanceSeeds.ACCOUNT_ID, null, "午饭\n原样保留  ", 1792729800000L, 1L, financeClock.millis(), financeClock.millis()), observed.getValue("record_transaction").single())
                    val saved = observed.getValue("native_finance_draft").single()
                    assertEquals("SAVED", saved[3])
                    assertEquals(revision + 1, saved[2])
                    assertEquals(oldDraft.toMutableList().apply { set(2, revision + 1); set(3, "SAVED"); set(9, saved[9]) }, saved)
                    val oldState = baseline.getValue("record_data_state").single()
                    assertEquals(oldState.toMutableList().apply { set(2, (oldState[2] as Long) + 1) }, observed.getValue("record_data_state").single())
                    val receipt = JSONObject(saved[9] as String)
                    assertEquals(1, receipt.getInt("formatVersion"))
                    assertEquals(id, receipt.getString("draftId"))
                    assertEquals(revision, receipt.getLong("confirmedDraftRevision"))
                    assertEquals(jsonValue(JSONObject(oldDraft[7] as String).getJSONObject("input")), jsonValue(receipt.getJSONObject("input")))
                    assertEquals(mapOf("occurredAt" to 1792729800000L, "zoneId" to "Asia/Shanghai", "offsetSeconds" to 28800L), jsonValue(receipt.getJSONObject("resolvedTime")))
                    assertEquals(mapOf(
                        "id" to recordId, "direction" to "EXPENSE", "amountMinor" to 5800L,
                        "accountId" to FinanceSeeds.ACCOUNT_ID, "categoryId" to null,
                        "memo" to "午饭\n原样保留  ", "occurredAt" to 1792729800000L,
                        "version" to 1L, "createdAt" to financeClock.millis(), "updatedAt" to financeClock.millis(),
                    ), jsonValue(receipt.getJSONObject("record")))
                    assertNull(service.readDraft(generation))
                    assertEquals(recordId, service.saveDraft(generation, id, revision).record.id)
                    assertEquals(observed, SchemaDiskObserver.snapshot(path))
                } else {
                    assertEquals(baseline, observed)
                    val draft = service.readDraft(generation)!!
                    assertEquals(id, draft.id)
                    assertEquals(revision, draft.revision)
                    assertEquals(recordId, draft.recordId)
                    if (scenario == "DRAFT_BEFORE") {
                        assertEquals("58.00", draft.input.amountText)
                    } else {
                        val receipt = service.saveDraft(generation, id, revision)
                        assertEquals(recordId, receipt.record.id)
                        assertEquals(if (scenario == "EDIT_WRITE") 6050L else 5800L, receipt.record.amountMinor)
                        assertEquals(1, SchemaDiskObserver.snapshot(path).getValue("record_transaction").size)
                        assertEquals(receipt, service.saveDraft(generation, id, revision))
                    }
                }
            } finally { database.close() }
        }
    }

    private fun decodeSnapshot(json: JSONObject): Map<String, List<List<Any?>>> = json.keys().asSequence().associateWith { table ->
        val rows = json.getJSONArray(table)
        (0 until rows.length()).map { index ->
            val row: JSONArray = rows.getJSONArray(index)
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

    private fun jsonValue(value: Any): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        is Number -> value.toLong()
        is String, is Boolean -> value
        else -> error("Unexpected JSON value")
    }
}
