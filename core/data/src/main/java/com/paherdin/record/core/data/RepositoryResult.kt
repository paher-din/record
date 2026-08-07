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
