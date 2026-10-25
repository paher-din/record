package com.paherdin.record2.backup

import java.time.Clock
import kotlinx.coroutines.delay

/**
 * Process-wide data operation gate. Export holds a read lease without blocking ordinary writes;
 * restore is exclusive for the whole gate/prepare/import window. The write counter is entered
 * inside each write transaction, so a restore lease waits for already-started short writes and
 * rejects every write that begins after the gate closes.
 */
internal class DataOperationGate(
    private val clock: Clock = Clock.systemUTC(),
    private val drainTimeoutMs: Long = BackupLimits.GATE_DRAIN_TIMEOUT_MS,
) {
    internal enum class Mode { OPEN, EXPORT, RESTORE }

    private val lock = Any()
    private var mode = Mode.OPEN
    private var activeWrites = 0

    val isRestoreInProgress: Boolean
        get() = synchronized(lock) { mode == Mode.RESTORE }

    val activeWriteCount: Int
        get() = synchronized(lock) { activeWrites }

    suspend fun <T> withWrite(block: suspend () -> T): T {
        synchronized(lock) {
            if (mode == Mode.RESTORE) {
                throw DataOperationBusyException("Restore is in progress")
            }
            activeWrites++
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                activeWrites--
            }
        }
    }

    suspend fun beginExport(): Lease {
        synchronized(lock) {
            if (mode != Mode.OPEN) throw DataOperationBusyException("Another data operation is in progress")
            mode = Mode.EXPORT
        }
        return Lease(Mode.EXPORT)
    }

    suspend fun beginRestore(): Lease {
        synchronized(lock) {
            if (mode != Mode.OPEN) throw DataOperationBusyException("Another data operation is in progress")
            mode = Mode.RESTORE
        }
        var handedOff = false
        try {
            val deadline = clock.millis() + drainTimeoutMs
            while (true) {
                val drained = synchronized(lock) { activeWrites == 0 }
                if (drained) {
                    handedOff = true
                    return Lease(Mode.RESTORE)
                }
                if (clock.millis() >= deadline) {
                    throw DataOperationBusyException("In-flight writes did not drain before the restore gate")
                }
                delay(5)
            }
        } finally {
            if (!handedOff) {
                synchronized(lock) {
                    if (mode == Mode.RESTORE) mode = Mode.OPEN
                }
            }
        }
    }

    fun requireAiUsable() {
        synchronized(lock) {
            if (mode == Mode.RESTORE) {
                throw DataOperationBusyException("Restore is in progress")
            }
        }
    }

    inner class Lease internal constructor(private val leasedMode: Mode) : AutoCloseable {
        private var released = false

        override fun close() {
            synchronized(lock) {
                if (released) return
                released = true
                if (mode == leasedMode) mode = Mode.OPEN
            }
        }
    }
}

internal object ProcessDataOperationGate {
    val gate = DataOperationGate()
}
