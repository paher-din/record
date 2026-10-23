package com.paherdin.record.core.data.restore

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.paherdin.record.core.data.JsonParseException
import com.paherdin.record.core.data.RestoreViolation
import com.paherdin.record.core.data.backup.LogicalBackupSchema
import com.paherdin.record.core.data.backup.LogicalType
import com.paherdin.record.core.data.backup.Manifest
import com.paherdin.record.core.data.backup.ManifestCodec
import com.paherdin.record.core.data.room.Account
import com.paherdin.record.core.data.room.AccountEntry
import com.paherdin.record.core.data.room.Attachment
import com.paherdin.record.core.data.room.AttachmentLink
import com.paherdin.record.core.data.room.EntityRef
import com.paherdin.record.core.data.room.Note
import com.paherdin.record.core.data.room.Transaction
import com.paherdin.record.core.data.room.TransactionNoteRelation
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal class StagingException(val violation: RestoreViolation) : RuntimeException()

internal sealed interface StagingOutcome {
    data class Ready(
        val candidateId: String,
        val receipt: StagingReceipt,
        val recordCounts: Map<String, Int>,
        val attachmentCount: Int,
    ) : StagingOutcome

    data class Rejected(val violation: RestoreViolation) : StagingOutcome
}

/**
 * Record-level staging import (spec 4.5, plan Task 7): parses the preflighted
 * package's eight datasets strictly, imports them in FK-safe deterministic
 * order into an isolated unreferenced generation, writes the attachment blobs,
 * validates the complete staged generation (record counts, foreign keys, XOR,
 * finance matrix, balances, endpoints, attachment identity/hash), and produces
 * the strict canonical receipt. The current vault is never touched; a failure
 * deletes the identifiable staging generation.
 */
internal class RestoreStager(
    private val context: Context,
    private val generationManager: GenerationManager,
) {
    suspend fun stage(source: File, manifest: Manifest, sourcePackageSha256: String): StagingOutcome {
        val genId = generationManager.newGenerationId()
        val genDir = generationManager.createGeneration(genId)
        val db = openGenerationDatabase(context, generationManager.databaseFile(genId))
        var failed = true
        try {
            val datasets = readDatasets(source, manifest)
            val blobs = readBlobs(source, manifest)
            db.withTransaction {
                val dao = db.recordDao()
                dao.insertAllEntityRefs(datasets[LogicalType.EntityRef].orEmpty().map { it as EntityRef })
                dao.insertAllAccounts(datasets[LogicalType.Account].orEmpty().map { it as Account })
                dao.insertAllTransactions(datasets[LogicalType.Transaction].orEmpty().map { it as Transaction })
                dao.insertAllNotes(datasets[LogicalType.Note].orEmpty().map { it as Note })
                dao.insertAllEntries(datasets[LogicalType.AccountEntry].orEmpty().map { it as AccountEntry })
                dao.insertAllRelations(datasets[LogicalType.TransactionNoteAssociation].orEmpty().map { it as TransactionNoteRelation })
                dao.insertAllAttachments(datasets[LogicalType.Attachment].orEmpty().map { it as Attachment })
                dao.insertAllAttachmentLinks(datasets[LogicalType.AttachmentLink].orEmpty().map { it as AttachmentLink })
            }
            val contentDir = File(generationManager.attachmentsDir(genId), "content")
            contentDir.mkdirs()
            blobs.forEach { (identity, bytes) -> File(contentDir, identity).writeBytes(bytes) }

            val validation = StagedGenerationValidator(db, contentDir, rejectExtraBlobs = true).validate()
            if (validation is GenerationValidation.Failed) {
                return StagingOutcome.Rejected(RestoreClassification.fromCategory(validation.category))
            }

            val recordCounts = datasets.mapKeys { it.key.name }.mapValues { it.value.size }
            val candidateDigest = candidateDigestOf(datasets, blobs.keys)
            val receipt = StagingReceipt(
                candidateId = genId,
                sourcePackageSha256 = sourcePackageSha256,
                manifestSha256 = sha256(ManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)),
                entryHashes = manifest.entries.associate { it.path to it.sha256 },
                recordCounts = recordCounts.mapValues { it.value.toLong() },
                attachmentCount = blobs.size,
                validationContractVersion = StagingReceipt.VALIDATION_CONTRACT_VERSION,
                candidateDigest = candidateDigest,
            )
            failed = false
            return StagingOutcome.Ready(genId, receipt, recordCounts, blobs.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StagingException) {
            return StagingOutcome.Rejected(e.violation)
        } catch (e: SQLiteConstraintException) {
            return StagingOutcome.Rejected(RestoreViolation.FOREIGN_KEY_INVALID)
        } catch (e: Exception) {
            return StagingOutcome.Rejected(RestoreViolation.RECORD_INVALID)
        } finally {
            db.close()
            if (failed) generationManager.deleteGenerationDirectory(genId)
        }
    }
    private fun readDatasets(source: File, manifest: Manifest): Map<LogicalType, List<Any>> {
        val result = LinkedHashMap<LogicalType, List<Any>>()
        for (type in LogicalBackupSchema.DATASET_ORDER) {
            val path = "${type.name}.jsonl"
            val manifestEntry = manifest.entries.firstOrNull { it.path == path }
                ?: throw StagingException(RestoreViolation.MANIFEST_INVALID)
            val text = readZipEntry(source, path) ?: throw StagingException(RestoreViolation.RECORD_INVALID)
            val records = try {
                text.lineSequence().filter { it.isNotBlank() }
                    .map { line -> LogicalBackupSchema.decode(type, line) }
                    .toList()
            } catch (e: JsonParseException) {
                throw StagingException(RestoreViolation.RECORD_INVALID)
            }
            if (records.size.toLong() != manifestEntry.recordCount) {
                throw StagingException(RestoreViolation.RECORD_COUNT_MISMATCH)
            }
            result[type] = records
        }
        return result
    }

    private fun readBlobs(source: File, manifest: Manifest): Map<String, ByteArray> {
        val blobs = LinkedHashMap<String, ByteArray>()
        for (entry in manifest.entries.filter { it.logicalType == "AttachmentContent" }) {
            val identity = entry.path.removePrefix("attachments/")
            val bytes = readZipEntryBytes(source, entry.path) ?: throw StagingException(RestoreViolation.ATTACHMENT_MISSING)
            if (sha256(bytes) != entry.sha256) throw StagingException(RestoreViolation.SHA256_MISMATCH)
            blobs[identity] = bytes
        }
        return blobs
    }

    private fun candidateDigestOf(datasets: Map<LogicalType, List<Any>>, contentIdentities: Set<String>): String {
        val sb = StringBuilder()
        LogicalBackupSchema.DATASET_ORDER.forEach { type ->
            sb.append(type.name).append('=').append(datasets[type].orEmpty().size).append('\n')
        }
        contentIdentities.sorted().forEach { sb.append("blob:").append(it).append('\n') }
        return sha256(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readZipEntry(source: File, name: String): String? = try {
        ZipFile(source).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    } catch (e: Exception) {
        null
    }

    private fun readZipEntryBytes(source: File, name: String): ByteArray? = try {
        ZipFile(source).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    } catch (e: Exception) {
        null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
