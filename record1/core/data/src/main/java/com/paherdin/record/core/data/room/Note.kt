package com.paherdin.record.core.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Same-ID subtype row: PK and FK to [EntityRef]. `NO ACTION` per ADR 0006.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = EntityRef::class,
            parentColumns = ["id"],
            childColumns = ["id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
)
internal data class Note(
    @PrimaryKey val id: String,
)
