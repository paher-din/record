package com.paherdin.record.core.data

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.paherdin.record.core.data.restore.ActivationOutcome
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.BootstrapResult
import com.paherdin.record.core.data.restore.GenerationActivation
import com.paherdin.record.core.data.restore.GenerationState.HEALTHY
import com.paherdin.record.core.data.restore.GenerationState.PENDING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure JVM tests for the ADR-0016 startup gate handoff (plan M1-T03 Step 1).
 * The engine runs its P5 sequence against injected steps, so progress order,
 * stable blocked categories, retry rules, concurrent coalescing, and Ready
 * caching are proven without a device; `RecordStartupDeviceTest` proves the
 * real bootstrap/migration/activation/Room paths on the reference device.
 */
class RecordStartupTest {

    private class RecordingObserver : StartupObserver {
        val events = java.util.Collections.synchronizedList(mutableListOf<StartupProgress>())
        override fun onProgress(progress: StartupProgress) {
            events.add(progress)
        }
    }

    private fun genId(): String = "gen-" + UUID.randomUUID()

    private fun healthyRecord(): ActivationRecord = ActivationRecord(
        migrationRecoveryState = MigrationRecoveryState.MIGRATION_HEALTHY,
        migration = null,
        generationActivation = GenerationActivation(genId(), null, null, null, HEALTHY),
    )

    private fun pendingActivationRecord(): ActivationRecord {
        val candidate = genId()
        return ActivationRecord(
            migrationRecoveryState = MigrationRecoveryState.MIGRATION_HEALTHY,
            migration = null,
            generationActivation = GenerationActivation(
                activeGenerationId = candidate,
                previousGenerationId = genId(),
                candidateGenerationId = candidate,
                retiringGenerationId = null,
                state = PENDING,
            ),
        )
    }

    private fun pendingMigrationRecord(): ActivationRecord = ActivationRecord(
        migrationRecoveryState = MigrationRecoveryState.MIGRATION_RECOVERY_PENDING,
        migration = MigrationPayload(
            snapshotId = "snap-1",
            snapshotSha256 = "a".repeat(64),
            liveDatabasePath = "databases/record.db",
            workDatabasePath = "databases/record.db",
            restoreTempPath = "recovery/restore-snap-1.db",
            quarantinePath = "recovery/quarantine-snap-1",
            stage = MigrationStage.PREPARED,
        ),
        generationActivation = GenerationActivation(genId(), null, null, null, HEALTHY),
    )

    /** Builds an engine whose P5 steps are the injected fakes. */
    private fun engine(
        classify: (StartupObserver) -> BootstrapResult = { BootstrapResult.Fresh },
        createFreshRecord: () -> ActivationRecord = { healthyRecord() },
        resolvePendingActivation: suspend (ActivationRecord) -> ActivationOutcome = { ActivationOutcome.Activated(it) },
        cleanupOrphans: (ActivationRecord) -> Unit = {},
        openActive: suspend (ActivationRecord, Boolean) -> RepositoryResult<RecordRepository> = { _, _ ->
            RepositoryResult.Success(RecordRepository())
        },
    ): StartupEngine = StartupEngine(
        classify = classify,
        createFreshRecord = createFreshRecord,
        resolvePendingActivation = resolvePendingActivation,
        cleanupOrphans = cleanupOrphans,
        openActive = openActive,
    )

    private fun assertBlocked(result: StartupResult, failure: StartupFailure, canRetry: Boolean) {
        val blocked = result as? StartupResult.Blocked
            ?: error("expected Blocked($failure, $canRetry), got $result")
        assertEquals(failure, blocked.failure)
        assertEquals(canRetry, blocked.canRetry)
    }

    // ---- progress ordering ----

    @Test
    fun freshAttemptEmitsCheckingFirstAndBecomesReady() = runBlocking {
        val observer = RecordingObserver()
        val result = engine().run(observer)
        assertTrue("fresh start must become Ready, got $result", result is StartupResult.Ready)
        assertEquals(listOf(StartupProgress.Checking), observer.events)
    }

