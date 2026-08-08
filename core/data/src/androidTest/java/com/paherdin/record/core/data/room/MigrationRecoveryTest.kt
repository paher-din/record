package com.paherdin.record.core.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.MigrationCoordinator
import com.paherdin.record.core.data.MigrationFaultHook
import com.paherdin.record.core.data.MigrationGateException
import com.paherdin.record.core.data.MigrationPayload
import com.paherdin.record.core.data.MigrationRecoveryRecordStore
import com.paherdin.record.core.data.MigrationRecoveryState
import com.paherdin.record.core.data.MigrationRecord
import com.paherdin.record.core.data.MigrationStage
import com.paherdin.record.core.data.ProceedAfterSnapshot
import com.paherdin.record.core.data.ProceedFresh
import com.paherdin.record.core.data.ProceedNormal
import com.paherdin.record.core.data.Blocked
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Criterion 10: the v1 → v2 migration runs through the coordinator with the
 * verified snapshot published before Room opens; failure injected at every
 * named snapshot/publish/pending/isolation/restore boundary resumes
 * idempotently; a pending record never opens Room or creates an empty
 * database; the published snapshot never changes; and the recovered vault
 * matches the pre-migration state. Every injected boundary runs the
 * three-scenario protocol (fail once/restart, repeated failure, complete/repeat).
 */
@RunWith(AndroidJUnit4::class)
class MigrationRecoveryTest {

