package com.paherdin.record.core.data

import com.paherdin.record.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Every valid row, every violation, the deterministic classification
 * precedence (isolated and multi-invalid candidates), and the exact-arithmetic
 * boundaries of the finance matrix validator (spec 4.1, plan Task 4).
 */
class FinanceMatrixValidatorTest {

    private val validator = FinanceMatrixValidator()

    private fun entry(accountId: String, amountMinor: Long) =
        NewAccountEntry(id = "e-${accountId}-$amountMinor", accountId = accountId, amountMinor = amountMinor)

    private fun currencies(vararg pairs: Pair<String, String>): Map<String, String> = pairs.toMap()

    private fun assertValid(type: TransactionType, entries: List<NewAccountEntry>, map: Map<String, String>) {
        validator.validate(type, entries, map)
    }

    private fun assertViolation(
        expected: FinanceViolation,
        type: TransactionType,
        entries: List<NewAccountEntry>,
        map: Map<String, String>,
    ) {
        val e = assertThrows(FinanceViolationException::class.java) {
            validator.validate(type, entries, map)
        }
        assertEquals(expected, e.violation)
    }

    // ---- valid rows ----

    @Test
    fun openingBalanceAcceptsAnySingleSignedValue() {
        for (amount in listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertValid(TransactionType.OPENING_BALANCE, listOf(entry("a", amount)), currencies("a" to "CNY"))
        }
    }

    @Test
    fun incomeAcceptsExactlyOnePositiveEntry() {
        assertValid(TransactionType.INCOME, listOf(entry("a", Long.MAX_VALUE)), currencies("a" to "CNY"))
        assertValid(TransactionType.INCOME, listOf(entry("a", 1L)), currencies("a" to "CNY"))
    }

    @Test
    fun expenseAcceptsExactlyOneNegativeEntry() {
        assertValid(TransactionType.EXPENSE, listOf(entry("a", Long.MIN_VALUE)), currencies("a" to "CNY"))
        assertValid(TransactionType.EXPENSE, listOf(entry("a", -1L)), currencies("a" to "CNY"))
    }

    @Test
    fun transferAcceptsOneNegativeAndOnePositiveOnDistinctSameCurrencyAccounts() {
        assertValid(
            TransactionType.TRANSFER,
            listOf(entry("src", -1L), entry("dst", 1L)),
            currencies("src" to "CNY", "dst" to "CNY"),
        )
        assertValid(
            TransactionType.TRANSFER,
            listOf(entry("src", Long.MAX_VALUE), entry("dst", -Long.MAX_VALUE)),
            currencies("src" to "CNY", "dst" to "CNY"),
        )
    }

    @Test
    fun transferInputOrderIsIrrelevantForTheValidator() {
        assertValid(
            TransactionType.TRANSFER,
            listOf(entry("dst", 100L), entry("src", -100L)),
            currencies("src" to "CNY", "dst" to "CNY"),
        )
    }

    // ---- cardinality ----

    @Test
    fun singleEntryTypesRejectWrongCardinality() {
        for (type in listOf(
            TransactionType.OPENING_BALANCE, TransactionType.INCOME, TransactionType.EXPENSE,
        )) {
            assertViolation(
                FinanceViolation.ENTRY_CARDINALITY, type, emptyList(),
                currencies("a" to "CNY"),
            )
            assertViolation(
                FinanceViolation.ENTRY_CARDINALITY, type,
                listOf(entry("a", 1L), entry("b", 1L)),
                currencies("a" to "CNY", "b" to "CNY"),
            )
        }
    }

    @Test
    fun transferRejectsWrongCardinality() {
        assertViolation(
            FinanceViolation.ENTRY_CARDINALITY, TransactionType.TRANSFER,
            listOf(entry("a", -5L)),
            currencies("a" to "CNY"),
        )
        assertViolation(
            FinanceViolation.ENTRY_CARDINALITY, TransactionType.TRANSFER,
            listOf(entry("a", -5L), entry("b", 5L), entry("c", 0L)),
            currencies("a" to "CNY", "b" to "CNY", "c" to "CNY"),
        )
    }

    // ---- zero amount ----

