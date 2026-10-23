package com.paherdin.record2.records

import java.math.BigDecimal
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test
    fun exactKnownAmounts() {
        mapOf("58" to 5800L, "58.00" to 5800L, "60.5" to 6050L,
            "0.01" to 1L, "0001.20" to 120L, "92233720368547758.07" to Long.MAX_VALUE)
            .forEach { (text, expected) -> assertEquals(text, expected, Money.parseMinor(text)) }
        assertEquals("0.01", Money.formatMinor(1))
        assertEquals("58.00", Money.formatMinor(5800))
        assertEquals("92233720368547758.07", Money.formatMinor(Long.MAX_VALUE))
    }

    @Test
    fun invalidAndOverflowingInputIsRejected() {
        listOf("", "0", "0.00", "-1", "+1", "1e3", "1,000", " 1", "1 ", "1\n",
            "1.234", ".50", "1.", "NaN", "Infinity", "５８", "92233720368547758.08")
            .forEach { text -> assertThrows(text, IllegalArgumentException::class.java) { Money.parseMinor(text) } }
        listOf(0L, -1L, Long.MIN_VALUE).forEach { minor ->
            assertThrows(IllegalArgumentException::class.java) { Money.formatMinor(minor) }
        }
    }

    @Test
    fun seededValuesMatchAnIndependentDecimalOracle() {
        val random = Random(20261023L)
        repeat(1000) {
            val minor = (random.nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
            val decimal = BigDecimal.valueOf(minor, 2).toPlainString()
            assertEquals(decimal, Money.formatMinor(minor))
            assertEquals(minor, Money.parseMinor(decimal))
        }
    }
}