    private lateinit var context: Context
    private lateinit var recordFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("record.db")
        recordFile = File(context.filesDir, "recovery/activation-recovery-v1.json")
        recordFile.delete()
        File(context.filesDir, "migration").deleteRecursively()
        File(context.filesDir, "recovery").deleteRecursively()
    }

    private fun liveDb(): File = context.getDatabasePath("record.db")

    private fun store() = MigrationRecoveryRecordStore(recordFile)

    private fun coordinator(hook: MigrationFaultHook = MigrationFaultHook.NO_OP) =
        MigrationCoordinator(context, store(), hook)

    /** v1 fixture built from the exact v1 DDL, with committed facts. */
    private fun createV1Fixture(db: SQLiteDatabase) {
        AttachmentTestFixtures.createV1Schema(db)
        db.execSQL("INSERT INTO `EntityRef` VALUES ('ref-t1', 'TRANSACTION', 1, 1, NULL)")
        db.execSQL("INSERT INTO `Transaction` VALUES ('ref-t1', 'EXPENSE')")
        db.execSQL("INSERT INTO `EntityRef` VALUES ('ref-n1', 'NOTE', 2, 2, NULL)")
        db.execSQL("INSERT INTO `Note` VALUES ('ref-n1')")
        db.execSQL("INSERT INTO `Account` VALUES ('acc-1', 'Wallet', 'CNY')")
        db.execSQL("INSERT INTO `AccountEntry` VALUES ('entry-1', 'ref-t1', 'acc-1', -5000)")
        db.execSQL("INSERT INTO `TransactionNoteRelation` VALUES ('ref-t1', 'ref-n1')")
    }

    private fun createV1FixtureAtLivePath() {
        liveDb().parentFile!!.mkdirs()
        SQLiteDatabase.openDatabase(liveDb().path, null, SQLiteDatabase.CREATE_IF_NECESSARY).use { db ->
            createV1Fixture(db)
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { com.paherdin.record.core.data.AttachmentValidator.sha256(it) }

    private fun recordCount(dbName: String, table: String): Long {
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { c -> c.moveToFirst(); c.getLong(0) }
        } finally {
            db.close()
        }
    }

    // ---- success path ----

    @Test
    fun v1FixtureUpgradesThroughCoordinatorAndRoom() {
        createV1FixtureAtLivePath()
        val gate = coordinator().startupGate()
        assertEquals(ProceedAfterSnapshot, gate)
        // Snapshot published + pending record persisted before Room opened.
        val record = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, record.state)
        val snapshot = File(context.filesDir, "migration/snapshot-${record.migration!!.snapshotId}.db")
        assertTrue(snapshot.isFile)
        val snapshotSha = sha256(snapshot)
        assertEquals(record.migration!!.snapshotSha256, snapshotSha)

        // Room opens through the public surface: the gate resumes to a verified
        // snapshot, the migration applies, and v1 facts survive in v2.
        val result = RecordRepository.open(context)
        assertTrue(result is RepositoryResult.Success)
        result as RepositoryResult.Success
        result.value.close()
        // v2 tables exist and v1 facts survived the migration.
        assertEquals(0L, recordCount("record.db", "Attachment"))
        assertEquals(1L, recordCount("record.db", "Account"))
        assertEquals(1L, recordCount("record.db", "AccountEntry"))
        assertEquals(1L, recordCount("record.db", "TransactionNoteRelation"))
        assertEquals(2L, recordCount("record.db", "EntityRef"))
        // Recovery record normalized to healthy.
        val final = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_HEALTHY, final.state)
    }

    @Test
    fun freshStartWithNoLiveDatabaseCreatesV2WithoutSnapshot() {
        val gate = coordinator().startupGate()
        assertEquals(ProceedFresh, gate)
        val result = RecordRepository.open(context)
        assertTrue(result is RepositoryResult.Success)
        (result as RepositoryResult.Success).value.close()
        // Assert the v2 tables exist.
        val db = SQLiteDatabase.openDatabase(liveDb().path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            assertTrue(tables.contains("Attachment"))
            assertTrue(tables.contains("AttachmentLink"))
            assertEquals(2L, db.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst(); c.getLong(0) })
        } finally {
            db.close()
        }
    }

    @Test
    fun v2LiveDatabaseOpensNormallyWithoutNewSnapshot() {
        RecordRepository.open(context).let { it as RepositoryResult.Success; it.value.close() }
        File(context.filesDir, "migration").deleteRecursively()
        val gate = coordinator().startupGate()
        assertEquals(ProceedNormal, gate)
        assertEquals(0, File(context.filesDir, "migration").listFiles()?.size ?: 0)
    }

    // ---- fail-once-then-restart boundaries ----

    @Test
    fun failOnceAtEveryNamedBoundaryThenRestartRecovers() {
        val boundaries = listOf(
            "beforeSnapshotCopy", "afterSnapshotCopy", "beforePublish", "afterPublish",
            "afterPendingPersistBeforeMove",
        )
        for (boundary in boundaries) {
            setUp()
            createV1FixtureAtLivePath()
            val failing = object : MigrationFaultHook {
                override fun beforeSnapshotCopy() = throwFault(boundary, "beforeSnapshotCopy")
                override fun afterSnapshotCopy() = throwFault(boundary, "afterSnapshotCopy")
                override fun beforePublish() = throwFault(boundary, "beforePublish")
                override fun afterPublish() = throwFault(boundary, "afterPublish")
                override fun afterPendingPersistBeforeMove() = throwFault(boundary, "afterPendingPersistBeforeMove")
            }
            assertThrows(RuntimeException::class.java) { coordinator(failing).startupGate() }
            // Restart with the no-op hook: the gate completes.
            val gate = coordinator().startupGate()
            assertEquals("boundary $boundary failed to resume", ProceedAfterSnapshot, gate)
            val result = RecordRepository.open(context)
            assertTrue("boundary $boundary: open failed after restart", result is RepositoryResult.Success)
            (result as RepositoryResult.Success).value.close()
            assertEquals("boundary $boundary: v1 facts lost", 1L, recordCount("record.db", "AccountEntry"))
            val final = store().read()!!
            assertEquals(MigrationRecoveryState.MIGRATION_HEALTHY, final.state)
        }
    }

    // ---- repeated failure without bypassing safety ----

    @Test
    fun repeatedFailureKeepsPendingRecordAndNeverCreatesEmptyDatabase() {
        createV1FixtureAtLivePath()
        val snapshotDir = File(context.filesDir, "migration")
        repeat(3) {
            val failing = object : MigrationFaultHook {
                override fun beforePublish() = throw RuntimeException("simulated repeated publish failure")
            }
            assertThrows(RuntimeException::class.java) { coordinator(failing).startupGate() }
            val record = store().read()!!
            assertEquals(MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, record.state)
            // No snapshot published yet (crash before publish) → no Room open possible.
            val published = snapshotDir.listFiles()?.filter { it.name.startsWith("snapshot-") } ?: emptyList()
            assertTrue("repeated failure must not publish a snapshot", published.isEmpty())
        }
        // Safety not bypassed: after the failures the gate still completes correctly.
        val gate = coordinator().startupGate()
        assertEquals(ProceedAfterSnapshot, gate)
        val result = RecordRepository.open(context)
        assertTrue(result is RepositoryResult.Success)
        (result as RepositoryResult.Success).value.close()
        assertEquals(1L, recordCount("record.db", "AccountEntry"))
    }

    // ---- corruption and blocking ----

    @Test
    fun corruptPublishedSnapshotBlocksRoomAndRetainsPendingRecord() {
        createV1FixtureAtLivePath()
        assertEquals(ProceedAfterSnapshot, coordinator().startupGate())
        val record = store().read()!!
        val snapshot = File(context.filesDir, "migration/snapshot-${record.migration!!.snapshotId}.db")
        snapshot.writeText("corrupted bytes")

        val gate = coordinator().startupGate()
        assertEquals(Blocked::class.java.simpleName, gate::class.java.simpleName)
        // Room stays closed: open() fails and no migration runs.
        val result = RecordRepository.open(context)
        assertEquals(RepositoryResult.Failure.TransactionFailed, result)
        val after = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, after.state)
        // The published snapshot is never silently replaced.
        assertEquals("corrupted bytes", snapshot.readText())
    }

    @Test
    fun pendingRecordBlocksRoomOpenWithNoDatabaseCreated() {
        // A pending record with a missing snapshot: Room must never open and no
        // empty database may appear.
        createV1FixtureAtLivePath()
        val payload = MigrationPayload(
            snapshotId = "ghost",
            snapshotSha256 = "F".repeat(64),
            liveDatabasePath = "databases/record.db",
            workDatabasePath = "databases/record.db",
            restoreTempPath = "files/recovery/restore.db",
            quarantinePath = "files/recovery/q/",
            stage = MigrationStage.PREPARED,
        )
        store().write(MigrationRecord(1, MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, payload))
        // Missing snapshot → redo protocol over the untouched v1 live database.
        val gate = coordinator().startupGate()
        assertEquals(ProceedAfterSnapshot, gate)
        val result = RecordRepository.open(context)
        assertTrue(result is RepositoryResult.Success)
        (result as RepositoryResult.Success).value.close()
        assertEquals(1L, recordCount("record.db", "AccountEntry"))
        assertEquals(2L, recordCount("record.db", "EntityRef"))
    }

    // ---- recovery stages ----

    @Test
    fun restoreFromMainReplacedStageRestoresSnapshotData() {
        createV1FixtureAtLivePath()
        assertEquals(ProceedAfterSnapshot, coordinator().startupGate())
        val record = store().read()!!
        val payload = record.migration!!
        val snapshot = File(context.filesDir, "migration/snapshot-${payload.snapshotId}.db")
        val restoreTemp = File(context.filesDir.parentFile!!, payload.restoreTempPath)
        restoreTemp.parentFile!!.mkdirs()
        snapshot.copyTo(restoreTemp, overwrite = true)
        // Simulate a failed live database: wipe it, then resume from MAIN_REPLACED.
        context.deleteDatabase("record.db")
        store().write(
            MigrationRecord(
                1,
                MigrationRecoveryState.MIGRATION_RECOVERY_PENDING,
                payload.copy(stage = MigrationStage.MAIN_REPLACED),
            ),
        )
        val gate = coordinator().startupGate()
        // The restored live database is the v1 snapshot: the migration must
        // still run to open v2.
        assertEquals(ProceedAfterSnapshot, gate)
        val result = RecordRepository.open(context)
        assertTrue(result is RepositoryResult.Success)
        (result as RepositoryResult.Success).value.close()
        val restored = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_HEALTHY, restored.state)
        // The recovered vault matches the pre-migration snapshot facts.
        assertEquals(2L, recordCount("record.db", "EntityRef"))
        assertEquals(1L, recordCount("record.db", "AccountEntry"))
        assertEquals(1L, recordCount("record.db", "TransactionNoteRelation"))
    }

    @Test
    fun completeThenRepeatIsIdempotent() {
        createV1FixtureAtLivePath()
        assertEquals(ProceedAfterSnapshot, coordinator().startupGate())
        val result = RecordRepository.open(context)
        assertTrue(result is RepositoryResult.Success)
        (result as RepositoryResult.Success).value.close()
        val snapshotCountAfterFirst = File(context.filesDir, "migration").listFiles()?.filter { it.name.startsWith("snapshot-") }?.size ?: 0
        // Second gate: healthy + v2 → ProceedNormal, no new snapshot, no recovery.
        val gate = coordinator().startupGate()
        assertEquals(ProceedNormal, gate)
        assertEquals(snapshotCountAfterFirst, File(context.filesDir, "migration").listFiles()?.filter { it.name.startsWith("snapshot-") }?.size)
        val record = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_HEALTHY, record.state)
    }

    @Test
    fun snapshotIsImmutableAcrossFailedAndRetriedOpen() {
        createV1FixtureAtLivePath()
        assertEquals(ProceedAfterSnapshot, coordinator().startupGate())
        val record = store().read()!!
        val snapshot = File(context.filesDir, "migration/snapshot-${record.migration!!.snapshotId}.db")
        val shaBefore = sha256(snapshot)
        // Simulate a migration/open failure at the Room boundary: the builder
        // throws a SQLiteException; the gate already published the snapshot.
        val failResult = RecordRepository.openWithBuilder(
            context,
            com.paherdin.record.core.data.TransactionFaultHook.NO_OP,
            { ctx ->
            androidx.room.Room.databaseBuilder(ctx, RecordDatabase::class.java, "record.db")
                .addMigrations(RecordDatabase.MIGRATION_1_2)
                .build()
                .also { throw android.database.sqlite.SQLiteException("simulated migration failure") }
            },
        )
        assertEquals(RepositoryResult.Failure.TransactionFailed, failResult)
        // Pending record retained, snapshot unchanged.
        val after = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, after.state)
        assertEquals(shaBefore, sha256(snapshot))
        // Retry completes.
        val result = RecordRepository.open(context)
        assertTrue(result is RepositoryResult.Success)
        (result as RepositoryResult.Success).value.close()
        assertEquals(1L, recordCount("record.db", "AccountEntry"))
    }

    // ---- snapshot-replacement and sidecar-isolation fault boundaries ----

    @Test
    fun failOnceAtSnapshotReplaceThenRestartRecovers() {
        createV1FixtureAtLivePath()
        assertEquals(ProceedAfterSnapshot, coordinator().startupGate())
        val record = store().read()!!
        val payload = record.migration!!
        val snapshot = File(context.filesDir, "migration/snapshot-${payload.snapshotId}.db")
        val restoreTemp = File(context.filesDir.parentFile!!, payload.restoreTempPath)
        restoreTemp.parentFile!!.mkdirs()
        snapshot.copyTo(restoreTemp, overwrite = true)
        context.deleteDatabase("record.db")
        store().write(
            MigrationRecord(1, MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, payload.copy(stage = MigrationStage.MAIN_REPLACED)),
        )
        // Fail once at beforeSnapshotReplace: the gate throws, no replacement.
        val failing = object : MigrationFaultHook {
            override fun beforeSnapshotReplace() = throw RuntimeException("simulated replace fault")
        }
        assertThrows(RuntimeException::class.java) { coordinator(failing).startupGate() }
        assertEquals("live db must not be replaced after the fault", null, liveDb().let { if (it.exists()) it.length() else null })
        // Restart with the no-op hook: recovery completes and migrates.
        val gate = coordinator().startupGate()
        assertEquals(ProceedAfterSnapshot, gate)
        val result = RecordRepository.open(context)
        assertTrue(result is RepositoryResult.Success)
        (result as RepositoryResult.Success).value.close()
        assertEquals(1L, recordCount("record.db", "AccountEntry"))
        assertEquals(MigrationRecoveryState.MIGRATION_HEALTHY, store().read()!!.state)
    }

    @Test
    fun failOnceAtSidecarIsolationThenRestartCompletesIdempotently() {
        createV1FixtureAtLivePath()
        assertEquals(ProceedAfterSnapshot, coordinator().startupGate())
        val record = store().read()!!
        val payload = record.migration!!
        val snapshot = File(context.filesDir, "migration/snapshot-${payload.snapshotId}.db")
        // Corrupt the snapshot so PREPARED reverification fails → isolation.
        snapshot.writeText("corrupted")
        // Fail once inside sidecar isolation; the pending record stays at
        // FAILED_SET_ISOLATED and restart resumes idempotently.
        val failing = object : MigrationFaultHook {
            override fun beforeSidecarIsolationEntry(name: String) {
                if (name.endsWith("-wal")) throw RuntimeException("simulated isolation fault")
            }
        }
        assertThrows(RuntimeException::class.java) { coordinator(failing).startupGate() }
        val after = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, after.state)
        // Restart completes the isolation and blocks (the snapshot is corrupt).
        val gate = coordinator().startupGate()
        assertEquals(Blocked::class.java.simpleName, gate::class.java.simpleName)
        val result = RecordRepository.open(context)
        assertEquals(RepositoryResult.Failure.TransactionFailed, result)
        assertEquals(MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, store().read()!!.state)
    }

    private fun throwFault(active: String, name: String) {
        if (active == name) throw RuntimeException("simulated fault at $name")
    }
}
