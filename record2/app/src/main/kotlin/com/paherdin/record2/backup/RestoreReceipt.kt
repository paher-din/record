package com.paherdin.record2.backup

import java.nio.charset.StandardCharsets

internal data class RestoreReceipt(
    val formatVersion: Int,
    val sourceZipSha256: String,
    val sourceZipBytes: Long,
    val sourceFormatVersion: Int,
    val sourceSchemaVersion: Int,
    val sourceGeneration: String,
    val restoredGeneration: String,
    val restoredAt: Long,
    val contentDigest: String,
    val importedRows: Long,
    val businessRevisionAtRestore: Long,
)

internal object RestoreReceiptCodec {
    private val fields = listOf(
        "formatVersion", "sourceZipSha256", "sourceZipBytes", "sourceFormatVersion",
        "sourceSchemaVersion", "sourceGeneration", "restoredGeneration", "restoredAt",
        "contentDigest", "importedRows", "businessRevisionAtRestore",
    )

    fun encode(receipt: RestoreReceipt): String {
        validate(receipt, receipt.restoredAt)
        val text = buildString {
            append("{\"formatVersion\":").append(receipt.formatVersion)
            append(",\"sourceZipSha256\":").append(StrictJson.quote(receipt.sourceZipSha256))
            append(",\"sourceZipBytes\":").append(receipt.sourceZipBytes)
            append(",\"sourceFormatVersion\":").append(receipt.sourceFormatVersion)
            append(",\"sourceSchemaVersion\":").append(receipt.sourceSchemaVersion)
            append(",\"sourceGeneration\":").append(StrictJson.quote(receipt.sourceGeneration))
            append(",\"restoredGeneration\":").append(StrictJson.quote(receipt.restoredGeneration))
            append(",\"restoredAt\":").append(receipt.restoredAt)
            append(",\"contentDigest\":").append(StrictJson.quote(receipt.contentDigest))
            append(",\"importedRows\":").append(receipt.importedRows)
            append(",\"businessRevisionAtRestore\":").append(receipt.businessRevisionAtRestore)
            append('}')
        }
        if (text.toByteArray(StandardCharsets.UTF_8).size > BackupLimits.MAX_RECEIPT_BYTES) {
            fail(BackupError.Internal, "restore receipt exceeds 4096 bytes")
        }
        return text
    }

    fun decode(text: String, nowMillis: Long): RestoreReceipt {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > BackupLimits.MAX_RECEIPT_BYTES) {
            fail(BackupError.TargetInvalid, "restore receipt exceeds 4096 bytes")
        }
        val root = try {
            StrictJson.decodeObject(bytes, BackupLimits.MAX_RECEIPT_BYTES)
        } catch (error: BackupException) {
            throw BackupException(BackupError.TargetInvalid, "restore receipt is not valid JSON", error)
        }
        root.exactFields(fields, "restoreReceipt")
        val receipt = RestoreReceipt(
            formatVersion = root.int("formatVersion", "restoreReceipt"),
            sourceZipSha256 = root.string("sourceZipSha256", "restoreReceipt"),
            sourceZipBytes = root.long("sourceZipBytes", "restoreReceipt"),
            sourceFormatVersion = root.int("sourceFormatVersion", "restoreReceipt"),
            sourceSchemaVersion = root.int("sourceSchemaVersion", "restoreReceipt"),
            sourceGeneration = root.string("sourceGeneration", "restoreReceipt"),
            restoredGeneration = root.string("restoredGeneration", "restoreReceipt"),
            restoredAt = root.long("restoredAt", "restoreReceipt"),
            contentDigest = root.string("contentDigest", "restoreReceipt"),
            importedRows = root.long("importedRows", "restoreReceipt"),
            businessRevisionAtRestore = root.long("businessRevisionAtRestore", "restoreReceipt"),
        )
        validate(receipt, nowMillis)
        return receipt
    }

    private fun validate(receipt: RestoreReceipt, nowMillis: Long) {
        if (receipt.formatVersion != BackupLimits.FORMAT_VERSION) {
            fail(BackupError.TargetInvalid, "restore receipt formatVersion is not 1")
        }
        if (receipt.sourceFormatVersion != BackupLimits.FORMAT_VERSION) {
            fail(BackupError.TargetInvalid, "restore receipt sourceFormatVersion is not 1")
        }
        if (receipt.sourceSchemaVersion != BackupLimits.DATABASE_SCHEMA_VERSION) {
            fail(BackupError.TargetInvalid, "restore receipt sourceSchemaVersion is not 1")
        }
        requireDigest(receipt.sourceZipSha256, "restoreReceipt.sourceZipSha256")
        requireDigest(receipt.contentDigest, "restoreReceipt.contentDigest")
        if (receipt.sourceZipBytes !in 0..BackupLimits.MAX_ZIP_BYTES) {
            fail(BackupError.TargetInvalid, "restore receipt sourceZipBytes is outside the v1 limit")
        }
        if (receipt.importedRows !in 0..BackupLimits.MAX_ROWS_TOTAL) {
            fail(BackupError.TargetInvalid, "restore receipt importedRows is outside the v1 limit")
        }
        requireIdentity(receipt.sourceGeneration, "restoreReceipt.sourceGeneration")
        requireIdentity(receipt.restoredGeneration, "restoreReceipt.restoredGeneration")
        if (receipt.sourceGeneration == receipt.restoredGeneration) {
            fail(BackupError.TargetInvalid, "restore receipt sourceGeneration equals restoredGeneration")
        }
        requireTimestamp(receipt.restoredAt, "restoreReceipt.restoredAt", nowMillis)
        if (receipt.businessRevisionAtRestore < 0 || receipt.businessRevisionAtRestore == Long.MAX_VALUE) {
            fail(BackupError.TargetInvalid, "restore receipt businessRevisionAtRestore is invalid")
        }
    }
}
