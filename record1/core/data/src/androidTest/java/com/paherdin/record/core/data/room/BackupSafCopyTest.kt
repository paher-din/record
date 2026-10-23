package com.paherdin.record.core.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paherdin.record.core.data.BackupArtifact
import com.paherdin.record.core.data.BackupTarget
import com.paherdin.record.core.data.NewAccount
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.NewTransaction
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.openForTest
import com.paherdin.record.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupSafCopyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("saf_test.db")
    }

    private suspend fun seed(repo: RecordRepository) {
        assertEquals(RepositoryResult.Success(Unit), repo.createAccount(NewAccount("acc-1", "Wallet", "CNY")))
        assertEquals(
            RepositoryResult.Success(Unit),
            repo.saveTransactionAggregate(
                NewTransaction(
                    id = "tx-1",
                    type = TransactionType.INCOME,
                    createdAt = 1,
                    updatedAt = 1,
                    deletedAt = null,
                    entries = listOf(NewAccountEntry("e-1", "acc-1", 5000)),
                    relationNoteIds = emptySet(),
                    attachmentContentIds = emptySet(),
                ),
            ),
        )
    }

    private fun newProvider(): TestProviderTarget = TestProviderTarget(File(context.cacheDir, "provider-${System.nanoTime()}.zip"))

    @Test
    fun completeProviderCopyVerifiesAndClearsMarker() = runBlocking {
        val repo = openForTest(context, "saf_test.db")
        seed(repo)
        val provider = newProvider()
        val result = repo.exportBackup(BackupTarget.Provider(provider))
        assertTrue("provider export failed: $result", result is RepositoryResult.Success)
        val artifact = (result as RepositoryResult.Success).value as BackupArtifact
        assertTrue("marker cleared after complete verified copy", !provider.isIncompleteMarked())
        assertTrue("provider verified", provider.verified)
        assertTrue("provider marker cleared", provider.markerCleared)
        assertTrue("provider wrote bytes", provider.writtenBytes() > 0)
        assertTrue("artifact has a backup id", artifact.backupId.isNotBlank())
        repo.close()
    }

    @Test
    fun providerCopyFaultMatrixKeepsObjectIncompleteAndRetries() = runBlocking {
        val repo = openForTest(context, "saf_test.db")
        seed(repo)
        // Small chunk size so a small package yields first/middle/final chunks.
        repo.setProviderChunkBytesForTest(64)

        // A failed step must leave a partial provider object incomplete-marked
        // (or never created), never reported complete, and retry must succeed.
        val run: suspend (TestProviderTarget, String, Int?, Boolean) -> Unit = { provider, label, chunkOrdinal, expectMarker ->
            if (chunkOrdinal != null) provider.failChunk(chunkOrdinal) else provider.failAt(label)
            val result = repo.exportBackup(BackupTarget.Provider(provider))
            assertTrue("$label: expected failure, got $result", result is RepositoryResult.Failure)
            if (expectMarker) {
                assertTrue("$label: provider stays incomplete-marked", provider.isIncompleteMarked())
            } else {
                assertFalse("$label: create-fault provider was never created", provider.isIncompleteMarked())
            }
            provider.clearFault()
            val retried = repo.exportBackup(BackupTarget.Provider(provider))
            assertTrue("$label: retry must succeed, got $retried", retried is RepositoryResult.Success)
            assertFalse("$label: retry clears the marker", provider.isIncompleteMarked())
            assertTrue("$label: retry verifies", provider.verified)
        }

        // First, middle, and final chunk ordinals over a small-chunk copy.
        val probe = newProvider()
        assertTrue(repo.exportBackup(BackupTarget.Provider(probe)) is RepositoryResult.Success)
        val chunkCount = probe.chunkCount()
        assertTrue("fixture must span multiple chunks, got $chunkCount", chunkCount >= 3)
        for (ordinal in listOf(0, chunkCount / 2, chunkCount - 1)) {
            run(newProvider(), "chunk[$ordinal]", ordinal, true)
        }

        run(newProvider(), "create", null, false)
        for (step in listOf("flushClose", "verify", "rename", "clear")) {
            run(newProvider(), step, null, true)
        }
        repo.close()
    }

    @Test
    fun renameUnsupportedStillSucceedsAfterVerification() = runBlocking {
        val repo = openForTest(context, "saf_test.db")
        seed(repo)
        val provider = newProvider()
        provider.setRenameUnsupported()
        val result = repo.exportBackup(BackupTarget.Provider(provider))
        assertTrue("rename-unsupported provider must still succeed after verify, got $result", result is RepositoryResult.Success)
        assertTrue(provider.verified)
        assertFalse("marker cleared despite rename being unsupported", provider.isIncompleteMarked())
        repo.close()
    }
}