    @Test
    fun incomeAndExpenseRejectZero() {
        assertViolation(
            FinanceViolation.ZERO_AMOUNT, TransactionType.INCOME, listOf(entry("a", 0L)),
            currencies("a" to "CNY"),
        )
        assertViolation(
            FinanceViolation.ZERO_AMOUNT, TransactionType.EXPENSE, listOf(entry("a", 0L)),
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun transferRejectsZeroEntries() {
        assertViolation(
            FinanceViolation.ZERO_AMOUNT, TransactionType.TRANSFER,
            listOf(entry("a", 0L), entry("b", 5L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
        assertViolation(
            FinanceViolation.ZERO_AMOUNT, TransactionType.TRANSFER,
            listOf(entry("a", 0L), entry("b", 0L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    // ---- sign ----

    @Test
    fun incomeRejectsNegative() {
        assertViolation(
            FinanceViolation.WRONG_SIGN, TransactionType.INCOME, listOf(entry("a", -1L)),
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun expenseRejectsPositive() {
        assertViolation(
            FinanceViolation.WRONG_SIGN, TransactionType.EXPENSE, listOf(entry("a", 1L)),
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun transferRejectsBothPositiveAndBothNegative() {
        assertViolation(
            FinanceViolation.WRONG_SIGN, TransactionType.TRANSFER,
            listOf(entry("a", 5L), entry("b", 5L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
        assertViolation(
            FinanceViolation.WRONG_SIGN, TransactionType.TRANSFER,
            listOf(entry("a", Long.MIN_VALUE), entry("b", Long.MIN_VALUE)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    // ---- transfer extras ----

    @Test
    fun transferRejectsSameAccount() {
        assertViolation(
            FinanceViolation.TRANSFER_SAME_ACCOUNT, TransactionType.TRANSFER,
            listOf(entry("a", -5L), entry("a", 5L)),
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun transferRejectsCurrencyMismatch() {
        assertViolation(
            FinanceViolation.TRANSFER_CURRENCY_MISMATCH, TransactionType.TRANSFER,
            listOf(entry("a", -5L), entry("b", 5L)),
            currencies("a" to "CNY", "b" to "USD"),
        )
    }

    @Test
    fun transferRejectsNonZeroSum() {
        assertViolation(
            FinanceViolation.TRANSFER_NOT_ZERO_SUM, TransactionType.TRANSFER,
            listOf(entry("a", -4L), entry("b", 5L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    // ---- deterministic precedence with multi-invalid candidates ----

    @Test
    fun precedenceZeroBeforeSignAndTransferExtras() {
        assertViolation(
            FinanceViolation.ZERO_AMOUNT, TransactionType.TRANSFER,
            listOf(entry("a", 0L), entry("a", 5L)), // zero + same-account
            currencies("a" to "CNY"),
        )
        assertViolation(
            FinanceViolation.ZERO_AMOUNT, TransactionType.TRANSFER,
            listOf(entry("a", 0L), entry("b", 5L)), // zero + currency mismatch
            currencies("a" to "CNY", "b" to "USD"),
        )
    }

    @Test
    fun precedenceSignBeforeTransferExtras() {
        assertViolation(
            FinanceViolation.WRONG_SIGN, TransactionType.TRANSFER,
            listOf(entry("a", 5L), entry("a", 5L)), // both positive + same-account
            currencies("a" to "CNY"),
        )
        assertViolation(
            FinanceViolation.WRONG_SIGN, TransactionType.TRANSFER,
            listOf(entry("a", 5L), entry("b", 5L)), // both positive + non-zero-sum
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    @Test
    fun precedenceSameAccountBeforeCurrencyAndZeroSum() {
        assertViolation(
            FinanceViolation.TRANSFER_SAME_ACCOUNT, TransactionType.TRANSFER,
            listOf(entry("a", -4L), entry("a", 5L)), // same-account + non-zero-sum
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun precedenceCurrencyBeforeZeroSum() {
        assertViolation(
            FinanceViolation.TRANSFER_CURRENCY_MISMATCH, TransactionType.TRANSFER,
            listOf(entry("a", -4L), entry("b", 5L)), // currency mismatch + non-zero-sum
            currencies("a" to "CNY", "b" to "USD"),
        )
    }

    @Test
    fun precedenceCardinalityBeatsEverything() {
        assertViolation(
            FinanceViolation.ENTRY_CARDINALITY, TransactionType.TRANSFER,
            listOf(entry("a", -5L), entry("b", 5L), entry("c", 0L)), // 3 entries, otherwise valid
            currencies("a" to "CNY", "b" to "CNY", "c" to "CNY"),
        )
    }

    // ---- exact-arithmetic boundaries ----

    @Test
    fun signCorrectTransferContainingMinValueIsRejectedAsNonZeroSumNeverByWraparound() {
        // Long.MIN_VALUE has no positive counterpart in Long: (-2^63) + x <= -1 for x <= MAX.
        assertViolation(
            FinanceViolation.TRANSFER_NOT_ZERO_SUM, TransactionType.TRANSFER,
            listOf(entry("a", Long.MIN_VALUE), entry("b", Long.MAX_VALUE)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
        assertViolation(
            FinanceViolation.TRANSFER_NOT_ZERO_SUM, TransactionType.TRANSFER,
            listOf(entry("a", Long.MIN_VALUE), entry("b", 5L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    @Test
    fun oneMinorUnitBoundaries() {
        assertValid(
            TransactionType.TRANSFER,
            listOf(entry("a", -1L), entry("b", 1L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }
}
