package com.paherdin.record.core.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plan Task 5 — the shared keyed `Mutex` registry: same hash serializes,
 * different hashes proceed independently, cancellation releases references,
 * and the registry returns to zero entries without an acquire/remove race.
 */
class AttachmentHashLockRegistryTest {

    @Test
    fun sameHashSerializes() = runBlocking {
        val registry = AttachmentHashLockRegistry()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val results = coroutineScope {
            (1..10).map {
                async {
                    registry.withLock("same-hash") {
                        val now = active.incrementAndGet()
                        maxActive.updateAndGet { if (it < now) now else it }
                        delay(5)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(10, results.size)
        assertEquals("same-hash sections must never overlap", 1, maxActive.get())
        assertEquals(0, registry.size())
    }

    @Test
    fun differentHashesProceedIndependently() = runBlocking {
        val registry = AttachmentHashLockRegistry()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        coroutineScope {
            (1..10).map {
                async {
                    registry.withLock("hash-$it") {
                        val now = active.incrementAndGet()
                        maxActive.updateAndGet { if (it < now) now else it }
                        delay(5)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertTrue("different hashes must overlap", maxActive.get() > 1)
        assertEquals(0, registry.size())
    }

    @Test
    fun cancellationReleasesReferencesAndRegistryReturnsToZero() = runBlocking {
        val registry = AttachmentHashLockRegistry()
        // Acquire then cancel the job mid-section.
        val job = async {
            registry.withLock("cancelled-hash") {
                delay(10_000)
            }
        }
        delay(20)
        job.cancel()
        try {
            job.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals("cancellation must release the reference", 0, registry.size())
        // The hash is usable again.
        registry.withLock("cancelled-hash") { }
        assertEquals(0, registry.size())
    }

    @Test
    fun nestedAcquireOfDifferentHashesInsideOneSectionWorks() = runBlocking {
        val registry = AttachmentHashLockRegistry()
        registry.withLock("outer") {
            registry.withLock("inner") { }
        }
        assertEquals(0, registry.size())
    }
}
