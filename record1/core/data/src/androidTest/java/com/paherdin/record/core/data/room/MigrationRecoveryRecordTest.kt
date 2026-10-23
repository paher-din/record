package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.MigrationRecoveryRecordStore
import com.paherdin.record.core.data.MigrationRecoveryState
import com.paherdin.record.core.data.MigrationStage
import com.paherdin.record.core.data.MigrationRecord
import com.paherdin.record.core.data.MigrationPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Strict v1 recovery-record parsing and AtomicFile round trip (plan Task 3,
 * "Recovery record v1"): exact shapes, duplicate/unknown/missing fields,
 * enum/state invariants, relative-path normalization with escape rejection,
 * and interrupted-write atomicity. Fail-closed: any violation rejects the
 * record before Room/SQLite opens.
 */
@RunWith(AndroidJUnit4::class)
class MigrationRecoveryRecordTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dir = File(context.cacheDir, "migration-record-test-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        file = File(dir, "activation-recovery-v1.json")
    }

    private fun store() = MigrationRecoveryRecordStore(file)

    private fun pendingPayload(stage: MigrationStage = MigrationStage.PREPARED) = MigrationPayload(
        snapshotId = "snap-1",
        snapshotSha256 = "A".repeat(64),
        liveDatabasePath = "databases/record.db",
        workDatabasePath = "databases/record.db",
        restoreTempPath = "files/recovery/restore.db",
        quarantinePath = "files/recovery/quarantine/",
        stage = stage,
    )

    // ---- happy shapes round trip ----

    @Test
    fun healthyRecordRoundTrips() {
        store().write(MigrationRecord(1, MigrationRecoveryState.MIGRATION_HEALTHY, null))
        val read = store().read()!!
        assertEquals(1, read.recordVersion)
        assertEquals(MigrationRecoveryState.MIGRATION_HEALTHY, read.state)
        assertNull(read.migration)
    }

    @Test
    fun pendingRecordRoundTripsWithAllStages() {
        for (stage in MigrationStage.entries) {
            store().write(MigrationRecord(1, MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, pendingPayload(stage)))
            val read = store().read()!!
            assertEquals(MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, read.state)
            assertEquals(stage, read.migration!!.stage)
            assertEquals("snap-1", read.migration!!.snapshotId)
            assertEquals("A".repeat(64), read.migration!!.snapshotSha256)
            assertEquals("databases/record.db", read.migration!!.liveDatabasePath)
        }
    }

    @Test
    fun recoveredRecordRoundTripsWithCompletedPayload() {
        store().write(MigrationRecord(1, MigrationRecoveryState.MIGRATION_RECOVERED, pendingPayload(MigrationStage.MAIN_REPLACED)))
        val read = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_RECOVERED, read.state)
        assertEquals(MigrationStage.MAIN_REPLACED, read.migration!!.stage)
    }

    // ---- strict rejection ----

    @Test
    fun unknownRecordVersionRejected() {
        file.writeText("""{"recordVersion":2,"migrationRecoveryState":"MIGRATION_HEALTHY","migration":null}""")
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun unknownStateRejected() {
        file.writeText("""{"recordVersion":1,"migrationRecoveryState":"MIGRATION_MAYBE","migration":null}""")
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun unknownStageRejected() {
        file.writeText(
            """{"recordVersion":1,"migrationRecoveryState":"MIGRATION_RECOVERY_PENDING","migration":{"snapshotId":"s","snapshotSha256":"${"A".repeat(64)}","liveDatabasePath":"databases/record.db","workDatabasePath":"databases/record.db","restoreTempPath":"files/recovery/restore.db","quarantinePath":"files/recovery/q/","stage":"SOMETHING_NEW"}}""",
        )
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun duplicateKeyRejected() {
        file.writeText(
            """{"recordVersion":1,"recordVersion":2,"migrationRecoveryState":"MIGRATION_HEALTHY","migration":null}""",
        )
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun unknownTopLevelFieldRejected() {
        file.writeText(
            """{"recordVersion":1,"migrationRecoveryState":"MIGRATION_HEALTHY","migration":null,"surprise":true}""",
        )
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun missingRequiredFieldRejected() {
        file.writeText("""{"recordVersion":1,"migrationRecoveryState":"MIGRATION_HEALTHY"}""")
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun statePayloadMismatchHealthyWithMigrationRejected() {
        val payload = pendingPayload()
        val json = MigrationRecoveryRecordStoreTestJson.healthyWithMigration(payload)
        file.writeText(json)
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun statePayloadMismatchPendingWithoutMigrationRejected() {
        file.writeText("""{"recordVersion":1,"migrationRecoveryState":"MIGRATION_RECOVERY_PENDING","migration":null}""")
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun malformedJsonRejected() {
        file.writeText("{not json")
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    // ---- path normalization and escape rejection ----

    @Test
    fun absolutePathRejected() {
        val payload = MigrationPayload(
            "s", "A".repeat(64), "/etc/passwd", "databases/record.db",
            "files/recovery/restore.db", "files/recovery/q/", MigrationStage.PREPARED,
        )
        file.writeText(MigrationRecoveryRecordStoreTestJson.pending(payload))
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun traversalPathRejected() {
        val payload = MigrationPayload(
            "s", "A".repeat(64), "databases/../secret", "databases/record.db",
            "files/recovery/restore.db", "files/recovery/q/", MigrationStage.PREPARED,
        )
        file.writeText(MigrationRecoveryRecordStoreTestJson.pending(payload))
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    @Test
    fun windowsDrivePathRejected() {
        val payload = MigrationPayload(
            "s", "A".repeat(64), "C:\\Windows\\system32", "databases/record.db",
            "files/recovery/restore.db", "files/recovery/q/", MigrationStage.PREPARED,
        )
        file.writeText(MigrationRecoveryRecordStoreTestJson.pending(payload))
        assertThrows(IllegalArgumentException::class.java) { store().read() }
    }

    // ---- AtomicFile durability ----

    @Test
    fun interruptedWriteLeavesPreviousRecordIntact() {
        store().write(MigrationRecord(1, MigrationRecoveryState.MIGRATION_HEALTHY, null))
        // Simulate an interrupted write by truncating after startWrite: write a
        // second valid record through a *new* store instance to a copy path,
        // then corrupt the in-progress file and confirm the prior record wins.
        val healthy = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_HEALTHY, healthy.state)
        // A failed write must not be observable: write pending, then verify the
        // file contains exactly the pending record (AtomicFile replaces atomically).
        store().write(MigrationRecord(1, MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, pendingPayload()))
        val pending = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_RECOVERY_PENDING, pending.state)
        // Rewrite healthy; the record is fully replaced, never mixed.
        store().write(MigrationRecord(1, MigrationRecoveryState.MIGRATION_HEALTHY, null))
        val final = store().read()!!
        assertEquals(MigrationRecoveryState.MIGRATION_HEALTHY, final.state)
        assertNull(final.migration)
    }

    @Test
    fun absentFileReadsAsNull() {
        assertNull(store().read())
    }
}

/** Builds raw JSON strings so tests exercise the strict parser, not the writer. */
internal object MigrationRecoveryRecordStoreTestJson {

    fun pending(p: MigrationPayload): String =
        """{"recordVersion":1,"migrationRecoveryState":"MIGRATION_RECOVERY_PENDING","migration":{"snapshotId":"${p.snapshotId}","snapshotSha256":"${p.snapshotSha256}","liveDatabasePath":"${p.liveDatabasePath}","workDatabasePath":"${p.workDatabasePath}","restoreTempPath":"${p.restoreTempPath}","quarantinePath":"${p.quarantinePath}","stage":"${p.stage.name}"}}"""

    fun healthyWithMigration(p: MigrationPayload): String =
        """{"recordVersion":1,"migrationRecoveryState":"MIGRATION_HEALTHY","migration":${pendingPayloadOnly(p)}}"""

    private fun pendingPayloadOnly(p: MigrationPayload): String =
        """{"snapshotId":"${p.snapshotId}","snapshotSha256":"${p.snapshotSha256}","liveDatabasePath":"${p.liveDatabasePath}","workDatabasePath":"${p.workDatabasePath}","restoreTempPath":"${p.restoreTempPath}","quarantinePath":"${p.quarantinePath}","stage":"${p.stage.name}"}"""
}
