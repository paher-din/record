package com.paherdin.record2.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface SessionDao {
    @Insert fun insertSession(row: AgentSessionEntity)
    @Insert fun insertRun(row: AgentRunEntity)
    @Insert fun insertMessage(row: AgentMessageEntity)
    @Insert fun insertReference(row: AgentMessageReferenceEntity)

    @Query("SELECT * FROM agent_run WHERE id = :id")
    fun run(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_message WHERE id = :id")
    fun message(id: String): AgentMessageEntity?
}
