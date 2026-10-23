package com.paherdin.record2.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecordDataStateEntity::class, AccountEntity::class, CategoryEntity::class,
        TransactionEntity::class, NoteEntity::class, TransactionNoteLinkEntity::class,
        NativeFinanceDraftEntity::class, ChangeProposalEntity::class, CommitOperationEntity::class,
        AgentSessionEntity::class, AgentRunEntity::class, AgentMessageEntity::class,
        AgentMessageReferenceEntity::class, AiControlEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class RecordDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun proposals(): ProposalDao
    abstract fun sessions(): SessionDao
    abstract fun aiControl(): AiControlDao
}
