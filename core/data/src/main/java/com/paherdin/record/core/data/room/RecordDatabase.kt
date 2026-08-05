package com.paherdin.record.core.data.room

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version-1 schema per spec 4.1/4.2 and ADR 0006; schema JSON is exported
 * and committed. Foreign keys are enforced on open by the repository builder.
 */
@Database(
    entities = [
        EntityRef::class,
        Transaction::class,
        Account::class,
        AccountEntry::class,
        Note::class,
        TransactionNoteRelation::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class RecordDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
}
