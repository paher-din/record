package com.paherdin.record2.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class BackupStagingTest {
    @Test
    fun activeTaskSurvivesSweepUntilReleased() {
        val root = temporaryFolder.newFolder("active")
        val task = BackupStaging.createTask(root, "export")
        assertTrue(BackupStaging.sweep(root).deleted.isEmpty())
        assertTrue(task.isDirectory)
        BackupStaging.releaseTask(task)
        assertEquals(listOf(task.name), BackupStaging.sweep(root).deleted)
        assertFalse(task.exists())
    }

    @Test
    fun creationWaitsForInFlightSweepAndThenRegistersAtomically() {
        val root = temporaryFolder.newFolder("overlap")
        File(root, "export-11111111-1111-4111-8111-111111111111").mkdir()
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val sweep = pool.submit<StagingSweepReport> {
                BackupStaging.sweep(root) { child ->
                    entered.countDown()
                    check(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    child.deleteRecursively()
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val created = pool.submit<File> { BackupStaging.createTask(root, "export") }
            org.junit.Assert.assertThrows(java.util.concurrent.TimeoutException::class.java) {
                created.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            proceed.countDown(); sweep.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val task = created.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(BackupStaging.sweep(root).deleted.isEmpty())
            assertTrue(task.isDirectory)
            BackupStaging.releaseTask(task)
        } finally { proceed.countDown(); pool.shutdownNow() }
    }
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sweepDeletesOnlyCanonicalTaskDirectories() {
        val root = temporaryFolder.newFolder("staging")
        val tasks = listOf(
            "preflight-11111111-1111-4111-8111-111111111111",
            "restore-22222222-2222-4222-8222-222222222222",
            "export-33333333-3333-4333-8333-333333333333",
        )
        tasks.forEach { name -> File(root, name).apply { mkdirs(); File(this, "leftover").writeText("x") } }
        File(root, "keep").mkdirs()
        File(root, "keep/data").writeText("x")
        File(root, "preflight-not-a-uuid").mkdirs()
        File(root, "preflight-44444444-4444-4444-8444-44444444444z").mkdirs()
        File(root, "preflight-55555555-5555-4555-8555-555555555555").writeText("file, not directory")

        val report = BackupStaging.sweep(root)

        assertEquals(tasks.sorted(), report.deleted.sorted())
        assertTrue(report.failed.isEmpty())
        tasks.forEach { assertFalse(File(root, it).exists()) }
        assertTrue(File(root, "keep/data").isFile)
        assertTrue(File(root, "preflight-not-a-uuid").isDirectory)
        assertTrue(File(root, "preflight-44444444-4444-4444-8444-44444444444z").isDirectory)
        assertTrue(File(root, "preflight-55555555-5555-4555-8555-555555555555").isFile)
    }

    @Test
    fun deleteFailureIsReportedWithoutThrowingOrBlockingOtherTasks() {
        val root = temporaryFolder.newFolder("staging-failure")
        val failedName = "restore-66666666-6666-4666-8666-666666666666"
        val deletedName = "preflight-77777777-7777-4777-8777-777777777777"
        File(root, failedName).apply { mkdirs(); File(this, "leftover").writeText("x") }
        File(root, deletedName).apply { mkdirs(); File(this, "leftover").writeText("x") }

        val report = BackupStaging.sweep(root) { directory ->
            if (directory.name == failedName) false else directory.deleteRecursively()
        }

        assertEquals(listOf(failedName), report.failed)
        assertEquals(listOf(deletedName), report.deleted)
        assertTrue(File(root, failedName).isDirectory)
        assertFalse(File(root, deletedName).exists())

        val retry = BackupStaging.sweep(root)
        assertEquals(listOf(failedName), retry.deleted)
        assertFalse(File(root, failedName).exists())
    }

    @Test
    fun missingRootIsANoOp() {
        val report = BackupStaging.sweep(File(temporaryFolder.root, "absent"))
        assertTrue(report.deleted.isEmpty())
        assertTrue(report.failed.isEmpty())
    }
}
