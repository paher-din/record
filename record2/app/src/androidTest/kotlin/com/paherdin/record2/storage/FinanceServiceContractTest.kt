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

class FinanceServiceContractTest {
    private val name = "b1-contract-${schemaId()}.db"
    private val path get() = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)
    private lateinit var database: RecordDatabase
    private lateinit var service: RoomFinanceService

    @Before fun open() {
        check(!path.exists())
        database = financeDatabase(name)
        database.openHelper.writableDatabase
        service = RoomFinanceService(database, financeClock)
    }

    @After fun close() { database.close() }
    private fun snapshot() = SchemaDiskObserver.snapshot(path)
    private fun rows(table: String) = snapshot().getValue(table)
    private fun reopen() { database.close(); database = financeDatabase(name); service = RoomFinanceService(database, financeClock) }

    @Test fun initializationIsAtomicAtEverySeedAndRemainsIdempotentAfterReopen() = runBlocking {
        val empty = snapshot()
        for (target in 0..9) {
            repeat(2) {
                var count = 0
                val failing = RoomFinanceService(database, financeClock, checkpoint = { point ->
                    if (point == FinanceCheckpoint.ACCOUNT_SEEDED || point == FinanceCheckpoint.CATEGORY_SEEDED) {
                        if (count++ == target) throw IllegalStateException("Synthetic seed failure")
                    }
                })
                try { failing.initialize(); fail("Initialization must fail") } catch (_: IllegalStateException) { }
                assertEquals(empty, snapshot())
            }
        }
        val catalog = service.initialize()
        assertEquals(1, rows("account").size)
        assertEquals(9, rows("category").size)
        assertEquals(listOf("餐饮", "出行", "购物", "居家", "学习", "其他支出", "生活费", "工资", "其他收入"), catalog.categories.map { it.name })
        val renamed = service.renameAccount(catalog.generation, 1, "我的日常账户")
        assertEquals(2L, renamed.version)
        val before = snapshot()
        reopen()
        val again = service.initialize()
        assertEquals(renamed, again.account)
        assertEquals(catalog.generation, again.generation)
        assertEquals(before, snapshot())
        database.openHelper.writableDatabase.execSQL("DELETE FROM record_data_state")
        val missingIdentity = snapshot()
        expectFinanceError(FinanceError.RecoveryRequired) { service.initialize() }
        assertEquals(missingIdentity, snapshot())
    }

    @Test fun createEditIncomeAndDuplicateSaveUseRealFactsAndOneStableIdentity() = runBlocking {
        val catalog = service.initialize()
        val draft = service.beginNewDraft(catalog.generation, financeInput(catalog))
        assertTrue(rows("record_transaction").isEmpty())
        val receipt = service.saveDraft(catalog.generation, draft.id, draft.revision)
        assertEquals(receipt, service.saveDraft(catalog.generation, draft.id, draft.revision))
        assertNull(service.readDraft(catalog.generation))
        val physical = rows("record_transaction").single()
        assertEquals(listOf(draft.recordId, "EXPENSE", 5800L, "CNY", catalog.account.id, null, "午饭\n原样保留  ", 1792729800000L, 1L, financeClock.millis(), financeClock.millis()), physical)
        val edit = service.beginEditDraft(catalog.generation, draft.recordId, 1, "Asia/Shanghai")
        assertEquals(FinanceDraftMode.EDIT, edit.mode)
        val revised = service.updateDraft(catalog.generation, edit.id, edit.revision, edit.input.copy(amountText = "60.50"))
        val edited = service.saveDraft(catalog.generation, revised.id, revised.revision)
        assertEquals(draft.recordId, edited.record.id)
        assertEquals(2L, edited.record.version)
        assertEquals(6050L, rows("record_transaction").single()[2])
        val income = service.beginNewDraft(catalog.generation, financeInput(catalog, "100").copy(
            direction = FinanceDirection.INCOME, categoryId = catalog.categories.last().id, categoryVersion = 1,
        ))
        service.saveDraft(catalog.generation, income.id, income.revision)
        assertEquals(listOf(6050L, 10000L), rows("record_transaction").map { it[2] as Long }.sorted())
        assertNull(rows("record_transaction").single { it[1] == "EXPENSE" }[5])
        assertEquals(catalog.categories.last().id, rows("record_transaction").single { it[1] == "INCOME" }[5])
        val before = snapshot()
        reopen()
        assertEquals(edited.record, service.transaction(catalog.generation, draft.recordId))
        assertEquals(before, snapshot())
    }

    @Test fun invalidInputsPreserveEveryFactAndDraftWithNoPartialSave() = runBlocking {
        val catalog = service.initialize()
        val input = financeInput(catalog)
        var draft = service.beginNewDraft(catalog.generation, input)
        val invalid = listOf("", "0", "-1", "1.234", "1e2", "1,000", "92233720368547758.08").map { input.copy(amountText = it) to FinanceError.Validation } + listOf(
            input.copy(localDateTime = "2026-10-24T00:00") to FinanceError.Validation,
            input.copy(localDateTime = "2026-03-08T02:30", zoneId = "America/New_York") to FinanceError.Validation,
            input.copy(accountId = schemaId()) to FinanceError.Validation,
            input.copy(accountVersion = 2) to FinanceError.VersionConflict,
            input.copy(categoryVersion = 1) to FinanceError.Validation,
            input.copy(categoryId = schemaId(), categoryVersion = 1) to FinanceError.NotFound,
            input.copy(categoryId = catalog.categories.last().id, categoryVersion = 1) to FinanceError.Validation,
            input.copy(categoryId = catalog.categories.first().id, categoryVersion = 2) to FinanceError.VersionConflict,
        )
        for ((badInput, error) in invalid) {
            draft = service.updateDraft(catalog.generation, draft.id, draft.revision, badInput)
            val before = snapshot()
            expectFinanceError(error) { service.saveDraft(catalog.generation, draft.id, draft.revision) }
            assertEquals(before, snapshot())
            assertEquals(badInput, service.readDraft(catalog.generation)?.input)
        }
        draft = service.updateDraft(catalog.generation, draft.id, draft.revision, input.copy(
            amountText = "92233720368547758.07", categoryId = catalog.categories.first().id, categoryVersion = 1,
        ))
        val maximum = service.saveDraft(catalog.generation, draft.id, draft.revision)
        assertEquals(Long.MAX_VALUE, maximum.record.amountMinor)
        assertEquals(Long.MAX_VALUE, rows("record_transaction").single()[2])
        assertEquals(catalog.categories.first().id, rows("record_transaction").single()[5])
        assertEquals(maximum, service.saveDraft(catalog.generation, draft.id, draft.revision))
    }

    @Test fun activeDraftStaleRevisionDiscardAndReplacementCannotOverwriteAnotherDraft() = runBlocking {
        val catalog = service.initialize()
        val draft = service.beginNewDraft(catalog.generation, financeInput(catalog, ""))
        expectFinanceError(FinanceError.DraftExists) { service.beginNewDraft(catalog.generation, financeInput(catalog)) }
        val current = service.updateDraft(catalog.generation, draft.id, draft.revision, draft.input.copy(amountText = "58"))
        val before = snapshot()
        expectFinanceError(FinanceError.DraftConflict) { service.updateDraft(catalog.generation, draft.id, draft.revision, draft.input) }
        expectFinanceError(FinanceError.DraftConflict) { service.discardDraft(catalog.generation, draft.id, draft.revision) }
        assertEquals(before, snapshot())
        service.discardDraft(catalog.generation, current.id, current.revision)
        service.discardDraft(catalog.generation, current.id, current.revision)
        assertNull(service.readDraft(catalog.generation))
        assertEquals("{}", rows("native_finance_draft").single()[7])
        val next = service.beginNewDraft(catalog.generation, financeInput(catalog, "100"))
        assertTrue(next.revision > current.revision)
        assertNotEquals(draft.recordId, next.recordId)
        expectFinanceError(FinanceError.DraftConflict) { service.saveDraft(catalog.generation, current.id, current.revision) }
        assertEquals(next, service.readDraft(catalog.generation))
    }

    @Test fun parallelInitializationAndSavesDoNotCreateDuplicateSeedsOrRecords() = runBlocking {
        val second = financeDatabase(name)
        try {
            val other = RoomFinanceService(second, financeClock)
            val catalogs = coroutineScope { listOf(async { service.initialize() }, async { other.initialize() }).awaitAll() }
            assertEquals(catalogs[0], catalogs[1])
            val catalog = catalogs.first()
            val draft = service.beginNewDraft(catalog.generation, financeInput(catalog))
            val receipts = coroutineScope { listOf(async { service.saveDraft(catalog.generation, draft.id, draft.revision) }, async { other.saveDraft(catalog.generation, draft.id, draft.revision) }).awaitAll() }
            assertEquals(receipts[0], receipts[1])
            assertEquals(1, rows("record_transaction").size)
            assertEquals(1, rows("account").size)
            assertEquals(9, rows("category").size)
        } finally { second.close() }
    }

    @Test fun failuresAtBusinessWriteAndDraftConsumptionRollbackThenSameDraftRetries() = runBlocking {
        val catalog = service.initialize()
        val draft = service.beginNewDraft(catalog.generation, financeInput(catalog))
        val before = snapshot()
        for (point in listOf(FinanceCheckpoint.TRANSACTION_WRITTEN, FinanceCheckpoint.DRAFT_CONSUMED)) {
            val failing = RoomFinanceService(database, financeClock, checkpoint = { if (it == point) throw SQLiteFullException("Synthetic full disk") })
            repeat(2) {
                expectFinanceError(FinanceError.StorageFull) { failing.saveDraft(catalog.generation, draft.id, draft.revision) }
                assertEquals(before, snapshot())
            }
            reopen()
            assertEquals(before, snapshot())
        }
        val receipt = service.saveDraft(catalog.generation, draft.id, draft.revision)
        assertEquals(draft.recordId, receipt.record.id)
        assertEquals(1, rows("record_transaction").size)
    }

    @Test fun coroutineCancellationInsideRealTransactionRollsBackBusinessAndDraft() = runBlocking {
        val catalog = service.initialize()
        val draft = service.beginNewDraft(catalog.generation, financeInput(catalog))
        val before = snapshot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelling = RoomFinanceService(database, financeClock, checkpoint = {
            if (it == FinanceCheckpoint.DRAFT_CONSUMED) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        val job = launch { cancelling.saveDraft(catalog.generation, draft.id, draft.revision) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            assertEquals(before, snapshot())
            job.cancel()
        } finally { release.countDown() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, snapshot())
        assertEquals(draft.recordId, service.saveDraft(catalog.generation, draft.id, draft.revision).record.id)
    }

    @Test fun lostCommitReceiptReturnsOutcomeUnknownAndSameIdentityRecoversExactlyOnce() = runBlocking {
        val catalog = service.initialize()
        val draft = service.beginNewDraft(catalog.generation, financeInput(catalog))
        val failing = RoomFinanceService(database, financeClock, checkpoint = { if (it == FinanceCheckpoint.SAVE_COMMITTED) throw IllegalStateException("Synthetic lost response") })
        expectFinanceError(FinanceError.OutcomeUnknown) { failing.saveDraft(catalog.generation, draft.id, draft.revision) }
        assertNull(service.readDraft(catalog.generation))
        val before = snapshot()
        reopen()
        val receipt = service.saveDraft(catalog.generation, draft.id, draft.revision)
        assertEquals(draft.recordId, receipt.record.id)
        assertEquals(1, rows("record_transaction").size)
        assertEquals(before, snapshot())
    }

    @Test fun editConflictAndFailureLeaveOldFieldsAndLocalDraftUntouched() = runBlocking {
        val catalog = service.initialize()
        val create = service.beginNewDraft(catalog.generation, financeInput(catalog))
        service.saveDraft(catalog.generation, create.id, create.revision)
        val edit = service.beginEditDraft(catalog.generation, create.recordId, 1, "Asia/Shanghai")
        val draft = service.updateDraft(catalog.generation, edit.id, edit.revision, edit.input.copy(amountText = "60.50", memo = "未落盘的本地说明"))
        val before = snapshot()
        val failing = RoomFinanceService(database, financeClock, checkpoint = { if (it == FinanceCheckpoint.TRANSACTION_WRITTEN) throw SQLiteFullException("Synthetic failure") })
        expectFinanceError(FinanceError.StorageFull) { failing.saveDraft(catalog.generation, draft.id, draft.revision) }
        assertEquals(before, snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE record_transaction SET version=2,memo='外部新说明' WHERE id=?", arrayOf(create.recordId))
        val changed = snapshot()
        expectFinanceError(FinanceError.VersionConflict) { service.saveDraft(catalog.generation, draft.id, draft.revision) }
        assertEquals(changed, snapshot())
        assertEquals(draft.input, service.readDraft(catalog.generation)?.input)
        expectFinanceError(FinanceError.GenerationMismatch) { service.updateDraft(schemaId(), draft.id, draft.revision, draft.input) }
        assertEquals(changed, snapshot())
    }

    @Test fun unchangedEditConsumesDraftWithoutAdvancingBusinessVersion() = runBlocking {
        val catalog = service.initialize()
        val create = service.beginNewDraft(catalog.generation, financeInput(catalog))
        val receipt = service.saveDraft(catalog.generation, create.id, create.revision)
        val revision = rows("record_data_state").single()[2]
        val edit = service.beginEditDraft(catalog.generation, create.recordId, 1, "Asia/Shanghai")
        assertEquals(receipt.record, service.saveDraft(catalog.generation, edit.id, edit.revision).record)
        assertEquals(revision, rows("record_data_state").single()[2])
    }

    @Test fun draftWriteFailureRetainsLastPersistedInputAndCommittedDraftCanBeReadAfterReopen() = runBlocking {
        val catalog = service.initialize()
        val draft = service.beginNewDraft(catalog.generation, financeInput(catalog, ""))
        val before = snapshot()
        val failing = RoomFinanceService(database, financeClock, checkpoint = { if (it == FinanceCheckpoint.DRAFT_WRITTEN) throw SQLiteFullException("Synthetic failure") })
        repeat(2) {
            expectFinanceError(FinanceError.StorageFull) { failing.updateDraft(catalog.generation, draft.id, draft.revision, draft.input.copy(amountText = "58")) }
            assertEquals(before, snapshot())
        }
        val persisted = service.updateDraft(catalog.generation, draft.id, draft.revision, draft.input.copy(amountText = "60.50"))
        reopen()
        assertEquals(persisted, service.readDraft(catalog.generation))
        assertTrue(rows("record_transaction").isEmpty())
    }

    @Test fun corruptDraftAndReceiptAndVersionOverflowFailClosed() = runBlocking {
        val catalog = service.initialize()
        val draft = service.beginNewDraft(catalog.generation, financeInput(catalog))
        database.openHelper.writableDatabase.execSQL("UPDATE native_finance_draft SET state='UNRECOGNIZED'")
        val badState = snapshot()
        expectFinanceError(FinanceError.RecoveryRequired) { service.readDraft(catalog.generation) }
        assertEquals(badState, snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE native_finance_draft SET state='EDITING'")
        database.openHelper.writableDatabase.execSQL("UPDATE native_finance_draft SET inputJson='{}'")
        val corrupt = snapshot()
        expectFinanceError(FinanceError.RecoveryRequired) { service.readDraft(catalog.generation) }
        expectFinanceError(FinanceError.RecoveryRequired) { service.saveDraft(catalog.generation, draft.id, draft.revision) }
        assertEquals(corrupt, snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE native_finance_draft SET state='SAVED',saveReceiptJson='{}'")
        expectFinanceError(FinanceError.RecoveryRequired) { service.saveDraft(catalog.generation, draft.id, draft.revision) }
        database.openHelper.writableDatabase.execSQL("UPDATE account SET version=?", arrayOf(Long.MAX_VALUE))
        val full = snapshot()
        expectFinanceError(FinanceError.VersionConflict) { service.renameAccount(catalog.generation, Long.MAX_VALUE, "溢出拒绝") }
        assertEquals(full, snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE native_finance_draft SET state='DISCARDED'")
        database.openHelper.writableDatabase.execSQL("UPDATE account SET version=1")
        val next = service.beginNewDraft(catalog.generation, financeInput(catalog))
        database.openHelper.writableDatabase.execSQL("UPDATE record_data_state SET businessRevision=?", arrayOf(Long.MAX_VALUE))
        val revisionOverflow = snapshot()
        expectFinanceError(FinanceError.VersionConflict) { service.saveDraft(catalog.generation, next.id, next.revision) }
        assertEquals(revisionOverflow, snapshot())
    }

    @Test fun nativeEditorDebouncesFlushesOnLeaveAndRetainsInputAfterDraftFailure() = runBlocking {
        val catalog = service.initialize()
        val draft = service.beginNewDraft(catalog.generation, financeInput(catalog, ""))
        val editor = FinanceDraftEditor(service, catalog.generation, draft, this)
        assertTrue(editor.edit(draft.input.copy(amountText = "58")))
        assertTrue(editor.edit(draft.input.copy(amountText = "60.50")))
        withTimeout(5000) {
            while (service.readDraft(catalog.generation)?.input?.amountText != "60.50") delay(20)
        }
        assertEquals(draft.revision + 1, editor.state.value.persisted.revision)
        assertTrue(editor.edit(editor.state.value.input.copy(memo = "离开前补写")))
        assertTrue(editor.leave())
        assertEquals("离开前补写", service.readDraft(catalog.generation)?.input?.memo)
        val current = service.readDraft(catalog.generation)!!
        val failing = RoomFinanceService(database, financeClock, checkpoint = { if (it == FinanceCheckpoint.DRAFT_WRITTEN) throw SQLiteFullException("Synthetic failure") })
        val failedEditor = FinanceDraftEditor(failing, catalog.generation, current, this)
        val local = current.input.copy(amountText = "100")
        assertTrue(failedEditor.edit(local))
        assertFalse(failedEditor.leave())
        assertEquals(local, failedEditor.state.value.input)
        assertEquals(FinanceError.StorageFull, failedEditor.state.value.error)
        assertEquals(current, service.readDraft(catalog.generation))
    }

    @Test fun nativeEditorLocksDuplicateSaveAndLeaveUntilTheActualCommitReturns() = runBlocking {
        val catalog = service.initialize()
        val draft = service.beginNewDraft(catalog.generation, financeInput(catalog))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = RoomFinanceService(database, financeClock, checkpoint = {
            if (it == FinanceCheckpoint.TRANSACTION_WRITTEN) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        val editor = FinanceDraftEditor(gated, catalog.generation, draft, this)
        val saving = async { editor.save() }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            assertTrue(editor.state.value.saving)
            assertNull(editor.state.value.saved)
            assertFalse(editor.edit(draft.input.copy(amountText = "100")))
            assertFalse(editor.leave())
            assertNull(editor.save())
            assertTrue(rows("record_transaction").isEmpty())
        } finally { release.countDown() }
        val receipt = saving.await()!!
        assertEquals(receipt, editor.state.value.saved)
        assertFalse(editor.state.value.saving)
        assertEquals(receipt, editor.save())
        assertEquals(1, rows("record_transaction").size)
    }
}
