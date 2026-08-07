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

    private fun currencies(vararg pairs: Pair<String, String>): Map<String, String> = pairs.toMap()

    // ---- valid rows ----

    @Test
    fun openingBalanceAcceptsAnySingleSignedValue() {
        for (amount in listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertFinanceValid(validator, TransactionType.OPENING_BALANCE, listOf(financeEntry("a", amount)), currencies("a" to "CNY"))
        }
    }

    @Test
    fun incomeAcceptsExactlyOnePositiveEntry() {
        assertFinanceValid(validator, TransactionType.INCOME, listOf(financeEntry("a", Long.MAX_VALUE)), currencies("a" to "CNY"))
        assertFinanceValid(validator, TransactionType.INCOME, listOf(financeEntry("a", 1L)), currencies("a" to "CNY"))
    }

    @Test
    fun expenseAcceptsExactlyOneNegativeEntry() {
        assertFinanceValid(validator, TransactionType.EXPENSE, listOf(financeEntry("a", Long.MIN_VALUE)), currencies("a" to "CNY"))
        assertFinanceValid(validator, TransactionType.EXPENSE, listOf(financeEntry("a", -1L)), currencies("a" to "CNY"))
    }

    @Test
    fun transferAcceptsOneNegativeAndOnePositiveOnDistinctSameCurrencyAccounts() {
        assertFinanceValid(validator,
            TransactionType.TRANSFER,
            listOf(financeEntry("src", -1L), financeEntry("dst", 1L)),
            currencies("src" to "CNY", "dst" to "CNY"),
        )
        assertFinanceValid(validator,
            TransactionType.TRANSFER,
            listOf(financeEntry("src", Long.MAX_VALUE), financeEntry("dst", -Long.MAX_VALUE)),
            currencies("src" to "CNY", "dst" to "CNY"),
        )
    }

    @Test
    fun transferInputOrderIsIrrelevantForTheValidator() {
        assertFinanceValid(validator,
            TransactionType.TRANSFER,
            listOf(financeEntry("dst", 100L), financeEntry("src", -100L)),
            currencies("src" to "CNY", "dst" to "CNY"),
        )
    }

    // ---- cardinality ----

    @Test
    fun singleEntryTypesRejectWrongCardinality() {
        for (type in listOf(
            TransactionType.OPENING_BALANCE, TransactionType.INCOME, TransactionType.EXPENSE,
        )) {
            assertFinanceViolation(validator,
                FinanceViolation.ENTRY_CARDINALITY, type, emptyList(),
                currencies("a" to "CNY"),
            )
            assertFinanceViolation(validator,
                FinanceViolation.ENTRY_CARDINALITY, type,
                listOf(financeEntry("a", 1L), financeEntry("b", 1L)),
                currencies("a" to "CNY", "b" to "CNY"),
            )
        }
    }

    @Test
    fun transferRejectsWrongCardinality() {
        assertFinanceViolation(validator,
            FinanceViolation.ENTRY_CARDINALITY, TransactionType.TRANSFER,
            listOf(financeEntry("a", -5L)),
            currencies("a" to "CNY"),
        )
        assertFinanceViolation(validator,
            FinanceViolation.ENTRY_CARDINALITY, TransactionType.TRANSFER,
            listOf(financeEntry("a", -5L), financeEntry("b", 5L), financeEntry("c", 0L)),
            currencies("a" to "CNY", "b" to "CNY", "c" to "CNY"),
        )
    }

    // ---- zero amount ----

    @Test
    fun incomeAndExpenseRejectZero() {
        assertFinanceViolation(validator,
            FinanceViolation.ZERO_AMOUNT, TransactionType.INCOME, listOf(financeEntry("a", 0L)),
            currencies("a" to "CNY"),
        )
        assertFinanceViolation(validator,
            FinanceViolation.ZERO_AMOUNT, TransactionType.EXPENSE, listOf(financeEntry("a", 0L)),
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun transferRejectsZeroEntries() {
        assertFinanceViolation(validator,
            FinanceViolation.ZERO_AMOUNT, TransactionType.TRANSFER,
            listOf(financeEntry("a", 0L), financeEntry("b", 5L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
        assertFinanceViolation(validator,
            FinanceViolation.ZERO_AMOUNT, TransactionType.TRANSFER,
            listOf(financeEntry("a", 0L), financeEntry("b", 0L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    // ---- sign ----

    @Test
    fun incomeRejectsNegative() {
        assertFinanceViolation(validator,
            FinanceViolation.WRONG_SIGN, TransactionType.INCOME, listOf(financeEntry("a", -1L)),
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun expenseRejectsPositive() {
        assertFinanceViolation(validator,
            FinanceViolation.WRONG_SIGN, TransactionType.EXPENSE, listOf(financeEntry("a", 1L)),
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun transferRejectsBothPositiveAndBothNegative() {
        assertFinanceViolation(validator,
            FinanceViolation.WRONG_SIGN, TransactionType.TRANSFER,
            listOf(financeEntry("a", 5L), financeEntry("b", 5L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
        assertFinanceViolation(validator,
            FinanceViolation.WRONG_SIGN, TransactionType.TRANSFER,
            listOf(financeEntry("a", Long.MIN_VALUE), financeEntry("b", Long.MIN_VALUE)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    // ---- transfer extras ----

    @Test
    fun transferRejectsSameAccount() {
        assertFinanceViolation(validator,
            FinanceViolation.TRANSFER_SAME_ACCOUNT, TransactionType.TRANSFER,
            listOf(financeEntry("a", -5L), financeEntry("a", 5L)),
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun transferRejectsCurrencyMismatch() {
        assertFinanceViolation(validator,
            FinanceViolation.TRANSFER_CURRENCY_MISMATCH, TransactionType.TRANSFER,
            listOf(financeEntry("a", -5L), financeEntry("b", 5L)),
            currencies("a" to "CNY", "b" to "USD"),
        )
    }

    @Test
    fun transferRejectsNonZeroSum() {
        assertFinanceViolation(validator,
            FinanceViolation.TRANSFER_NOT_ZERO_SUM, TransactionType.TRANSFER,
            listOf(financeEntry("a", -4L), financeEntry("b", 5L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    // ---- deterministic precedence with multi-invalid candidates ----

    @Test
    fun precedenceZeroBeforeSignAndTransferExtras() {
        assertFinanceViolation(validator,
            FinanceViolation.ZERO_AMOUNT, TransactionType.TRANSFER,
            listOf(financeEntry("a", 0L), financeEntry("a", 5L)), // zero + same-account
            currencies("a" to "CNY"),
        )
        assertFinanceViolation(validator,
            FinanceViolation.ZERO_AMOUNT, TransactionType.TRANSFER,
            listOf(financeEntry("a", 0L), financeEntry("b", 5L)), // zero + currency mismatch
            currencies("a" to "CNY", "b" to "USD"),
        )
    }

    @Test
    fun precedenceSignBeforeTransferExtras() {
        assertFinanceViolation(validator,
            FinanceViolation.WRONG_SIGN, TransactionType.TRANSFER,
            listOf(financeEntry("a", 5L), financeEntry("a", 5L)), // both positive + same-account
            currencies("a" to "CNY"),
        )
        assertFinanceViolation(validator,
            FinanceViolation.WRONG_SIGN, TransactionType.TRANSFER,
            listOf(financeEntry("a", 5L), financeEntry("b", 5L)), // both positive + non-zero-sum
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    @Test
    fun precedenceSameAccountBeforeCurrencyAndZeroSum() {
        assertFinanceViolation(validator,
            FinanceViolation.TRANSFER_SAME_ACCOUNT, TransactionType.TRANSFER,
            listOf(financeEntry("a", -4L), financeEntry("a", 5L)), // same-account + non-zero-sum
            currencies("a" to "CNY"),
        )
    }

    @Test
    fun precedenceCurrencyBeforeZeroSum() {
        assertFinanceViolation(validator,
            FinanceViolation.TRANSFER_CURRENCY_MISMATCH, TransactionType.TRANSFER,
            listOf(financeEntry("a", -4L), financeEntry("b", 5L)), // currency mismatch + non-zero-sum
            currencies("a" to "CNY", "b" to "USD"),
        )
    }

    @Test
    fun precedenceCardinalityBeatsEverything() {
        assertFinanceViolation(validator,
            FinanceViolation.ENTRY_CARDINALITY, TransactionType.TRANSFER,
            listOf(financeEntry("a", -5L), financeEntry("b", 5L), financeEntry("c", 0L)), // 3 entries, otherwise valid
            currencies("a" to "CNY", "b" to "CNY", "c" to "CNY"),
        )
    }

    // ---- exact-arithmetic boundaries ----

    @Test
    fun signCorrectTransferContainingMinValueIsRejectedAsNonZeroSumNeverByWraparound() {
        // Long.MIN_VALUE has no positive counterpart in Long: (-2^63) + x <= -1 for x <= MAX.
        assertFinanceViolation(validator,
            FinanceViolation.TRANSFER_NOT_ZERO_SUM, TransactionType.TRANSFER,
            listOf(financeEntry("a", Long.MIN_VALUE), financeEntry("b", Long.MAX_VALUE)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
        assertFinanceViolation(validator,
            FinanceViolation.TRANSFER_NOT_ZERO_SUM, TransactionType.TRANSFER,
            listOf(financeEntry("a", Long.MIN_VALUE), financeEntry("b", 5L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }

    @Test
    fun oneMinorUnitBoundaries() {
        assertFinanceValid(validator,
            TransactionType.TRANSFER,
            listOf(financeEntry("a", -1L), financeEntry("b", 1L)),
            currencies("a" to "CNY", "b" to "CNY"),
        )
    }
}
