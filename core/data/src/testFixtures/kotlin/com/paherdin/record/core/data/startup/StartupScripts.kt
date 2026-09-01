package com.paherdin.record.core.data

import com.paherdin.record.core.data.restore.ActivationOutcome
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.BootstrapResult
import com.paherdin.record.core.data.restore.GenerationActivation
import com.paherdin.record.core.data.restore.GenerationState
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * A one-shot, stdlib-only completion gate for scripted startup attempts. A test
 * holds one engine attempt in flight by awaiting [await] from a scripted step
 * and completing it with [complete] from the test thread. Pure JVM: no
 * Context, Room, or I/O.
 */
class StartupScriptGate {

    private val released = CountDownLatch(1)

    /** Completes the gate; idempotent. */
    fun complete() {
        released.countDown()
    }

    /** Suspends the scripted engine step until the test completes the gate. */
    suspend fun await(): Unit = suspendCoroutine { continuation ->
        Thread {
            released.await()
            continuation.resume(Unit)
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Blocking wait for scripted non-suspend steps; the test completes the gate. */
    fun awaitBlocking() {
        released.await()
    }
}

/**
 * Test-only scriptable [RecordStartup] factory (plan M1-T07 unit-test seam
 * correction). It drives the REAL [StartupEngine] `run()` by replacing only the
 * five P5 step lambdas (classify/createFreshRecord/resolvePendingActivation/
 * cleanupOrphans/openActive) with scripted versions — run()'s ordering and
 * stable-failure mapping are never reimplemented. The public API exposes only
 * public types ([RecordStartup], [StartupScriptGate]); production code must
 * never reference this fixture.
 */
object StartupScripts {

    private fun genId(): String = "gen-" + UUID.randomUUID()

    private fun healthyRecord(): ActivationRecord = ActivationRecord(
        migrationRecoveryState = MigrationRecoveryState.MIGRATION_HEALTHY,
        migration = null,
        generationActivation = GenerationActivation(genId(), null, null, null, GenerationState.HEALTHY),
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
                state = GenerationState.PENDING,
            ),
        )
    }

    /** Builds a [RecordStartup] over a real engine with scripted P5 steps. */
    private fun scriptEngine(
        classify: (StartupObserver) -> BootstrapResult = { BootstrapResult.Fresh },
        resolvePendingActivation: suspend (ActivationRecord) -> ActivationOutcome = { ActivationOutcome.Activated(it) },
        openActive: suspend (ActivationRecord, Boolean) -> RepositoryResult<RecordRepository> = { _, _ ->
            RepositoryResult.Success(RecordRepository())
        },
    ): RecordStartup = RecordStartup(
        StartupEngine(
            classify = classify,
            createFreshRecord = { healthyRecord() },
            resolvePendingActivation = resolvePendingActivation,
            cleanupOrphans = {},
            openActive = openActive,
        ),
    )

    /**
     * Healthy fresh vault: emits Checking, then returns [StartupResult.Ready]
     * with a repository instance.
     */
    fun ready(): RecordStartup = scriptEngine()

    /**
     * Durable pending activation: emits Checking, RecoveryDetected, Recovering
     * (the engine's PENDING path), the resolver reports Activated, and the
     * attempt completes Ready.
     */
    fun readyThroughRecovery(): RecordStartup = scriptEngine(
        classify = { BootstrapResult.Ready(pendingActivationRecord()) },
        resolvePendingActivation = { ActivationOutcome.Activated(healthyRecord()) },
    )

    /** Classification blocks with a retryable recovery requirement. */
    fun retryableBlocked(): RecordStartup = scriptEngine(
        classify = { BootstrapResult.Blocked("scripted blocked classification") },
    )

    /** Active-generation open fails with storage exhausted; not retryable. */
    fun blockingStorageExhausted(): RecordStartup = scriptEngine(
        openActive = { _, _ -> RepositoryResult.Failure.StorageExhausted },
    )

    /**
     * First attempt returns a retryable blocked result, a later attempt
     * reaches Ready: proves the store's `retry()` re-entry per
     * [RecordStartup] semantics (one new attempt, same engine).
     */
    fun retryableBlockedThenReady(): RecordStartup {
        val classifyCalls = AtomicInteger()
        return scriptEngine(
            classify = {
                if (classifyCalls.incrementAndGet() == 1) {
                    BootstrapResult.Blocked("first scripted attempt blocked")
                } else {
                    BootstrapResult.Fresh
                }
            },
        )
    }

    /**
     * The openActive step awaits [gate] and then returns Ready, so a test can
     * hold one attempt in flight past `Checking`. A second engine attempt —
     * which coalescing must prevent — fails the open step, turning the
     * terminal outcome into a blocking failure the test can observe.
     */
    fun gatedReady(gate: StartupScriptGate): RecordStartup {
        val attempts = AtomicInteger()
        return scriptEngine(
            openActive = { _, _ ->
                if (attempts.incrementAndGet() > 1) RepositoryResult.Failure.TransactionFailed
                gate.await()
                RepositoryResult.Success(RecordRepository())
            },
        )
    }

    /**
     * RecoveryDetected is emitted and the attempt then waits on [gate] before
     * completing, so a test can observe the held `Phase.Running` gate state.
     */
    fun gatedRunning(gate: StartupScriptGate): RecordStartup = scriptEngine(
        classify = { observer ->
            observer.onProgress(StartupProgress.RecoveryDetected)
            gate.awaitBlocking()
            BootstrapResult.Ready(healthyRecord())
        },
    )

    /**
     * Durable pending activation: the attempt passes RecoveryDetected and
     * Recovering, then the resolver awaits [gate], holding the attempt in the
     * `Phase.Recovering` gate until the test completes it.
     */
    fun gatedRecovering(gate: StartupScriptGate): RecordStartup = scriptEngine(
        classify = { BootstrapResult.Ready(pendingActivationRecord()) },
        resolvePendingActivation = {
            gate.await()
            ActivationOutcome.Activated(healthyRecord())
        },
    )
}