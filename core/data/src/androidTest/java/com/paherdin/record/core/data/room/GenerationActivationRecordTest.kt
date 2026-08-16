package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.MigrationRecoveryState
import com.paherdin.record.core.data.restore.ActivationRecord
import com.paherdin.record.core.data.restore.ActivationRecordStore
import com.paherdin.record.core.data.restore.GenerationActivation
import com.paherdin.record.core.data.restore.GenerationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Strict version-2 activation-record shapes and AtomicFile round trips. */
@RunWith(AndroidJUnit4::class)
class GenerationActivationRecordTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "generation-record-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        file = File(dir, "activation-recovery-v1.json")
    }

    @Test
    fun healthyAndPendingShapesRoundTrip() {
        val healthy = record(
            GenerationActivation(
                activeGenerationId = id(1),
                previousGenerationId = null,
                candidateGenerationId = null,
                retiringGenerationId = id(2),
                state = GenerationState.HEALTHY,
            ),
        )
        val store = ActivationRecordStore(file)
        store.write(healthy)
        assertEquals(2, store.recordVersion())
        assertEquals(healthy, store.read())

        val pending = record(
            GenerationActivation(
                activeGenerationId = id(3),
                previousGenerationId = id(1),
                candidateGenerationId = id(3),
                retiringGenerationId = null,
                state = GenerationState.PENDING,
            ),
        )
        store.write(pending)
        assertEquals(pending, store.read())
    }

    @Test
    fun pendingRequiresPreviousAndMatchingCandidate() {
        assertThrows(IllegalArgumentException::class.java) {
            GenerationActivation(id(1), null, id(1), null, GenerationState.PENDING)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationActivation(id(1), id(2), null, null, GenerationState.PENDING)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationActivation(id(1), id(2), id(3), null, GenerationState.PENDING)
        }
    }

    @Test
    fun healthyClearsRollbackPointersAndNeverRetiresActiveGeneration() {
        assertThrows(IllegalArgumentException::class.java) {
            GenerationActivation(id(1), id(2), null, null, GenerationState.HEALTHY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationActivation(id(1), null, id(2), null, GenerationState.HEALTHY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationActivation(id(1), null, null, id(1), GenerationState.HEALTHY)
        }
    }

    @Test
    fun generationIdsAreCanonicalAndCannotEscapeVaultRoot() {
        for (invalid in listOf("", "../record.db", "gen-not-a-uuid", "/absolute", "GEN-00000000-0000-0000-0000-000000000001")) {
            assertThrows("accepted invalid ID: $invalid", IllegalArgumentException::class.java) {
                GenerationActivation(invalid, null, null, null, GenerationState.HEALTHY)
            }
        }
    }

    @Test
    fun duplicateUnknownMissingAndVersionOneRecordsFailClosedForNormalReader() {
        val store = ActivationRecordStore(file)
        file.writeText("""{"recordVersion":2,"recordVersion":2,"migrationRecoveryState":"MIGRATION_HEALTHY","migration":null,"generationActivation":{}}""")
        assertThrows(IllegalArgumentException::class.java) { store.read() }
        file.writeText("""{"recordVersion":2,"migrationRecoveryState":"MIGRATION_HEALTHY","migration":null,"generationActivation":{"activeGenerationId":"${id(1)}","previousGenerationId":null,"candidateGenerationId":null,"retiringGenerationId":null,"state":"HEALTHY"},"unknown":1}""")
        assertThrows(IllegalArgumentException::class.java) { store.read() }
        file.writeText("""{"recordVersion":2,"migrationRecoveryState":"MIGRATION_HEALTHY","migration":null}""")
        assertThrows(IllegalArgumentException::class.java) { store.read() }
        file.writeText("""{"recordVersion":1,"migrationRecoveryState":"MIGRATION_HEALTHY","migration":null}""")
        assertEquals(1, store.recordVersion())
        assertThrows(IllegalArgumentException::class.java) { store.read() }
    }

    private fun record(generation: GenerationActivation) = ActivationRecord(
        migrationRecoveryState = MigrationRecoveryState.MIGRATION_HEALTHY,
        migration = null,
        generationActivation = generation,
    )

    private fun id(value: Int): String = "gen-00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
