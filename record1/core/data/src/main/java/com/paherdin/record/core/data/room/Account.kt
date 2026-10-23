package com.paherdin.record.core.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Identity-only account (spec 4.1): FK target for [AccountEntry].
 * Balance is derived from entries, never a stored field.
 */
@Entity
internal data class Account(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
)