    @Test
    fun recoveryDetectedThenRecoveringPrecedePendingResolution() = runBlocking {
        val order = mutableListOf<String>()
        val observer = RecordingObserver()
        val result = engine(
            classify = { BootstrapResult.Ready(pendingActivationRecord()) },
            resolvePendingActivation = {
                order += "resolve"
                ActivationOutcome.Activated(healthyRecord())
            },
            openActive = { _, _ ->
                order += "open"
                RepositoryResult.Success(RecordRepository())
            },
        ).run(observer)
        assertTrue(result is StartupResult.Ready)
        assertEquals(
            listOf(StartupProgress.Checking, StartupProgress.RecoveryDetected, StartupProgress.Recovering),
            observer.events,
        )
        assertEquals(listOf("resolve", "open"), order)
    }

    @Test
    fun classifyReceivesTheAttemptObserver() = runBlocking {
        // The migration recovery path emits its events from inside
        // classification (the bootstrap dispatches the resolver); the engine
        // must forward the attempt observer there.
        val observer = RecordingObserver()
        val result = engine(
            classify = { forwarded ->
                forwarded.onProgress(StartupProgress.RecoveryDetected)
                forwarded.onProgress(StartupProgress.Recovering)
                BootstrapResult.Ready(healthyRecord())
            },
        ).run(observer)
        assertTrue(result is StartupResult.Ready)
        assertEquals(
            listOf(StartupProgress.Checking, StartupProgress.RecoveryDetected, StartupProgress.Recovering),
            observer.events,
        )
    }

    // ---- stable blocked categories ----

    @Test
    fun blockedBootstrapIsRecoveryRequiredAndRetryable() = runBlocking {
        val observer = RecordingObserver()
        val result = engine(classify = { BootstrapResult.Blocked("legacy recovery blocked") }).run(observer)
        assertBlocked(result, StartupFailure.RecoveryRequired, canRetry = true)
        assertEquals(listOf(StartupProgress.Checking), observer.events)
    }

    @Test
    fun blockedPendingResolutionIsRecoveryRequiredAndRetryable() = runBlocking {
        val result = engine(
            classify = { BootstrapResult.Ready(pendingActivationRecord()) },
            resolvePendingActivation = { ActivationOutcome.Blocked("PENDING without previous generation") },
        ).run(RecordingObserver())
        assertBlocked(result, StartupFailure.RecoveryRequired, canRetry = true)
    }

    @Test
    fun nonHealthyMigrationStateBlocksWithoutRetry() = runBlocking {
        val result = engine(classify = { BootstrapResult.Ready(pendingMigrationRecord()) }).run(RecordingObserver())
        assertBlocked(result, StartupFailure.RecoveryRequired, canRetry = false)
    }

    @Test
    fun unresolvedGenerationAfterResolutionBlocksWithoutRetry() = runBlocking {
        // Even a non-Blocked resolution outcome that still leaves a PENDING
        // generation must block; no Room open starts.
        val result = engine(
            classify = { BootstrapResult.Ready(pendingActivationRecord()) },
            resolvePendingActivation = { ActivationOutcome.Activated(pendingActivationRecord()) },
        ).run(RecordingObserver())
        assertBlocked(result, StartupFailure.RecoveryRequired, canRetry = false)
    }

    @Test
    fun storageExhaustedMapsToNonRetryableStorageBlocked() = runBlocking {
        val result = engine(classify = { throw SQLiteFullException("simulated full") }).run(RecordingObserver())
        assertBlocked(result, StartupFailure.StorageExhausted, canRetry = false)
    }

    @Test
    fun rawFailuresMapToUnexpectedWithoutRetry() = runBlocking {
        val failures: List<() -> Throwable> = listOf(
            { SQLiteException("sqlite") },
            { MigrationGateException("gate") },
            { IllegalArgumentException("bad record") },
            { RuntimeException("raw") },
        )
        for (failure in failures) {
            val result = engine(classify = { throw failure() }).run(RecordingObserver())
            assertBlocked(result, StartupFailure.Unexpected, canRetry = false)
        }
    }

