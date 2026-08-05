package com.paherdin.record.model

/**
 * Entity discriminator, mirroring the domain model exactly.
 * Persisted as its string `name` (Room default conversion); no extra members,
 * no custom serialization.
 */
enum class EntityType {
    TRANSACTION,
    NOTE,
    PROJECT,
}
