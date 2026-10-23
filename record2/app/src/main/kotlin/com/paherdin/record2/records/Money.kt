package com.paherdin.record2.records

internal object Money {
    private val amount = Regex("[0-9]+(?:\\.[0-9]{1,2})?")

    fun parseMinor(text: String): Long {
        require(amount.matches(text)) { "Amount must be a positive decimal with at most two fractional digits" }
        val whole = text.substringBefore('.')
        val fraction = text.substringAfter('.', "").padEnd(2, '0')
        val minor = (whole + fraction).toLongOrNull()
        require(minor != null && minor > 0) { "Amount must fit positive Long minor units" }
        return minor
    }

    fun formatMinor(minor: Long): String {
        require(minor > 0) { "Amount must be positive" }
        return "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
    }
}
