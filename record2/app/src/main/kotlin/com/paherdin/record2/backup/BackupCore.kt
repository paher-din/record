package com.paherdin.record2.backup

internal enum class BackupError {
    Unsupported,
    Busy,
    InvalidSource,
    TargetInvalid,
    StorageFull,
    RecoverableIO,
    Cancelled,
    Internal,
}

internal class BackupException(
    val code: BackupError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class DataOperationBusyException(message: String = "A data operation is in progress") :
    Exception(message)

/** Frozen v1 limits from the approved G0 design. */
internal object BackupLimits {
    const val FORMAT = "record2.logical"
    const val FORMAT_VERSION = 1
    const val DATABASE_SCHEMA_VERSION = 1
    const val ENTRY_COUNT = 13
    const val SEGMENT_COUNT = 12

    const val MAX_ZIP_BYTES = 67_108_864L
    const val MAX_TOTAL_UNCOMPRESSED = 268_435_456L
    const val MAX_ENTRY_UNCOMPRESSED = 134_217_728L
    const val MAX_MANIFEST_BYTES = 1_048_576
    const val MAX_LINE_BYTES = 8_388_608
    const val MAX_ROWS_TOTAL = 500_000L
    const val MAX_COMPRESSION_RATIO = 100L
    const val MAX_JSON_DEPTH = 32
    const val MAX_RECEIPT_BYTES = 4_096
    const val MAX_RECEIVER_IDENTITY_CHARS = 256
    const val MAX_APP_VERSION_NAME_CHARS = 128

    const val MIN_TIMESTAMP_MS = -62_135_596_800_000L
    const val HARD_MAX_TIMESTAMP_MS = 253_402_300_799_999L
    const val MAX_FUTURE_SKEW_MS = 86_400_000L

    const val MAX_EXPORT_WAL_GROWTH_BYTES = 402_653_184L
    const val EXPORT_FREE_RESERVE_BYTES = 67_108_864L
    const val EXPORT_STAGING_RESERVE_BYTES = 134_217_728L
    const val GATE_DRAIN_TIMEOUT_MS = 30_000L
}

internal fun fail(code: BackupError, message: String): Nothing = throw BackupException(code, message)

internal fun canonicalUuid(value: String): Boolean =
    runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)

internal fun lowercaseHex64(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun maxTimestampAt(nowMillis: Long): Long {
    val future = if (nowMillis > Long.MAX_VALUE - BackupLimits.MAX_FUTURE_SKEW_MS) {
        Long.MAX_VALUE
    } else {
        nowMillis + BackupLimits.MAX_FUTURE_SKEW_MS
    }
    return minOf(BackupLimits.HARD_MAX_TIMESTAMP_MS, future)
}

internal fun requireTimestamp(value: Long, field: String, nowMillis: Long) {
    if (value < BackupLimits.MIN_TIMESTAMP_MS || value > maxTimestampAt(nowMillis)) {
        throw BackupException(BackupError.InvalidSource, "$field outside the import time range")
    }
}

internal fun requireVersion(value: Long, field: String) {
    if (value < 1 || value == Long.MAX_VALUE) {
        throw BackupException(BackupError.InvalidSource, "$field must be in 1..Long.MAX_VALUE-1")
    }
}

internal fun requireNonNegativeBelowMax(value: Long, field: String) {
    if (value < 0 || value == Long.MAX_VALUE) {
        throw BackupException(BackupError.InvalidSource, "$field must be in 0..Long.MAX_VALUE-1")
    }
}

internal fun requireIdentity(value: String, field: String) {
    if (!canonicalUuid(value)) {
        throw BackupException(BackupError.InvalidSource, "$field must be a canonical lowercase UUID")
    }
}

internal fun requireDigest(value: String, field: String) {
    if (!lowercaseHex64(value)) {
        throw BackupException(BackupError.InvalidSource, "$field must be 64 lowercase hex characters")
    }
}

internal fun requireNoControl(value: String, field: String, maxCodePoints: Int? = null) {
    if (value.isEmpty()) throw BackupException(BackupError.InvalidSource, "$field must not be empty")
    var count = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (Character.isISOControl(codePoint)) {
            throw BackupException(BackupError.InvalidSource, "$field contains a control character")
        }
        count++
        index += Character.charCount(codePoint)
    }
    if (maxCodePoints != null && count > maxCodePoints) {
        throw BackupException(BackupError.InvalidSource, "$field exceeds $maxCodePoints code points")
    }
}

internal fun requireValidUnicode(value: String, field: String) {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (Character.isHighSurrogate(char)) {
            if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                throw BackupException(BackupError.Internal, "$field contains a lone surrogate")
            }
            index += 2
        } else if (Character.isLowSurrogate(char)) {
            throw BackupException(BackupError.Internal, "$field contains a lone surrogate")
        } else {
            index++
        }
    }
}
