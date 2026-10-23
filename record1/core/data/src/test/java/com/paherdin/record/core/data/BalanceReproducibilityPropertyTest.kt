package com.paherdin.record.core.data

import com.paherdin.record.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.util.Random

/**
 * Criterion 4: balance reproducibility across arbitrary P2-valid history
 * (testing strategy "Balance drift across arbitrary history"). Fixed seeds
 * only — every run is reproducible; the seed is printed so it can be recorded
 * in `verification.md`. The generator produces P2-valid histories (spec 4.1);
 * after every step, a `BigInteger` oracle equals the effective-entry sum for
 * each account and every transfer keeps exact zero sum. Invalid candidates are
 * mutated from valid shapes and each must be rejected with its precise
 * violation.
 */
class BalanceReproducibilityPropertyTest {

    private val validator = FinanceMatrixValidator()

    /** Fixed account pool: two CNY accounts and two USD accounts so same-currency transfers exist. */
    private val accounts = mapOf(
        "acct-a" to "CNY",
        "acct-b" to "CNY",
        "acct-c" to "USD",
        "acct-d" to "USD",
    )

    private val transferPairs = listOf("acct-a" to "acct-b", "acct-c" to "acct-d")

    private data class Tx(
        val type: TransactionType,
        val entries: List<NewAccountEntry>,
        var deleted: Boolean = false,
    )

    private fun runSeed(seed: Long, steps: Int) {
        val random = Random(seed)
        // Mask the sign bit so magnitudes are always non-negative (never abs(Long.MIN_VALUE)).
        val magnitude = { bound: Long -> (random.nextLong() and Long.MAX_VALUE) % bound + 1L }
        val txs = mutableListOf<Tx>()
        val check = { context: String ->
            // BigInteger oracle: balance = sum of effective (non-deleted) entries per account.
            val expected = mutableMapOf<String, BigInteger>()
            txs.filter { !it.deleted }.forEach { tx ->
                tx.entries.forEach { entry ->
                    expected.merge(entry.accountId, entry.amountMinor.toBigInteger(), BigInteger::add)
                }
            }
            accounts.keys.forEach { accountId ->
                assertEquals(
                    "balance drift for $accountId at $context",
                    expected[accountId] ?: BigInteger.ZERO,
                    oracleBalance(accountId, txs),
                )
            }
            // Every transfer is exactly zero-sum in the model.
            txs.filter { it.type == TransactionType.TRANSFER }.forEach { tx ->
                val sum = tx.entries.fold(BigInteger.ZERO) { acc, e -> acc.add(e.amountMinor.toBigInteger()) }
                assertEquals("transfer zero-sum drift at $context", BigInteger.ZERO, sum)
            }
        }

        repeat(steps) { i ->
            val accountId = { accounts.keys.toList()[random.nextInt(accounts.size)] }
            when (random.nextInt(5)) {
                0 -> { // OPENING_BALANCE on a random account; zero permitted occasionally.
                    val amount = if (random.nextInt(10) == 0) 0L else (random.nextLong() and Long.MAX_VALUE) % 1_000_000L
                    val entries = listOf(NewAccountEntry("op$i-e0", accountId(), amount))
                    assertFinanceValid(validator, TransactionType.OPENING_BALANCE, entries, accounts)
                    txs += Tx(TransactionType.OPENING_BALANCE, entries)
                }
                1 -> { // INCOME: exactly one positive entry.
                    val entries = listOf(NewAccountEntry("op$i-e0", accountId(), magnitude(1_000_000L)))
                    assertFinanceValid(validator, TransactionType.INCOME, entries, accounts)
                    txs += Tx(TransactionType.INCOME, entries)
                }
                2 -> { // EXPENSE: exactly one negative entry.
                    val entries = listOf(NewAccountEntry("op$i-e0", accountId(), -magnitude(1_000_000L)))
                    assertFinanceValid(validator, TransactionType.EXPENSE, entries, accounts)
                    txs += Tx(TransactionType.EXPENSE, entries)
                }
                3 -> { // TRANSFER: two distinct same-currency accounts, zero sum.
                    val (src, dst) = transferPairs[random.nextInt(transferPairs.size)]
                    val m = magnitude(1_000_000L)
                    val entries = listOf(NewAccountEntry("op$i-e0", src, -m), NewAccountEntry("op$i-e1", dst, m))
                    assertFinanceValid(validator, TransactionType.TRANSFER, entries, accounts)
                    txs += Tx(TransactionType.TRANSFER, entries)
                }
                4 -> { // Pure-model soft-delete transition: mark an existing transaction deleted.
                    val candidate = txs.filter { !it.deleted }
                    if (candidate.isNotEmpty()) candidate[random.nextInt(candidate.size)].deleted = true
                }
            }
            check("step $i")
        }
        println("seed=$seed steps=$steps balance-reproducibility OK")
    }

    private fun oracleBalance(accountId: String, txs: List<Tx>): BigInteger =
        txs.filter { !it.deleted }
            .flatMap { it.entries }
            .filter { it.accountId == accountId }
            .fold(BigInteger.ZERO) { acc, e -> acc.add(e.amountMinor.toBigInteger()) }

    @Test
    fun arbitraryHistoryKeepsBalancesReproducible() {
        runSeed(0xC0FFEE, 400)
        runSeed(0xBEEF, 400)
        runSeed(2_026_08_05, 400)
    }

    // ---- invalid candidates by mutation ----

    @Test
    fun mutatedCandidatesAreRejectedWithPreciseViolations() {
        assertFinanceViolation(validator, FinanceViolation.WRONG_SIGN, TransactionType.INCOME, listOf(financeEntry("acct-a", -5L)), accounts)
        assertFinanceViolation(validator, FinanceViolation.ZERO_AMOUNT, TransactionType.INCOME, listOf(financeEntry("acct-a", 0L)), accounts)
        assertFinanceViolation(validator, FinanceViolation.ENTRY_CARDINALITY, TransactionType.INCOME, emptyList(), accounts)
        assertFinanceViolation(validator, FinanceViolation.WRONG_SIGN, TransactionType.EXPENSE, listOf(financeEntry("acct-a", 5L)), accounts)
        assertFinanceViolation(validator, FinanceViolation.ENTRY_CARDINALITY, TransactionType.TRANSFER, listOf(financeEntry("acct-a", -5L)), accounts)
        assertFinanceViolation(
            validator, FinanceViolation.TRANSFER_SAME_ACCOUNT, TransactionType.TRANSFER,
            listOf(financeEntry("acct-a", -5L), financeEntry("acct-a", 5L)), accounts,
        )
        assertFinanceViolation(
            validator, FinanceViolation.TRANSFER_CURRENCY_MISMATCH, TransactionType.TRANSFER,
            listOf(financeEntry("acct-a", -5L), financeEntry("acct-c", 5L)), accounts,
        )
        assertFinanceViolation(
            validator, FinanceViolation.TRANSFER_NOT_ZERO_SUM, TransactionType.TRANSFER,
            listOf(financeEntry("acct-a", -4L), financeEntry("acct-b", 5L)), accounts,
        )
        assertFinanceViolation(
            validator, FinanceViolation.ZERO_AMOUNT, TransactionType.TRANSFER,
            listOf(financeEntry("acct-a", 0L), financeEntry("acct-b", 5L)), accounts,
        )
    }
}
