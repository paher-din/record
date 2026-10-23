package com.paherdin.record2.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface AiControlDao {
    @Insert
    fun insertControl(row: AiControlEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertControlIfAbsent(row: AiControlEntity)

    @Query("SELECT * FROM ai_control WHERE id = 1")
    fun control(): AiControlEntity?

    @Query("UPDATE ai_control SET mode = :mode, authEpoch = authEpoch + 1 WHERE id = 1 AND authEpoch = :expectedEpoch")
    fun updateMode(mode: String, expectedEpoch: Long): Int

    @Query(
        "UPDATE ai_control SET selectedReceiverIdentity = :receiver, grantReceiverIdentity = NULL, " +
            "grantScopeVersion = NULL, grantDisclosureVersion = NULL, grantedAt = NULL, " +
            "authEpoch = authEpoch + 1 WHERE id = 1 AND authEpoch = :expectedEpoch",
    )
    fun selectReceiver(receiver: String, expectedEpoch: Long): Int

    @Query(
        "UPDATE ai_control SET selectedReceiverIdentity = :receiver, grantReceiverIdentity = :receiver, " +
            "grantScopeVersion = :scopeVersion, grantDisclosureVersion = :disclosureVersion, " +
            "grantedAt = :grantedAt, authEpoch = authEpoch + 1 WHERE id = 1 AND authEpoch = :expectedEpoch",
    )
    fun grant(
        receiver: String,
        scopeVersion: Int,
        disclosureVersion: Int,
        grantedAt: Long,
        expectedEpoch: Long,
    ): Int

    @Query(
        "UPDATE ai_control SET grantReceiverIdentity = NULL, grantScopeVersion = NULL, " +
            "grantDisclosureVersion = NULL, grantedAt = NULL, authEpoch = authEpoch + 1 " +
            "WHERE id = 1 AND authEpoch = :expectedEpoch",
    )
    fun revoke(expectedEpoch: Long): Int

    @Query("UPDATE ai_control SET authEpoch = authEpoch + 1 WHERE id = 1 AND authEpoch = :expectedEpoch")
    fun bumpAuthEpoch(expectedEpoch: Long): Int
}
