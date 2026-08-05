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
        data object DuplicateIdentity : Failure
        data object EndpointRejected : Failure
        data object StorageExhausted : Failure
        data object TransactionFailed : Failure
        data object CommittedButResponseFailed : Failure
        data object Unexpected : Failure
    }
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
