package com.paherdin.record.core.data

import com.paherdin.record.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Every row of the complete, mutually exclusive candidate-occupancy truth
 * table (plan "XOR validator semantics" section).
 */
class XorInvariantValidatorTest {

    private fun assertViolation(
        expected: XorViolation,
        entityType: EntityType,
        candidateTransaction: Boolean,
        candidateNote: Boolean,
    ) {
        val e = assertThrows(XorViolationException::class.java) {
            XorInvariantValidator.validate(entityType, candidateTransaction, candidateNote)
        }
        assertEquals(expected, e.violation)
    }

    @Test
    fun transactionWithTransactionCandidateIsValid() {
        XorInvariantValidator.validate(EntityType.TRANSACTION, true, false)
    }

    @Test
    fun noteWithNoteCandidateIsValid() {
        XorInvariantValidator.validate(EntityType.NOTE, false, true)
    }

    @Test
    fun transactionDiscriminatorMismatch() {
        assertViolation(XorViolation.DISCRIMINATOR_MISMATCH, EntityType.TRANSACTION, false, true)
    }

    @Test
    fun noteDiscriminatorMismatch() {
        assertViolation(XorViolation.DISCRIMINATOR_MISMATCH, EntityType.NOTE, true, false)
    }

    @Test
    fun missingSubtype() {
        assertViolation(XorViolation.MISSING_SUBTYPE, EntityType.TRANSACTION, false, false)
        assertViolation(XorViolation.MISSING_SUBTYPE, EntityType.NOTE, false, false)
    }

    @Test
    fun crossTableIdCollision() {
        assertViolation(XorViolation.CROSS_TABLE_ID_COLLISION, EntityType.TRANSACTION, true, true)
        assertViolation(XorViolation.CROSS_TABLE_ID_COLLISION, EntityType.NOTE, true, true)
    }

    @Test
    fun projectNotSupportedForAllFlagCombinations() {
        for (transaction in listOf(true, false)) {
            for (note in listOf(true, false)) {
                assertViolation(
                    XorViolation.PROJECT_NOT_SUPPORTED,
                    EntityType.PROJECT,
                    transaction,
                    note,
                )
            }
        }
    }
}
