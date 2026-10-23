package com.paherdin.record2.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface AiControlDao {
    @Insert fun insertControl(row: AiControlEntity)

    @Query("SELECT * FROM ai_control WHERE id = 1")
    fun control(): AiControlEntity?
}
