package com.paherdin.record2.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_control")
internal data class AiControlEntity(
    @PrimaryKey val id: Int,
    val mode: String,
    val selectedReceiverIdentity: String?,
    val grantReceiverIdentity: String?,
    val grantScopeVersion: Int?,
    val grantDisclosureVersion: Int?,
    val grantedAt: Long?,
    val authEpoch: Long,
)
