package com.paherdin.record.core.data

import com.paherdin.record.model.TransactionType

/** Carries the first finance violation found by the matrix validator. */
internal class FinanceViolationException(val violation: FinanceViolation) : Exception()

/**
 * Pure entry-side finance matrix validator (spec 4.1, plan "Finance validator
 * semantics"). Throws `FinanceViolationException` on the first violation in
 * deterministic precedence order: cardinality → zero amount → wrong sign →
 * same-account transfer → currency mismatch → non-zero-sum. No database
 * access; the repository resolves every entry `accountId` against existing
 * `Account`s and passes the stored currency strings before calling this.
 * Exact arithmetic only: direct `Long` comparisons, no `abs(Long.MIN_VALUE)`,
 * and the zero-sum check widens to `BigInteger` so wraparound can never
 * masquerade as a valid result.
 */
internal class FinanceMatrixValidator {

    fun validate(
        type: TransactionType,
        entries: List<NewAccountEntry>,
        currencyByAccountId: Map<String, String>,
    ) {
        require(entries.all { it.accountId in currencyByAccountId }) {
            "every entry accountId must be resolved by the repository before finance validation"
        }

        // Precedence 1: cardinality.
        val expected = when (type) {
            TransactionType.OPENING_BALANCE, TransactionType.INCOME, TransactionType.EXPENSE -> 1
            TransactionType.TRANSFER -> 2
        }
        if (entries.size != expected) throw FinanceViolationException(FinanceViolation.ENTRY_CARDINALITY)

        // Precedence 2: zero amount (income/expense/transfer only; OPENING_BALANCE permits zero).
        if (type != TransactionType.OPENING_BALANCE && entries.any { it.amountMinor == 0L }) {
            throw FinanceViolationException(FinanceViolation.ZERO_AMOUNT)
        }

        // Precedence 3: sign.
        when (type) {
            TransactionType.INCOME -> if (entries[0].amountMinor < 0L) {
                throw FinanceViolationException(FinanceViolation.WRONG_SIGN)
            }
            TransactionType.EXPENSE -> if (entries[0].amountMinor > 0L) {
                throw FinanceViolationException(FinanceViolation.WRONG_SIGN)
            }
            TransactionType.TRANSFER -> {
                val negatives = entries.count { it.amountMinor < 0L }
                val positives = entries.count { it.amountMinor > 0L }
                if (negatives != 1 || positives != 1) {
                    throw FinanceViolationException(FinanceViolation.WRONG_SIGN)
                }
            }
            TransactionType.OPENING_BALANCE -> Unit
        }

        if (type != TransactionType.TRANSFER) return

        // Precedence 4: distinct accounts.
        if (entries[0].accountId == entries[1].accountId) {
            throw FinanceViolationException(FinanceViolation.TRANSFER_SAME_ACCOUNT)
        }

        // Precedence 5: same stored currency string.
        if (currencyByAccountId[entries[0].accountId] != currencyByAccountId[entries[1].accountId]) {
            throw FinanceViolationException(FinanceViolation.TRANSFER_CURRENCY_MISMATCH)
        }

        // Precedence 6: exact zero sum. Widened so (MIN, MAX) etc. never wrap.
        val sum = entries[0].amountMinor.toBigInteger().add(entries[1].amountMinor.toBigInteger())
        if (sum.signum() != 0) throw FinanceViolationException(FinanceViolation.TRANSFER_NOT_ZERO_SUM)
    }
}
