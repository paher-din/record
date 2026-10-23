package consumer

import com.paherdin.record.core.data.BackupArtifact
import com.paherdin.record.core.data.BackupProviderTarget
import com.paherdin.record.core.data.BackupStage
import com.paherdin.record.core.data.BackupTarget
import com.paherdin.record.core.data.BackupViolation
import com.paherdin.record.core.data.RecordRepository
import com.paherdin.record.core.data.RepositoryResult
import com.paherdin.record.core.data.RepositoryResult.Failure
import com.paherdin.record.core.data.RepositoryResult.Success
import com.paherdin.record.core.data.RestoreStage
import com.paherdin.record.core.data.RestoreViolation
import com.paherdin.record.core.data.StagedRestore
import java.io.File

/**
 * Positive consumer fixture: the additive backup/restore surface compiles —
 * targets, artifacts, staged handles, typed outcomes, and the three entry
 * points.
 */
class BackupSurfaceUsage(private val repository: RecordRepository) {

    suspend fun useBackup() {
        val privateTarget: BackupTarget = BackupTarget.PrivateFile(File("backup.zip"))
        val provider: BackupTarget = BackupTarget.Provider(NoopProvider())
        when (val exported: RepositoryResult<BackupArtifact> = repository.exportBackup(privateTarget)) {
            is Success -> {
                val artifact: BackupArtifact = exported.value
                val id: String = artifact.backupId
                val sha: String = artifact.sha256
            }
            is Failure -> when (val f: Failure = exported) {
                is Failure.BackupRejected -> {
                    val violation: BackupViolation = f.violation
                }
                is Failure.BackupIoFailed -> {
                    val stage: BackupStage = f.stage
                }
                else -> {}
            }
        }

        when (val staged: RepositoryResult<StagedRestore> = repository.stageRestore(File("backup.zip"))) {
            is Success -> {
                val handle: StagedRestore = staged.value
                val candidateId: String = handle.candidateId
                val digest: String = handle.validationDigest
                val counts: Map<String, Int> = handle.recordCounts
                val attachmentCount: Int = handle.attachmentCount
                when (val confirmed: RepositoryResult<Unit> = repository.confirmRestore(handle)) {
                    is Success -> {}
                    is Failure -> when (val f: Failure = confirmed) {
                        is Failure.RestoreRejected -> {
                            val violation: RestoreViolation = f.violation
                        }
                        is Failure.RestoreIoFailed -> {
                            val stage: RestoreStage = f.stage
                        }
                        else -> {}
                    }
                }
            }
            is Failure -> {}
        }

        repository.exportBackup(provider)
    }
}

private class NoopProvider : BackupProviderTarget {
    override suspend fun createIncomplete(): Boolean = true
    override suspend fun writeChunk(bytes: ByteArray) {}
    override suspend fun flushAndClose() {}
    override suspend fun verify(): Boolean = true
    override suspend fun renameToFinalIfSupported(): Boolean = true
    override suspend fun clearIncompleteMarker() {}
}
