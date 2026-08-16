package com.paherdin.record.core.data.restore

import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Generation directory layout, initial-generation creation, pointer-aware
 * orphan scanning, and step-6 retirement (spec 4.5, plan Task 3).
 *
 * `files/vaults/<generationId>/record.db` plus a per-generation
 * `attachments/{tmp,content,quarantine}` tree. Orphan cleanup never deletes a
 * directory named by any activation-record pointer. The legacy root layout
 * (`record.db`, `files/attachments/`) is never an orphan candidate here.
 *
 * ponytail: single process-wide registry, so one generation lock suffices;
 * per-generation locks only if a future multi-process component lands.
 */
internal class GenerationManager(
    private val context: android.content.Context,
    private val lock: ReentrantLock = ReentrantLock(),
) {
    fun vaultsDir(): File = File(context.filesDir, "vaults")

    fun generationDir(id: String): File = File(vaultsDir(), id)

    fun databaseFile(id: String): File = File(generationDir(id), "record.db")

    fun attachmentsDir(id: String): File = File(generationDir(id), "attachments")

    fun isIncomplete(id: String): Boolean = File(generationDir(id), INCOMPLETE_MARKER).isFile

    fun newGenerationId(): String = "gen-" + UUID.randomUUID().toString()

    /** Creates the generation directory; the caller atomically publishes the pointer. */
    fun createGeneration(id: String): File {
        val dir = generationDir(id)
        check(!dir.exists()) { "generation $id already exists" }
        check(dir.mkdirs()) { "failed to create generation dir $dir" }
        File(dir, INCOMPLETE_MARKER).writeText("incomplete\n", Charsets.UTF_8)
        return dir
    }

    /** Clears the marker only after the generation is safe to become active. */
    fun markComplete(id: String) {
        val marker = File(generationDir(id), INCOMPLETE_MARKER)
        check(marker.isFile) { "generation $id has no incomplete marker" }
        check(marker.delete()) { "failed to clear incomplete marker for $id" }
    }

    /** Completes before Room opens: create the initial generation directory. */
    fun createInitialGeneration(): GenerationActivation {
        val id = newGenerationId()
        createGeneration(id)
        return GenerationActivation(id, null, null, null, GenerationState.HEALTHY)
    }

    /**
     * Step-6 retirement under the lock: re-read the record, require the
     * retiring ID to differ from every pointer, delete the directory or
     * confirm it absent, then atomically clear the pointer.
     */
    fun <R> underLock(block: () -> R): R = lock.withLock(block)

    /** Directories not named by any activation pointer; legacy root is excluded. */
    fun orphanCandidates(record: ActivationRecord): List<File> {
        val protected = setOfNotNull(
            record.generationActivation.activeGenerationId,
            record.generationActivation.previousGenerationId,
            record.generationActivation.candidateGenerationId,
            record.generationActivation.retiringGenerationId,
        )
        val vaults = vaultsDir()
        if (!vaults.isDirectory) return emptyList()
        return vaults.listFiles { f -> f.isDirectory && f.name !in protected }?.toList() ?: emptyList()
    }

    /** Idempotent; removes a complete or incomplete unreferenced generation directory. */
    fun deleteGenerationDirectory(id: String) {
        val dir = generationDir(id)
        if (!dir.exists()) return
        check(dir.deleteRecursively()) { "failed to delete generation $id" }
    }

    companion object {
        private const val INCOMPLETE_MARKER = ".incomplete"
    }
}
