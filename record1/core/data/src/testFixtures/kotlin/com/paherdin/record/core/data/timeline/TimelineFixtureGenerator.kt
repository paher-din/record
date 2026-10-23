package com.paherdin.record.core.data.timeline

import java.lang.Math.floorMod
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Tech PoC P6 — `timeline-v1`: the frozen 10,000-card mixed transaction/note
 * day timeline fixture, per plan Task 2. Deterministic by construction; the
 * seed, ZoneId, slot/offset tables, selector, labels and amounts are frozen
 * and generation never reads the device default zone, never substitutes
 * `d * 86_400_000` for local-date construction, and uses no randomness.
 */
object TimelineFixtureGenerator {

    const val seed = 2026081606L
    const val fixtureVersion = "timeline-v1"
    const val zoneId = "Asia/Shanghai"
    const val cardCount = 10_000
    const val dayCount = 1_000
    const val cardsPerDay = 10
    const val transactionCount = 6_000
    const val noteCount = 4_000

    const val OPENING_BALANCE_COUNT = 10
    const val EXPENSE_COUNT = 3_000
    const val INCOME_COUNT = 1_800
    const val TRANSFER_COUNT = 1_190

    val zone: ZoneId = ZoneId.of(zoneId)

    val transactionSlots: List<Int> = listOf(0, 2, 4, 5, 7, 9)
    val noteSlots: List<Int> = listOf(1, 3, 6, 8)
    val slotOffsetsMs: List<Long> = listOf(
        0L, 0L, 21_600_000L, 32_400_000L, 43_200_000L, 54_000_000L,
        43_200_000L, 64_800_000L, 75_600_000L, 86_399_999L,
    )

    val accounts: List<String> = listOf("现金", "招商银行", "支付宝", "微信")
    val expenseCategories: List<String> = listOf("餐饮", "交通", "购物", "居住")
    val incomeCategories: List<String> = listOf("工资", "奖金", "退款")

    private const val DAY_BASE_YEAR = 2023
    private const val DAY_BASE_MONTH = 1
    private const val DAY_BASE_DAY = 1

    private const val SALT_SOURCE_ACCOUNT = 1
    private const val SALT_EXPENSE_CATEGORY = 2
    private const val SALT_INCOME_CATEGORY = 3
    private const val SALT_TRANSFER_DESTINATION = 4
    private const val SALT_OPENING_AMOUNT = 11
    private const val SALT_EXPENSE_AMOUNT = 12
    private const val SALT_INCOME_AMOUNT = 13
    private const val SALT_TRANSFER_AMOUNT = 14

    /** Deterministic selector: floorMod(seed + 1_000_003*i + 97_409*salt, size). */
    fun pick(i: Int, salt: Int, size: Int): Int =
        floorMod(seed + 1_000_003L * i + 97_409L * salt, size.toLong()).toInt()

    /** Local-midnight epoch of local day d in Asia/Shanghai; never the device default zone. */
    fun dayStart(d: Int): Long = LocalDate.of(DAY_BASE_YEAR, DAY_BASE_MONTH, DAY_BASE_DAY)
        .plusDays(d.toLong())
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    fun isTransactionSlot(s: Int): Boolean = s in transactionSlots

    /** Position of a transaction slot within {0,2,4,5,7,9}; requires a transaction slot. */
    fun transactionSlotPosition(s: Int): Int = transactionSlots.indexOf(s).also {
        require(it >= 0) { "not a transaction slot: $s" }
    }

    /** Chronological ordinal over all 6,000 transactions (ascending occurredAt). */
    fun chronologicalTransactionOrdinal(i: Int): Int {
        val d = i / cardsPerDay
        val s = i % cardsPerDay
        return d * transactionSlots.size + transactionSlotPosition(s)
    }

    /** Transaction kind by the frozen ordinal ranges; null for a Note slot. */
    fun transactionKind(i: Int): TransactionKind? {
        if (!isTransactionSlot(i % cardsPerDay)) return null
        return when (chronologicalTransactionOrdinal(i)) {
            in 0 until OPENING_BALANCE_COUNT -> TransactionKind.OPENING_BALANCE
            in OPENING_BALANCE_COUNT until OPENING_BALANCE_COUNT + EXPENSE_COUNT -> TransactionKind.EXPENSE
            in OPENING_BALANCE_COUNT + EXPENSE_COUNT until OPENING_BALANCE_COUNT + EXPENSE_COUNT + INCOME_COUNT ->
                TransactionKind.INCOME
            else -> TransactionKind.TRANSFER
        }
    }

