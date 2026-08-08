package com.paherdin.record.core.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version-2 schema (spec 4.6, ADR 0006 + ADR 0007): the six v1 tables are
 * unchanged and exactly `Attachment` and `AttachmentLink` are added by
 * `Migration(1, 2)`. The migration is registered only inside the
 * coordinator-gated builder (plan Task 4): no production open can migrate
 * without the verified snapshot published first. Schema JSON is exported and
 * committed (`1.json` stays byte-identical, `2.json` is added).
 */
@Database(
    entities = [
        EntityRef::class,
        Transaction::class,
        Account::class,
        AccountEntry::class,
        Note::class,
        TransactionNoteRelation::class,
        Attachment::class,
        AttachmentLink::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class RecordDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao

    companion object {
        /**
         * The only migration path: creates exactly the two v2 tables and the
         * `AttachmentLink(ownerEntityRefId)` index, changes nothing in the six
         * v1 tables, `NO ACTION` FKs per ADR 0006, no destructive fallback.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQL matches the committed 2.json createSql exactly so Room's
                // post-migration schema validation passes: the unique constraint
                // is an index, never an inline table constraint.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Attachment` (" +
                        "`id` TEXT NOT NULL, " +
                        "`contentIdentity` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_Attachment_contentIdentity` " +
                        "ON `Attachment` (`contentIdentity`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `AttachmentLink` (" +
                        "`attachmentId` TEXT NOT NULL, " +
                        "`ownerEntityRefId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`attachmentId`, `ownerEntityRefId`), " +
                        "FOREIGN KEY(`attachmentId`) REFERENCES `Attachment`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
                        "FOREIGN KEY(`ownerEntityRefId`) REFERENCES `EntityRef`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_AttachmentLink_ownerEntityRefId` " +
                        "ON `AttachmentLink` (`ownerEntityRefId`)",
                )
            }
        }
    }
}
