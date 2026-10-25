package com.paherdin.record2.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class DataOperationGateTest {
    @Test
    fun restoreWaitsForStartedWriteThenRejectsNewWrites() = runBlocking {
        val gate = DataOperationGate(drainTimeoutMs = 5_000)
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val writer = launch {
            gate.withWrite {
                started.complete(Unit)
                delay(100)
                finished.complete(Unit)
            }
        }
        started.await()
        val restore = async { gate.beginRestore() }
        delay(20)
        assertFalse(restore.isCompleted)
        assertFalse(finished.isCompleted)
        finished.await()
        val lease = restore.await()
        val error = assertThrows(DataOperationBusyException::class.java) {
            runBlocking { gate.withWrite { } }
        }
        assertTrue(error.message!!.contains("Restore"))
        lease.close()
        var entered = false
        gate.withWrite { entered = true }
        assertTrue(entered)
        writer.join()
    }

    @Test
    fun drainTimeoutAbandonsRestoreWithoutLeavingTheGateClosed() = runBlocking {
        val gate = DataOperationGate(drainTimeoutMs = 30)
        val started = CompletableDeferred<Unit>()
        val writer = launch {
            gate.withWrite {
                started.complete(Unit)
                delay(300)
            }
        }
        started.await()
        assertThrows(DataOperationBusyException::class.java) {
            runBlocking { gate.beginRestore() }
        }
        writer.join()
        val lease = gate.beginRestore()
        lease.close()
    }

    @Test
    fun cancelledDrainReleasesTheRestoreGate() = runBlocking {
        val gate = DataOperationGate(drainTimeoutMs = 5_000)
        val started = CompletableDeferred<Unit>()
        val writer = launch {
            gate.withWrite {
                started.complete(Unit)
                delay(300)
            }
        }
        started.await()
        val waiter = launch {
            runCatching { gate.beginRestore() }
        }
        delay(20)
        waiter.cancelAndJoin()
        writer.join()
        val export = gate.beginExport()
        export.close()
        var written = false
        gate.withWrite { written = true }
        assertTrue(written)
    }

    @Test
    fun exportAndRestoreAreMutuallyExclusiveButExportAllowsWrites() = runBlocking {
        val gate = DataOperationGate()
        val export = gate.beginExport()
        var writeEntered = false
        gate.withWrite { writeEntered = true }
        assertTrue(writeEntered)
        assertThrows(DataOperationBusyException::class.java) {
            runBlocking { gate.beginRestore() }
        }
        export.close()
        val restore = gate.beginRestore()
        assertThrows(DataOperationBusyException::class.java) {
            runBlocking { gate.beginExport() }
        }
        restore.close()
    }
}
