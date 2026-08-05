package com.paherdin.record.core.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Transaction entry: signed integer minor units, both FKs `NO ACTION`,
 * explicit index set per ADR 0006.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("transactionId"), Index("accountId")],
)
internal data class AccountEntry(
    @PrimaryKey val id: String,
    val transactionId: String,
    val accountId: String,
    val amountMinor: Long,
)
