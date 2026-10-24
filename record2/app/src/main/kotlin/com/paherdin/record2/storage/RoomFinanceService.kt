package com.paherdin.record2.storage

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.paherdin.record2.backup.DataOperationGate
import com.paherdin.record2.backup.ProcessDataOperationGate
import com.paherdin.record2.records.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class FinanceCheckpoint {
    ACCOUNT_SEEDED, CATEGORY_SEEDED, DRAFT_WRITTEN, DRAFT_COMMITTED,
    TRANSACTION_WRITTEN, DRAFT_CONSUMED, SAVE_COMMITTED,
}

@Serializable
private data class DraftPayload(val formatVersion: Int = 1, val input: FinanceInput)

internal class RoomFinanceService(
    private val database: RecordDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val processOwnerId: String = UUID.randomUUID().toString(),
    private val checkpoint: (FinanceCheckpoint) -> Unit = {},
) : FinanceService {
    internal var dataOperationGate: DataOperationGate = ProcessDataOperationGate.gate
    private val dao = database.records()
    private val json = Json { encodeDefaults = true }

    override suspend fun initialize(): FinanceCatalog = io {
        write {
            var state = dao.dataState()
            if (state == null) {
                // Missing identity on a populated database is corruption, not first launch.
                if (dao.hasDataWithoutGeneration()) fail(FinanceError.RecoveryRequired)
                state = RecordDataStateEntity(1, UUID.randomUUID().toString(), 0, processOwnerId, null)
                dao.insertDataState(state)
            }
            validateState(state)
            var changed = false
            val time = clock.millis()
            if (dao.account(FinanceSeeds.ACCOUNT_ID) == null) {
                dao.insertAccount(AccountEntity(FinanceSeeds.ACCOUNT_ID, "日常账户", "CNY", 1, time, time))
                changed = true
                mark(FinanceCheckpoint.ACCOUNT_SEEDED)
            }
            for (seed in FinanceSeeds.categories) {
                val existing = dao.category(seed.id)
                if (existing == null) {
                    dao.insertCategory(CategoryEntity(seed.id, seed.name, seed.direction.name, 1, time, time))
                    changed = true
                    mark(FinanceCheckpoint.CATEGORY_SEEDED)
                } else if (existing.direction != seed.direction.name || existing.version < 1 || existing.name.isBlank()) {
                    fail(FinanceError.RecoveryRequired)
                }
            }
            val account = checkedAccount()
            if (changed) advance(state)
            FinanceCatalog(state.dataGeneration, account.dto(), dao.categories().map { it.dto() })
        }
    }

    override suspend fun renameAccount(generation: String, expectedVersion: Long, name: String): FinanceAccount = io {
        write {
            val state = state(generation)
            if (name.isBlank()) fail(FinanceError.Validation)
            val account = checkedAccount()
            if (account.version != expectedVersion) fail(FinanceError.VersionConflict)
            if (name != account.name) {
                if (dao.renameAccount(account.id, expectedVersion, name, clock.millis()) != 1) fail(FinanceError.VersionConflict)
                advance(state)
            }
            checkedAccount().dto()
        }
    }

    override suspend fun beginNewDraft(generation: String, input: FinanceInput): FinanceDraft = io {
        write {
            state(generation)
            begin(FinanceDraftMode.NEW, UUID.randomUUID().toString(), null, input)
        }
    }

    override suspend fun beginEditDraft(generation: String, recordId: String, expectedVersion: Long, zoneId: String): FinanceDraft = io {
        write {
            state(generation)
            val record = record(recordId)
            if (record.version != expectedVersion) fail(FinanceError.VersionConflict)
            val local = try { Instant.ofEpochMilli(record.occurredAt).atZone(ZoneId.of(zoneId)).toLocalDateTime().toString() }
            catch (error: RuntimeException) { throw FinanceException(FinanceError.Validation, error) }
            val account = dao.account(record.accountId) ?: fail(FinanceError.NotFound)
            val category = record.categoryId?.let { dao.category(it) ?: fail(FinanceError.NotFound) }
            begin(FinanceDraftMode.EDIT, record.id, record.version, FinanceInput(
                Money.formatMinor(record.amountMinor), record.direction, account.id, account.version,
                category?.id, category?.version, record.memo, local, zoneId,
            ))
        }
    }

    override suspend fun readDraft(generation: String): FinanceDraft? = io {
        database.withTransaction {
            state(generation)
            storedDraft()?.takeIf { it.state == "EDITING" }?.dto()
        }
    }

    override suspend fun updateDraft(generation: String, draftId: String, expectedRevision: Long, input: FinanceInput): FinanceDraft = io {
        val result = write {
            state(generation)
            editing(draftId, expectedRevision)
            if (dao.reviseDraft(draftId, expectedRevision, encode(input), clock.millis()) != 1) fail(FinanceError.DraftConflict)
            mark(FinanceCheckpoint.DRAFT_WRITTEN)
            storedDraft()!!.dto()
        }
        mark(FinanceCheckpoint.DRAFT_COMMITTED)
        result
    }

    override suspend fun discardDraft(generation: String, draftId: String, expectedRevision: Long) = io {
        write {
            state(generation)
            val current = storedDraft() ?: fail(FinanceError.DraftConflict)
            if (expectedRevision in 1 until Long.MAX_VALUE && current.draftId == draftId && current.state == "DISCARDED" && current.draftRevision == expectedRevision + 1) return@write
            editing(draftId, expectedRevision)
            if (dao.finishDraft(draftId, expectedRevision, "DISCARDED", "{}", null, clock.millis()) != 1) fail(FinanceError.DraftConflict)
        }
    }

    override suspend fun saveDraft(generation: String, draftId: String, expectedRevision: Long): FinanceSaveReceipt = io {
        val receipt = write {
            val state = state(generation)
            val current = storedDraft() ?: fail(FinanceError.DraftConflict)
            if (current.draftId != draftId) fail(FinanceError.DraftConflict)
            if (current.state == "SAVED") {
                val saved = decodeReceipt(current)
                if (saved.draftId != draftId || saved.confirmedDraftRevision != expectedRevision) fail(FinanceError.DraftConflict)
                return@write saved
            }
            val draft = editing(draftId, expectedRevision).dto()
            val input = draft.input
            val amount = try { Money.parseMinor(input.amountText) }
            catch (error: IllegalArgumentException) { throw FinanceException(FinanceError.Validation, error) }
            val time = FinanceTime.resolve(input.localDateTime, input.zoneId, clock)
            if (input.accountId != FinanceSeeds.ACCOUNT_ID) fail(FinanceError.Validation)
            val account = checkedAccount()
            if (input.accountVersion != account.version) fail(FinanceError.VersionConflict)
            if (input.categoryId == null) {
                if (input.categoryVersion != null) fail(FinanceError.Validation)
            } else {
                val category = dao.category(input.categoryId) ?: fail(FinanceError.NotFound)
                if (category.direction != input.direction.name) fail(FinanceError.Validation)
                if (category.version != input.categoryVersion) fail(FinanceError.VersionConflict)
            }
            val now = clock.millis()
            if (draft.mode == FinanceDraftMode.NEW) {
                if (dao.transaction(draft.recordId) != null || draft.baseVersion != null) fail(FinanceError.RecoveryRequired)
                dao.insertTransaction(TransactionEntity(draft.recordId, input.direction.name, amount, "CNY", account.id, input.categoryId, input.memo, time.occurredAt, 1, now, now))
                advance(state)
            } else {
                val old = record(draft.recordId)
                if (old.version != draft.baseVersion) fail(FinanceError.VersionConflict)
                val changed = old.direction != input.direction || old.amountMinor != amount || old.accountId != account.id || old.categoryId != input.categoryId || old.memo != input.memo || old.occurredAt != time.occurredAt
                if (changed) {
                    if (dao.editTransaction(old.id, old.version, input.direction.name, amount, account.id, input.categoryId, input.memo, time.occurredAt, now) != 1) fail(FinanceError.VersionConflict)
                    advance(state)
                }
            }
            mark(FinanceCheckpoint.TRANSACTION_WRITTEN)
            val saved = FinanceSaveReceipt(draftId = draftId, confirmedDraftRevision = expectedRevision, input = input, resolvedTime = time, record = record(draft.recordId))
            if (dao.finishDraft(draftId, expectedRevision, "SAVED", current.inputJson, json.encodeToString(saved), now) != 1) fail(FinanceError.DraftConflict)
            mark(FinanceCheckpoint.DRAFT_CONSUMED)
            saved
        }
        try { mark(FinanceCheckpoint.SAVE_COMMITTED) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw FinanceException(FinanceError.OutcomeUnknown, error) }
        receipt
    }

    override suspend fun transaction(generation: String, id: String): FinanceRecord = io {
        database.withTransaction { state(generation); record(id) }
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        database.withTransaction { dataOperationGate.withWrite { block() } }

    private suspend fun begin(mode: FinanceDraftMode, recordId: String, baseVersion: Long?, input: FinanceInput): FinanceDraft {
        val old = storedDraft()
        if (old?.state == "EDITING") fail(FinanceError.DraftExists)
        val id = UUID.randomUUID().toString()
        if (old == null) {
            dao.insertDraft(NativeFinanceDraftEntity(1, id, 1, "EDITING", mode.name, recordId, baseVersion, encode(input), clock.millis(), null))
        } else if (dao.replaceDraft(old.draftId, old.draftRevision, id, mode.name, recordId, baseVersion, encode(input), clock.millis()) != 1) {
            fail(FinanceError.DraftConflict)
        }
        mark(FinanceCheckpoint.DRAFT_WRITTEN)
        return storedDraft()!!.dto()
    }

    private fun state(generation: String): RecordDataStateEntity {
        val state = dao.dataState() ?: fail(FinanceError.RecoveryRequired)
        validateState(state)
        if (generation != state.dataGeneration) fail(FinanceError.GenerationMismatch)
        return state
    }

    private fun validateState(state: RecordDataStateEntity) {
        if (state.businessRevision < 0 || runCatching { UUID.fromString(state.dataGeneration).toString() }.getOrNull() != state.dataGeneration) fail(FinanceError.RecoveryRequired)
    }

    private fun advance(state: RecordDataStateEntity) {
        if (dao.advanceBusinessRevision(state.businessRevision) != 1) fail(FinanceError.VersionConflict)
    }

    private fun checkedAccount(): AccountEntity {
        val account = dao.account(FinanceSeeds.ACCOUNT_ID) ?: fail(FinanceError.NotFound)
        if (account.currency != "CNY" || account.name.isBlank() || account.version < 1) fail(FinanceError.RecoveryRequired)
        return account
    }

    private fun editing(id: String, revision: Long): NativeFinanceDraftEntity {
        val draft = storedDraft() ?: fail(FinanceError.DraftConflict)
        if (draft.draftId != id || draft.draftRevision != revision || revision < 1 || revision == Long.MAX_VALUE || draft.state != "EDITING") fail(FinanceError.DraftConflict)
        return draft
    }

    private fun encode(input: FinanceInput): String = json.encodeToString(DraftPayload(input = input))

    private fun storedDraft(): NativeFinanceDraftEntity? = dao.draft()?.also { row ->
        if (row.draftRevision < 1 || row.state !in setOf("EDITING", "SAVED", "DISCARDED") ||
            (row.mode != "NEW" && row.mode != "EDIT") ||
            (row.mode == "NEW" && row.baseVersion != null) ||
            (row.mode == "EDIT" && (row.baseVersion == null || row.baseVersion < 1))) {
            fail(FinanceError.RecoveryRequired)
        }
    }

    private fun NativeFinanceDraftEntity.dto(): FinanceDraft = try {
        val payload = json.decodeFromString<DraftPayload>(inputJson)
        require(payload.formatVersion == 1 && draftRevision > 0)
        FinanceDraft(draftId, draftRevision, FinanceDraftMode.valueOf(mode), recordId, baseVersion, payload.input)
    } catch (error: RuntimeException) { throw FinanceException(FinanceError.RecoveryRequired, error) }

    private fun decodeReceipt(row: NativeFinanceDraftEntity): FinanceSaveReceipt = try {
        json.decodeFromString<FinanceSaveReceipt>(row.saveReceiptJson ?: error("Missing receipt")).also {
            require(it.formatVersion == 1 && it.draftId == row.draftId && it.record.id == row.recordId &&
                it.record.amountMinor > 0 && it.record.version > 0 && it.input == row.dto().input &&
                it.confirmedDraftRevision in 1 until Long.MAX_VALUE && row.draftRevision == it.confirmedDraftRevision + 1)
        }
    } catch (error: RuntimeException) { throw FinanceException(FinanceError.RecoveryRequired, error) }

    private fun record(id: String): FinanceRecord {
        val row = dao.transaction(id) ?: fail(FinanceError.NotFound)
        if (row.currency != "CNY" || row.amountMinor <= 0 || row.version < 1) fail(FinanceError.RecoveryRequired)
        return FinanceRecord(row.id, direction(row.direction), row.amountMinor, row.accountId, row.categoryId, row.memo, row.occurredAt, row.version, row.createdAt, row.updatedAt)
    }

    private fun AccountEntity.dto() = FinanceAccount(id, name, version)
    private fun CategoryEntity.dto() = FinanceCategory(id, name, direction(direction), version)
    private fun direction(value: String) = try { FinanceDirection.valueOf(value) }
    catch (error: IllegalArgumentException) { throw FinanceException(FinanceError.RecoveryRequired, error) }
    private fun fail(code: FinanceError): Nothing = throw FinanceException(code)
    private suspend fun mark(point: FinanceCheckpoint) { checkpoint(point); currentCoroutineContext().ensureActive() }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: SQLiteFullException) { throw FinanceException(FinanceError.StorageFull, error) }
        catch (error: SQLiteException) { throw FinanceException(FinanceError.RecoverableIO, error) }
    }
}