    /** The 10,000 records in generator index order `i = d * 10 + s`. */
    fun generate(): List<TimelineFixtureRecord> = (0 until cardCount).map(::card)

    private fun card(i: Int): TimelineFixtureRecord {
        val d = i / cardsPerDay
        val s = i % cardsPerDay
        val id = UUID.nameUUIDFromBytes(
            "timeline-v1:2026081606:$i".toByteArray(Charsets.UTF_8),
        ).toString()
        val occurredAt = dayStart(d) + slotOffsetsMs[s]
        return if (isTransactionSlot(s)) transactionCard(i, id, occurredAt)
        else noteCard(i, d, s, id, occurredAt)
    }

    private fun transactionCard(i: Int, id: String, occurredAt: Long): TimelineFixtureRecord {
        val kind = transactionKind(i)!!
        val sourceIndex = pick(i, SALT_SOURCE_ACCOUNT, accounts.size)
        val source = accounts[sourceIndex]
        val (title, amountMinor) = when (kind) {
            TransactionKind.OPENING_BALANCE ->
                "$source · 期初余额" to (100_000L + pick(i, SALT_OPENING_AMOUNT, 900_001))
            TransactionKind.EXPENSE ->
                "$source · ${expenseCategories[pick(i, SALT_EXPENSE_CATEGORY, expenseCategories.size)]}" to
                    -(100L + pick(i, SALT_EXPENSE_AMOUNT, 49_901))
            TransactionKind.INCOME ->
                "$source · ${incomeCategories[pick(i, SALT_INCOME_CATEGORY, incomeCategories.size)]}" to
                    (100_000L + pick(i, SALT_INCOME_AMOUNT, 900_001))
            TransactionKind.TRANSFER -> {
                val destinationIndex =
                    (sourceIndex + 1 + pick(i, SALT_TRANSFER_DESTINATION, accounts.size - 1)) % accounts.size
                "$source → ${accounts[destinationIndex]}" to
                    -(1_000L + pick(i, SALT_TRANSFER_AMOUNT, 199_001))
            }
        }
        return TimelineFixtureRecord(id, "TRANSACTION", occurredAt, title, amountMinor, body = null)
    }

    private fun noteCard(i: Int, d: Int, s: Int, id: String, occurredAt: Long): TimelineFixtureRecord {
        val body = "# 记录 $i\n第 $d 天，第 $s 槽。"
        val title = body.lineSequence().first { it.isNotBlank() }.removePrefix("# ").trim()
        return TimelineFixtureRecord(id, "NOTE", occurredAt, title, amountMinor = null, body = body)
    }

    /** Canonical C record: `C` + tab-separated length-prefixed fields; amountMinor absent = `NULL`. */
    fun encodeC(record: TimelineFixtureRecord): String {
        val fields = listOf(
            encodeField(record.id),
            encodeField(record.type),
            encodeField(record.occurredAt.toString()),
            encodeField(record.title),
            if (record.amountMinor == null) NULL_AMOUNT else encodeField(record.amountMinor.toString()),
            if (record.body == null) NULL_STRING else encodeField(record.body),
        )
        return "C\t" + fields.joinToString("\t")
    }

    private const val NULL_AMOUNT = "NULL"
    private const val NULL_STRING = "-1:"

    private fun encodeField(value: String): String =
        "${value.toByteArray(Charsets.UTF_8).size}:$value"

    /**
     * Canonical fixture stream: `timeline-v1\n2026081606\nAsia/Shanghai\n`
     * header lines then one LF-terminated C record per card in index order,
     * UTF-8, LF only.
     */
    fun encodeFixture(): ByteArray {
        val out = StringBuilder()
        out.append(fixtureVersion).append('\n')
        out.append(seed).append('\n')
        out.append(zoneId).append('\n')
        for (record in generate()) {
            out.append(encodeC(record)).append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }
}

enum class TransactionKind { OPENING_BALANCE, EXPENSE, INCOME, TRANSFER }

data class TimelineFixtureRecord(
    val id: String,
    val type: String,
    val occurredAt: Long,
    val title: String,
    val amountMinor: Long?,
    val body: String?,
)
