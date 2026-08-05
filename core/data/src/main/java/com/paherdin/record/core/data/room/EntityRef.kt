package com.paherdin.record.core.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.paherdin.record.model.EntityType

/**
 * Entity discriminator row (ADR 0006): epoch-millis UTC `Long` timestamps,
 * nullable `deletedAt` absent while active, enum persisted as its string name.
 */
@Entity
internal data class EntityRef(
    @PrimaryKey val id: String,
    val entityType: EntityType,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)
