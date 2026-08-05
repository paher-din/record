package com.paherdin.record.core.data

import com.paherdin.record.model.EntityType

/** Internal marker thrown by [XorInvariantValidator]; mapped to [RepositoryResult.Failure.ValidationRejected] at the boundary. */
internal class XorViolationException(val violation: XorViolation) :
    IllegalStateException("XOR invariant violation: ${violation.name}")

/**
 * Pure XOR invariant validator with candidate-occupancy semantics (plan
 * "XOR validator semantics" section): the flags mean the occupancy this write
 * *will produce* — `candidateTransaction = (this request supplies a
 * Transaction row) || (a Transaction row already exists)`, and likewise for
 * `candidateNote`. No database access. The truth table is complete and
 * mutually exclusive; every invalid row throws [XorViolationException].
 */
internal object XorInvariantValidator {
    fun validate(entityType: EntityType, candidateTransaction: Boolean, candidateNote: Boolean) {
        val violation = when {
            entityType == EntityType.PROJECT -> XorViolation.PROJECT_NOT_SUPPORTED
            candidateTransaction && candidateNote -> XorViolation.CROSS_TABLE_ID_COLLISION
            !candidateTransaction && !candidateNote -> XorViolation.MISSING_SUBTYPE
            entityType == EntityType.TRANSACTION && !candidateTransaction ->
                XorViolation.DISCRIMINATOR_MISMATCH
            entityType == EntityType.NOTE && !candidateNote ->
                XorViolation.DISCRIMINATOR_MISMATCH
            else -> null
        }
        if (violation != null) throw XorViolationException(violation)
    }
}
