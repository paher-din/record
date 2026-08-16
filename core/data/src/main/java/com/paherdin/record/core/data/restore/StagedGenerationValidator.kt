package com.paherdin.record.core.data.restore

import com.paherdin.record.core.data.AttachmentValidator
import com.paherdin.record.core.data.FinanceMatrixValidator
import com.paherdin.record.core.data.FinanceViolationException
import com.paherdin.record.core.data.NewAccountEntry
import com.paherdin.record.core.data.room.RecordDatabase
import com.paherdin.record.core.data.room.RecordDao
import com.paherdin.record.model.EntityType
import java.io.File

internal sealed class GenerationValidation {
    internal data object Valid : GenerationValidation()
    internal data class Failed(val category: RecordLevelCategory, val detail: String) : GenerationValidation()
}

/**
 * Validates a complete generation database and its attachment tree without
 * touching any live vault (spec 4.5, plan Task 3/7): record counts, foreign
 * keys, the XOR identity invariant, the finance matrix, resulting balances,
 * endpoint ownership, attachment metadata/blob identity, and blob SHA-256.
 * Reused by the legacy bootstrap copy (Task 3) and the restore staging pass
 * (Task 7).
 */
internal class StagedGenerationValidator(
    private val database: RecordDatabase,
    private val attachmentContentDir: File,
    private val rejectExtraBlobs: Boolean = false,
) {
    private val dao: RecordDao get() = database.recordDao()

    suspend fun validate(): GenerationValidation {
        val refs = dao.allEntityRefs()
        val txs = dao.allTransactions()
        val accounts = dao.allAccounts()
        val entries = dao.allAccountEntries()
        val notes = dao.allNotes()
        val relations = dao.allRelations()
        val attachments = dao.allAttachments()
        val links = dao.allAttachmentLinks()

        // Record-level endpoint checks.
        for (e in entries) {
            if (txs.none { it.id == e.transactionId }) return failed(RecordLevelCategory.ForeignKey, "AccountEntry ${e.id} -> missing Transaction ${e.transactionId}")
            if (accounts.none { it.id == e.accountId }) return failed(RecordLevelCategory.Endpoint, "AccountEntry ${e.id} -> missing Account ${e.accountId}")
        }
        for (r in relations) {
            if (txs.none { it.id == r.transactionId }) return failed(RecordLevelCategory.ForeignKey, "relation ${r.transactionId} -> missing Transaction")
            if (notes.none { it.id == r.noteId }) return failed(RecordLevelCategory.ForeignKey, "relation ${r.noteId} -> missing Note")
        }
        for (l in links) {
            if (attachments.none { it.id == l.attachmentId }) return failed(RecordLevelCategory.AttachmentLinkDangling, "AttachmentLink -> missing Attachment ${l.attachmentId}")
            val owner = refs.firstOrNull { it.id == l.ownerEntityRefId }
                ?: return failed(RecordLevelCategory.AttachmentLinkDangling, "AttachmentLink -> missing owner ${l.ownerEntityRefId}")
            if (owner.entityType !in setOf(EntityType.TRANSACTION, EntityType.NOTE)) {
                return failed(RecordLevelCategory.Endpoint, "AttachmentLink owner ${l.ownerEntityRefId} is not Transaction/Note")
            }
            val ownerExists = if (owner.entityType == EntityType.TRANSACTION) txs.any { it.id == owner.id } else notes.any { it.id == owner.id }
            if (!ownerExists) return failed(RecordLevelCategory.AttachmentLinkDangling, "AttachmentLink owner ${l.ownerEntityRefId} subtype missing")
        }

        // XOR identity: EntityRef type matches its subtype table, no cross collisions.
        for (ref in refs) {
            val hasTx = txs.any { it.id == ref.id }
            val hasNote = notes.any { it.id == ref.id }
            val ok = when (ref.entityType) {
                EntityType.TRANSACTION -> hasTx && !hasNote
                EntityType.NOTE -> hasNote && !hasTx
                EntityType.PROJECT -> !hasTx && !hasNote
            }
            if (!ok) return failed(RecordLevelCategory.Xor, "EntityRef ${ref.id} type ${ref.entityType} inconsistent")
        }

        // Finance matrix per transaction.
        val currencyByAccount = accounts.associate { it.id to it.currency }
        val financeValidator = FinanceMatrixValidator()
        for (tx in txs) {
            val txEntries = entries.filter { it.transactionId == tx.id }
                .map { NewAccountEntry(it.id, it.accountId, it.amountMinor) }
            try {
                financeValidator.validate(tx.type, txEntries, currencyByAccount)
            } catch (e: FinanceViolationException) {
                return failed(RecordLevelCategory.Finance, "Transaction ${tx.id}: ${e.violation}")
            }
        }

        // Balances over effective entries: each account balance is the exact sum
        // of its effective AccountEntry values, so the reproducibility check is
        // exact arithmetic — an overflow produces BALANCE_INVALID.
        val effectiveEntryIds = refs
            .filter { it.deletedAt == null }
            .map { it.id }
            .toSet()
        entries
            .filter { it.transactionId in effectiveEntryIds }
            .groupBy { it.accountId }
            .forEach { (accountId, accountEntries) ->
                try {
                    accountEntries.fold(0L) { acc, entry -> Math.addExact(acc, entry.amountMinor) }
                } catch (e: ArithmeticException) {
                    return failed(RecordLevelCategory.Balance, "balance overflow for account $accountId")
                }
            }

        // Attachment metadata/blob identity.
        val seen = HashSet<String>()
        for (a in attachments) {
            if (!seen.add(a.contentIdentity)) return failed(RecordLevelCategory.AttachmentDuplicateIdentity, "duplicate contentIdentity ${a.contentIdentity}")
            val blob = File(attachmentContentDir, a.contentIdentity)
            if (!blob.isFile) return failed(RecordLevelCategory.AttachmentMissing, "blob missing for ${a.contentIdentity}")
            val actual = blob.inputStream().use { AttachmentValidator.sha256(it) }
            if (!actual.equals(a.contentIdentity, ignoreCase = true)) {
                return failed(RecordLevelCategory.AttachmentCorrupt, "blob hash mismatch for ${a.contentIdentity}")
            }
        }
        // A blob present without any referencing Attachment row is extra content.
        // For a restore staging candidate any extra blob is a rejection; for the
        // legacy bootstrap copy it is expected orphan residue cleaned later.
        if (rejectExtraBlobs) {
            val referenced = attachments.map { it.contentIdentity }.toSet()
            attachmentContentDir.listFiles { f -> f.isFile }?.forEach { blob ->
                if (blob.name !in referenced) {
                    return failed(RecordLevelCategory.AttachmentExtra, "unreferenced blob ${blob.name}")
                }
            }
        }
        return GenerationValidation.Valid
    }

    private fun failed(category: RecordLevelCategory, detail: String): GenerationValidation =
        GenerationValidation.Failed(category, detail)
}
