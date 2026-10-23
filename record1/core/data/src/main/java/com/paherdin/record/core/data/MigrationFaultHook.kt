package com.paherdin.record.core.data

/**
 * Simulated-fault injection boundaries of the migration coordinator
 * (plan Task 3/4). Production default is the no-op; tests inject throwing
 * implementations at named boundaries. Every boundary runs the three-scenario
 * protocol: fail once then restart, fail repeatedly without bypassing safety,
 * complete then repeat.
 */
internal interface MigrationFaultHook {
    fun beforeSnapshotCopy() {}
    fun afterSnapshotCopy() {}
    fun beforePublish() {}
    fun afterPublish() {}
    fun beforeMigration() {}
    fun duringMigration() {}
    fun afterPendingPersistBeforeMove() {}
    fun beforeSidecarIsolationEntry(name: String) {}
    fun afterSidecarIsolationEntry(name: String) {}
    fun beforeSnapshotReplace() {}
    fun afterSnapshotReplace() {}
    fun beforeRestoreCopyValidation() {}
    fun afterMigrationRecovered() {}

    companion object {
        val NO_OP: MigrationFaultHook = object : MigrationFaultHook {}
    }
}
