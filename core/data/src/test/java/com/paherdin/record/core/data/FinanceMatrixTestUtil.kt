package com.paherdin.record.core.data

import com.paherdin.record.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

/**
 * Shared JVM test helpers for the finance matrix validator, used by
 * `FinanceMatrixValidatorTest` and `BalanceReproducibilityPropertyTest`.
 */
internal fun financeEntry(accountId: String, amountMinor: Long) =
    NewAccountEntry(id = "e-$accountId-$amountMinor", accountId = accountId, amountMinor = amountMinor)

internal fun assertFinanceValid(
    validator: FinanceMatrixValidator,
    type: TransactionType,
    entries: List<NewAccountEntry>,
    currencyByAccountId: Map<String, String>,
) {
    validator.validate(type, entries, currencyByAccountId)
}

internal fun assertFinanceViolation(
    validator: FinanceMatrixValidator,
    expected: FinanceViolation,
    type: TransactionType,
    entries: List<NewAccountEntry>,
    currencyByAccountId: Map<String, String>,
) {
    val e = assertThrows(FinanceViolationException::class.java) {
        validator.validate(type, entries, currencyByAccountId)
    }
    assertEquals(expected, e.violation)
}
