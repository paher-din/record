package com.paherdin.record.model

/**
 * Transaction type, mirroring the domain model exactly.
 * Persisted as its string `name` (Room default conversion); no extra members,
 * no custom serialization.
 */
enum class TransactionType {
    OPENING_BALANCE,
    INCOME,
    EXPENSE,
    TRANSFER,
}