    @Test
    fun openStepFailuresMapStableCategories() = runBlocking {
        val storageExhausted = engine(openActive = { _, _ -> RepositoryResult.Failure.StorageExhausted }).run(RecordingObserver())
        assertBlocked(storageExhausted, StartupFailure.StorageExhausted, canRetry = false)
        val transactionFailed = engine(openActive = { _, _ -> RepositoryResult.Failure.TransactionFailed }).run(RecordingObserver())
        assertBlocked(transactionFailed, StartupFailure.Unexpected, canRetry = false)
    }

    // ---- retry rules ----

    @Test
    fun retryAfterRetryableBlockedStartsNewAttemptOnTheSameEngine() = runBlocking {
        val classifyCalls = AtomicInteger()
        val startup = RecordStartup(
            engine(
                classify = {
                    classifyCalls.incrementAndGet()
                    if (classifyCalls.get() == 1) BootstrapResult.Blocked("first attempt blocked") else BootstrapResult.Fresh
                },
            ),
        )
        val first = startup.start(RecordingObserver())
        assertBlocked(first, StartupFailure.RecoveryRequired, canRetry = true)
        val retried = startup.retry(RecordingObserver())
        assertTrue("retry must reach Ready through the same engine, got $retried", retried is StartupResult.Ready)
        assertEquals(2, classifyCalls.get())
    }

    @Test
    fun retryAfterReadyReturnsCachedReadyWithoutAnotherAttempt() = runBlocking {
        val classifyCalls = AtomicInteger()
        val startup = RecordStartup(engine(classify = { classifyCalls.incrementAndGet(); BootstrapResult.Fresh }))
        val ready = startup.start(RecordingObserver())
        assertTrue(ready is StartupResult.Ready)
        assertSame(ready, startup.retry(RecordingObserver()))
        assertEquals(1, classifyCalls.get())
    }

    @Test
    fun retryAfterNonRetryableBlockedReturnsSameResultWithoutTouchingStorage() = runBlocking {
        val classifyCalls = AtomicInteger()
        val startup = RecordStartup(
            engine(classify = { classifyCalls.incrementAndGet(); BootstrapResult.Ready(pendingMigrationRecord()) }),
        )
        val blocked = startup.start(RecordingObserver())
        assertSame(blocked, startup.retry(RecordingObserver()))
        assertEquals(1, classifyCalls.get())
    }

    // ---- concurrency and caching ----

    @Test
    fun concurrentStartsCoalesceOntoSingleAttempt() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val classifyCalls = AtomicInteger()
        val startup = RecordStartup(
            engine(
                classify = {
                    classifyCalls.incrementAndGet()
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS)) { "fake classify release timed out" }
                    BootstrapResult.Fresh
                },
            ),
        )
        val (first, second) = coroutineScope {
            val a = async(Dispatchers.Default) { startup.start(RecordingObserver()) }
            val b = async(Dispatchers.Default) { startup.start(RecordingObserver()) }
            check(entered.await(15, TimeUnit.SECONDS)) { "first attempt never entered classify" }
            release.countDown()
            a.await() to b.await()
        }
        assertTrue(first is StartupResult.Ready)
        assertSame("concurrent callers must receive the same in-flight result", first, second)
        assertEquals(1, classifyCalls.get())
    }

    @Test
    fun readyIsCachedWithoutReopening() = runBlocking {
        val openCalls = AtomicInteger()
        val repository = RecordRepository()
        val startup = RecordStartup(
            engine(openActive = { _, _ -> openCalls.incrementAndGet(); RepositoryResult.Success(repository) }),
        )
        val first = startup.start(RecordingObserver())
        val second = startup.start(RecordingObserver())
        assertTrue(first is StartupResult.Ready)
        assertSame(first, second)
        assertSame(repository, (first as StartupResult.Ready).repository)
        assertEquals(1, openCalls.get())
    }
}
