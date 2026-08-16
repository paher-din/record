package com.paherdin.record.core.data.restore

import android.content.Context
import com.paherdin.record.core.data.restore.GenerationState.HEALTHY
import com.paherdin.record.core.data.restore.GenerationState.PENDING
import java.io.File

internal sealed interface ActivationOutcome {
    data class Activated(val record: ActivationRecord) : ActivationOutcome
    data class RolledBack(val record: ActivationRecord) : ActivationOutcome
    data class Blocked(val reason: String) : ActivationOutcome
}

/**
 * The activation protocol steps 2–5 (spec 4.5, ADR 0003, plan Task 8): activate
 * pending (one atomic record write), startup revalidation of the candidate
 * (the same validation a cold startup runs), healthy confirmation, and
 * rollback. Every transition is an atomic `AtomicFile` record write; a crash
 * exposes either the whole `PENDING` record or the whole resulting `HEALTHY`
 * record, never mixed fields. The full N-1/N-2 migration matrix stays M7's.
 */
internal class RestoreActivationCoordinator(
    private val context: Context,
    private val activationStore: ActivationRecordStore,
    private val generationManager: GenerationManager,
    private val hook: RestoreFaultHook = RestoreFaultHook.NO_OP,
) {
    /**
     * From a `HEALTHY` record with active `O`, activates candidate `C`:
     * writes `PENDING`, revalidates `C`, then confirms healthy or rolls back to
     * `O`. The old generation `O` becomes retiring on confirmation; the failed
     * candidate `C` becomes retiring on rollback.
     */
    suspend fun confirm(
        old: ActivationRecord,
        candidateGenerationId: String,
    ): ActivationOutcome {
        val oldActive = old.generationActivation.activeGenerationId
        val pendingRecord = old.copy(
            generationActivation = GenerationActivation(
                activeGenerationId = candidateGenerationId,
                previousGenerationId = oldActive,
                candidateGenerationId = candidateGenerationId,
                retiringGenerationId = null,
                state = PENDING,
            ),
        )
        hook.beforePendingWrite()
        activationStore.write(pendingRecord)
        hook.afterPendingWrite()

        hook.beforeRevalidate()
        val revalidated = revalidate(candidateGenerationId)
        hook.afterRevalidate()

        if (!revalidated) {
            val rolledBack = pendingRecord.copy(
                generationActivation = GenerationActivation(
                    activeGenerationId = oldActive,
                    previousGenerationId = null,
                    candidateGenerationId = null,
                    retiringGenerationId = candidateGenerationId,
                    state = HEALTHY,
                ),
            )
            hook.beforeRollbackWrite()
            activationStore.write(rolledBack)
            hook.afterRollbackWrite()
            return ActivationOutcome.RolledBack(rolledBack)
        }

        val healthy = pendingRecord.copy(
            generationActivation = GenerationActivation(
                activeGenerationId = candidateGenerationId,
                previousGenerationId = null,
                candidateGenerationId = null,
                retiringGenerationId = oldActive,
                state = HEALTHY,
            ),
        )
        hook.beforeHealthyWrite()
        activationStore.write(healthy)
        hook.afterHealthyWrite()
        return ActivationOutcome.Activated(healthy)
    }

    /** The same candidate validation a cold startup runs (spec 4.5 step 3). */
    suspend fun revalidate(candidateGenerationId: String): Boolean {
        val db = openGenerationDatabase(context, generationManager.databaseFile(candidateGenerationId))
        return try {
            val contentDir = File(generationManager.attachmentsDir(candidateGenerationId), "content")
            StagedGenerationValidator(db, contentDir).validate() is GenerationValidation.Valid
        } catch (e: Exception) {
            false
        } finally {
            db.close()
        }
    }

    /**
     * Resumes an already-durable `PENDING` record (spec 4.5 step 3): revalidates
     * the candidate and either confirms healthy or rolls back. Used by a cold
     * startup and by the same-repository confirmation after a crash past
     * `PENDING`.
     */
    suspend fun resolvePending(pending: ActivationRecord): ActivationOutcome {
        val candidate = pending.generationActivation.activeGenerationId
        val old = pending.generationActivation.previousGenerationId
            ?: return ActivationOutcome.Blocked("PENDING without previous generation")
        hook.beforeRevalidate()
        val revalidated = revalidate(candidate)
        hook.afterRevalidate()

        if (!revalidated) {
            val rolledBack = pending.copy(
                generationActivation = GenerationActivation(
                    activeGenerationId = old,
                    previousGenerationId = null,
                    candidateGenerationId = null,
                    retiringGenerationId = candidate,
                    state = HEALTHY,
                ),
            )
            hook.beforeRollbackWrite()
            activationStore.write(rolledBack)
            hook.afterRollbackWrite()
            return ActivationOutcome.RolledBack(rolledBack)
        }

        val healthy = pending.copy(
            generationActivation = GenerationActivation(
                activeGenerationId = candidate,
                previousGenerationId = null,
                candidateGenerationId = null,
                retiringGenerationId = old,
                state = HEALTHY,
            ),
        )
        hook.beforeHealthyWrite()
        activationStore.write(healthy)
        hook.afterHealthyWrite()
        return ActivationOutcome.Activated(healthy)
    }

    /** Step-6 retirement: re-read, verify the pointer differs, delete or confirm absent, clear. */
    fun retire(retiringGenerationId: String): ActivationRecord {
        val record = checkNotNull(activationStore.read()) { "record missing during retirement" }
        val pointers = setOfNotNull(
            record.generationActivation.activeGenerationId,
            record.generationActivation.previousGenerationId,
            record.generationActivation.candidateGenerationId,
        )
        check(retiringGenerationId !in pointers) { "refusing to retire a pointer-referenced generation" }
        hook.beforeRetireDelete()
        generationManager.deleteGenerationDirectory(retiringGenerationId)
        hook.afterRetireDelete()
        val cleared = record.copy(
            generationActivation = record.generationActivation.copy(retiringGenerationId = null),
        )
        hook.beforeRetirePointerClear()
        activationStore.write(cleared)
        hook.afterRetirePointerClear()
        return cleared
    }
}
