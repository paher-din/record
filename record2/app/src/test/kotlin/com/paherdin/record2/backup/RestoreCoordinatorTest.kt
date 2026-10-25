package com.paherdin.record2.backup

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RestoreCoordinatorTest {
    private val source = object : BackupSource {
        override val displayName: String? = null
        override suspend fun copyTo(destination: File, maxBytes: Long): Long = 0L
    }

    private fun manifest(): BackupManifest = BackupManifest(
        format = BackupLimits.FORMAT,
        formatVersion = BackupLimits.FORMAT_VERSION,
        databaseSchemaVersion = BackupLimits.DATABASE_SCHEMA_VERSION,
        exportedAt = 0,
        sourceGeneration = UUID.randomUUID().toString(),
        businessRevision = 0,
        appVersionCode = 1,
        appVersionName = "test",
        entryCount = BackupLimits.ENTRY_COUNT,
        totalRows = 0,
        totalUncompressedBytes = 0,
        segments = emptyList(),
        contentDigest = "b".repeat(64),
    )

    private fun candidate(id: String = UUID.randomUUID().toString()): RestoreCandidate =
        RestoreCandidate(
            id = id,
            source = source,
            sourceDisplayName = null,
            sourceBytes = 1,
            sourceSha256 = "a".repeat(64),
            manifest = manifest(),
            targetGeneration = null,
            targetAuthEpoch = null,
            preflightAt = 0,
            appSchemaVersion = BackupLimits.DATABASE_SCHEMA_VERSION,
            appVersionCode = 1,
        )

    private fun receipt(candidate: RestoreCandidate): RestoreReceipt = RestoreReceipt(
        formatVersion = BackupLimits.FORMAT_VERSION,
        sourceZipSha256 = candidate.sourceSha256,
        sourceZipBytes = candidate.sourceBytes,
        sourceFormatVersion = candidate.manifest.formatVersion,
        sourceSchemaVersion = candidate.manifest.databaseSchemaVersion,
        sourceGeneration = candidate.manifest.sourceGeneration,
        restoredGeneration = UUID.randomUUID().toString(),
        restoredAt = 0,
        contentDigest = candidate.manifest.contentDigest,
        importedRows = 0,
        businessRevisionAtRestore = 0,
    )

    private fun result(
        receipt: RestoreReceipt,
        stats: RestoreTransformStats = RestoreTransformStats.Known(0, 0),
    ): RestoreResult = RestoreResult(
        restoredGeneration = receipt.restoredGeneration,
        processOwnerId = "owner",
        authEpoch = 1,
        receipt = receipt,
        importedRows = receipt.importedRows,
        transformStats = stats,
    )

    private class FakeEngine : RestoreEngine {
        var lookup: RestoreReceiptLookup = RestoreReceiptLookup.None
        var restoreCalls: Int = 0
        var completedCalls: Int = 0
        lateinit var restoreBlock: suspend (RestoreCandidate, (RestoreProgress) -> Unit) -> RestoreResult

        override suspend fun preflight(source: BackupSource): RestoreCandidate = error("unused")

        override suspend fun receiptLookup(candidate: RestoreCandidate): RestoreReceiptLookup = lookup

        override suspend fun restore(
            candidate: RestoreCandidate,
            progress: (RestoreProgress) -> Unit,
        ): RestoreResult {
            restoreCalls += 1
            return restoreBlock(candidate, progress)
        }

        override suspend fun completedResult(receipt: RestoreReceipt): RestoreResult {
            completedCalls += 1
            return RestoreResult(
                restoredGeneration = receipt.restoredGeneration,
                processOwnerId = "owner",
                authEpoch = 1,
                receipt = receipt,
                importedRows = receipt.importedRows,
                transformStats = RestoreTransformStats.Unknown,
            )
        }
    }

    private suspend fun eventually(block: () -> Boolean) {
        withTimeout(10_000) {
            while (!block()) delay(5)
        }
    }

    @Test
    fun confirmedRestoreSurvivesObserverCancellationAndRejectsDuplicatesOrOtherCandidates() = runBlocking {
        val engine = FakeEngine()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        engine.restoreBlock = { candidate, progress ->
            progress(RestoreProgress.WAITING_FOR_GATE)
            progress(RestoreProgress.PREPARING)
            progress(RestoreProgress.IMPORTING)
            entered.complete(Unit)
            gate.await()
            progress(RestoreProgress.COMMITTED)
            progress(RestoreProgress.POST_COMMIT)
            result(receipt(candidate))
        }
        val coordinator = RestoreCoordinator(engine)
        try {
            val candidate = candidate()
            val started = coordinator.confirm(candidate)
            assertTrue(started is RestoreStart.Started)
            val handle = (started as RestoreStart.Started).handle
            withTimeout(10_000) { entered.await() }
            eventually { coordinator.currentStatus().phase == RestorePhase.IMPORTING }

            val duplicate = coordinator.confirm(candidate)
            assertTrue(duplicate is RestoreStart.Existing)
            assertEquals(handle.operationId, (duplicate as RestoreStart.Existing).handle.operationId)

            val other = coordinator.confirm(candidate())
            assertTrue(other is RestoreStart.Busy)
            assertEquals(handle.operationId, (other as RestoreStart.Busy).activeOperationId)

            // A page/observer cancellation only cancels this waiter, not the owned restore.
            val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val observer = observerScope.async { runCatching { handle.await() } }
            observer.cancelAndJoin()
            assertTrue(engine.restoreCalls == 1)

            gate.complete(Unit)
            val completed = withTimeout(10_000) { handle.await() }
            assertEquals("owner", completed.processOwnerId)
            assertTrue(coordinator.currentStatus().phase == RestorePhase.FINISHED)
            assertTrue(!coordinator.currentStatus().recoveredFromReceipt)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun committedReceiptIsReusedWithoutASecondImport() = runBlocking {
        val engine = FakeEngine()
        val coordinator = RestoreCoordinator(engine)
        try {
            val candidate = candidate()
            val committed = receipt(candidate)
            engine.lookup = RestoreReceiptLookup.Committed(committed)
            val started = coordinator.confirm(candidate) as RestoreStart.Started
            val result = withTimeout(10_000) { started.handle.await() }
            assertEquals(0, engine.restoreCalls)
            assertEquals(1, engine.completedCalls)
            assertTrue(result.transformStats is RestoreTransformStats.Unknown)
            assertTrue(coordinator.currentStatus().recoveredFromReceipt)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun failureWithoutReceiptIsFailedNoCommitAndAllowsANewCandidate() = runBlocking {
        val engine = FakeEngine()
        engine.restoreBlock = { _, _ -> throw BackupException(BackupError.RecoverableIO, "boom") }
        val coordinator = RestoreCoordinator(engine)
        try {
            val candidate = candidate()
            val started = coordinator.confirm(candidate) as RestoreStart.Started
            val error = runCatching { withTimeout(10_000) { started.handle.await() } }.exceptionOrNull()
            assertTrue(error is BackupException)
            assertEquals(RestorePhase.FAILED_NO_COMMIT, coordinator.currentStatus().phase)

            val duplicate = coordinator.confirm(candidate)
            assertTrue(duplicate is RestoreStart.Existing)
            assertEquals(started.handle.operationId, (duplicate as RestoreStart.Existing).handle.operationId)

            val next = coordinator.confirm(candidate())
            assertTrue(next is RestoreStart.Started)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun unknownReceiptNeverStartsImport() = runBlocking {
        val engine = FakeEngine()
        engine.lookup = RestoreReceiptLookup.Unknown("receipt mismatch")
        val coordinator = RestoreCoordinator(engine)
        try {
            val started = coordinator.confirm(candidate()) as RestoreStart.Started
            val error = runCatching { withTimeout(10_000) { started.handle.await() } }.exceptionOrNull()
            assertTrue(error is BackupException)
            assertEquals(0, engine.restoreCalls)
            assertEquals(RestorePhase.OUTCOME_UNKNOWN, coordinator.currentStatus().phase)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun cancellationAfterCommitReclassifiesFromTheReceipt() = runBlocking {
        val engine = FakeEngine()
        val coordinator = RestoreCoordinator(engine)
        try {
            val candidate = candidate()
            val committed = receipt(candidate)
            engine.lookup = RestoreReceiptLookup.None
            engine.restoreBlock = { _, progress ->
                progress(RestoreProgress.COMMITTED)
                // The durable receipt appears at commit; the observer cancellation follows.
                engine.lookup = RestoreReceiptLookup.Committed(committed)
                throw CancellationException("observation lost")
            }
            val started = coordinator.confirm(candidate) as RestoreStart.Started
            val result = withTimeout(10_000) { started.handle.await() }
            assertEquals(1, engine.restoreCalls)
            assertEquals(committed.restoredGeneration, result.restoredGeneration)
            assertTrue(result.transformStats is RestoreTransformStats.Unknown)
            assertTrue(coordinator.currentStatus().recoveredFromReceipt)
        } finally {
            coordinator.close()
        }
    }
}
