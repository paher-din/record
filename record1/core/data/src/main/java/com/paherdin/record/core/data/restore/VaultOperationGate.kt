package com.paherdin.record.core.data.restore

import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * Repository-wide shared/exclusive operation gate (spec 4.5, ADR 0010, plan
 * Task 8). Ordinary reads, writes, export, staging, attachment work, and
 * maintenance acquire the shared side; generation bootstrap, restore
 * activation, rollback, startup resolution, and backing-database replacement
 * acquire the exclusive side. No new shared operation begins while an
 * exclusive transition is in progress; confirmation drains shared leases,
 * swaps the binding, then releases the gate.
 *
 * `inline` so the caller's body keeps its early `return` statements; the
 * inline function's `finally` still releases the lease on any non-local
 * return. ponytail: blocking lock in suspend context is acceptable for the
 * single-process app.
 */
internal class VaultOperationGate {
    private val lock = ReentrantLock()
    private val gateCondition: Condition = lock.newCondition()
    private var exclusive = false
    private var activeShared = 0

    inline suspend fun <R> shared(block: suspend () -> R): R {
        lock.lock()
        try {
            while (exclusive) gateCondition.await()
            activeShared++
        } finally {
            lock.unlock()
        }
        return try {
            block()
        } finally {
            lock.lock()
            try {
                activeShared--
                if (activeShared == 0) gateCondition.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }

    inline suspend fun <R> exclusive(block: suspend () -> R): R {
        lock.lock()
        try {
            while (exclusive) gateCondition.await()
            exclusive = true
            while (activeShared > 0) gateCondition.await()
        } finally {
            lock.unlock()
        }
        return try {
            block()
        } finally {
            lock.lock()
            try {
                exclusive = false
                gateCondition.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }

    /** Blocking exclusive variant for non-suspend callers such as `close()`. */
    fun <R> exclusiveBlocking(block: () -> R): R {
        lock.lock()
        try {
            while (exclusive) gateCondition.await()
            exclusive = true
            while (activeShared > 0) gateCondition.await()
        } finally {
            lock.unlock()
        }
        return try {
            block()
        } finally {
            lock.lock()
            try {
                exclusive = false
                gateCondition.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }
}
