package com.paherdin.record.core.data

/**
 * Typed outcome of every public repository operation. `CancellationException`
 * is always rethrown and never converted into an outcome; raw Room/SQLite
 * exceptions never cross the boundary.
 */
sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>

    sealed interface Failure : RepositoryResult<Nothing> {
        data class ValidationRejected(val violation: XorViolation) : Failure
        data class FinanceRejected(val violation: FinanceViolation) : Failure
        data class AttachmentRejected(val violation: AttachmentViolation) : Failure
        data class AttachmentCorrupted(val detail: CorruptionDetail) : Failure
        data class AttachmentIoFailed(val stage: AttachmentStage) : Failure
        data class BackupRejected(val violation: BackupViolation) : Failure
        data class BackupIoFailed(val stage: BackupStage) : Failure
        data class RestoreRejected(val violation: RestoreViolation) : Failure
        data class RestoreIoFailed(val stage: RestoreStage) : Failure
        data object DuplicateIdentity : Failure
        data object EndpointRejected : Failure
        data object StorageExhausted : Failure
        data object TransactionFailed : Failure
        data object CommittedButResponseFailed : Failure
        data object Unexpected : Failure
    }
}

/** The backup failures of the export surface (spec 4.8, plan Task 4). */
enum class BackupViolation { PACKAGE_VALIDATION_FAILED }

/** The named file-operation stage of a `BackupIoFailed` outcome (plan Task 4). */
enum class BackupStage {
    CONSISTENT_READ,
    STREAM_JSONL,
    STREAM_ATTACHMENT,
    MANIFEST_WRITE,
    PRIVATE_VALIDATE,
    PRIVATE_RENAME,
    PROVIDER_CREATE,
    PROVIDER_COPY_CHUNK,
    PROVIDER_FLUSH_CLOSE,
    PROVIDER_VERIFY,
    PROVIDER_RENAME,
    PROVIDER_MARKER_CLEAR,
}

/** The named stage of a `RestoreIoFailed` outcome (plan Task 6). */
enum class RestoreStage {
    PREFLIGHT_DECLARED,
    PREFLIGHT_STREAMING,
    STAGING_IMPORT,
    STAGING_VALIDATE,
    ACTIVATION_SWITCH,
    STARTUP_REVALIDATE,
    ROLLBACK,
    RETIRE_DELETE,
    RETIRE_POINTER_CLEAR,
    ORPHAN_SCAN,
}

/** The total, first-match deterministic restore rejection classification (spec 4.3, plan). */
enum class RestoreViolation {
    FORMAT_UNSUPPORTED,
    MANIFEST_INVALID,
    RECORD_INVALID,
    UNKNOWN_LOGICAL_TYPE,
    SINGLETON_DUPLICATE,
    NAME_KEY_VERSION_UNSUPPORTED,
    CATALOG_INVALID,
    PATH_INVALID,
    PATH_TOO_LONG,
    PATH_DUPLICATE,
    ENTRY_COUNT_LIMIT,
    ENTRY_SIZE_LIMIT,
    AGGREGATE_SIZE_LIMIT,
    SPACE_BUDGET,
    COMPRESSION_RATIO_LIMIT,
    SIZE_UNDER_DECLARED,
    SHA256_MISMATCH,
    RECORD_COUNT_MISMATCH,
    ATTACHMENT_DUPLICATE_IDENTITY,
    ATTACHMENT_LINK_DANGLING,
    ATTACHMENT_MISSING,
    ATTACHMENT_EXTRA,
    ATTACHMENT_CORRUPT,
    FOREIGN_KEY_INVALID,
    XOR_INVALID,
    FINANCE_INVALID,
    BALANCE_INVALID,
    ENDPOINT_INVALID,
    CANDIDATE_MISMATCH,
    MAINTENANCE_PENDING,
}

/**
 * The XOR invariant violations the validator can report (spec 5.1, ADR 0006).
 */
enum class XorViolation {
    DISCRIMINATOR_MISMATCH,
    MISSING_SUBTYPE,
    PROJECT_NOT_SUPPORTED,
    CROSS_TABLE_ID_COLLISION,
}

/**
 * The attachment violations the pipeline can report (spec 4.7, plan Task 2).
 * Member order is the deterministic classification precedence for
 * multi-invalid candidates. `CONTENT_IDENTITY_UNKNOWN` and
 * `CONTENT_NOT_AVAILABLE` are reported by the repository/link paths, not by
 * the pure validator.
 */
enum class AttachmentViolation {
    UNDECODABLE,
    SIZE_LIMIT_EXCEEDED,
    MIME_TYPE_NOT_ACCEPTED,
    CONTENT_IDENTITY_UNKNOWN,
    CONTENT_NOT_AVAILABLE,
}

/**
 * The corruption details of the attachment pipeline (spec 4.7, ADR 0008):
 * an unexpected metadata/blob split, a hash or size mismatch, or conflicting
 * metadata. A zero-link pending row whose blob is absent is the *expected*
 * cleanup crash window and is classified separately — never this detail.
 */
enum class CorruptionDetail {
    METADATA_BLOB_SPLIT,
    HASH_MISMATCH,
    SIZE_MISMATCH,
    CONFLICTING_METADATA,
}

/**
 * The named file-operation stage of an `AttachmentIoFailed` outcome
 * (spec 4.7, plan Task 2).
 */
enum class AttachmentStage {
    TEMP_COPY,
    VALIDATION_HASH,
    ATOMIC_MOVE,
    METADATA_WRITE,
    PHYSICAL_DELETE,
    METADATA_DELETE,
    ORPHAN_CLEANUP,
}

/**
 * The finance-matrix violations the validator can report (spec 4.1/4.3).
 * Member order is the deterministic classification precedence for
 * multi-invalid candidates.
 */
enum class FinanceViolation {
    ENTRY_CARDINALITY,
    ZERO_AMOUNT,
    WRONG_SIGN,
    TRANSFER_SAME_ACCOUNT,
    TRANSFER_CURRENCY_MISMATCH,
    TRANSFER_NOT_ZERO_SUM,
}
