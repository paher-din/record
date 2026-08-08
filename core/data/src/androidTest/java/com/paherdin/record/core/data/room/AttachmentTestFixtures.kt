package com.paherdin.record.core.data.room

import java.io.File

/** Shared image fixtures for the attachment suites (plan Task 5/6/7). */
internal object AttachmentTestFixtures {

    /** A real 1x1 PNG (decodable, decoded MIME "image/png"). */
    val ONE_PIXEL_PNG: ByteArray = intArrayOf(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR length+type
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, // 1x1
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, // bit depth/color/CRC
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, // IDAT
        0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01, // zlib data
        0x0D, 0x0A, 0x2D, 0xB4, // IDAT CRC
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, // IEND
        0xAE, 0x42, 0x60, 0x82, // IEND CRC
    ).map { it.toByte() }.toByteArray()

    /** A real 1x1 JPEG (decodable, decoded MIME "image/jpeg"). */
    val ONE_PIXEL_JPEG: ByteArray = intArrayOf(
        0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46,
        0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0xFF, 0xDB, 0x00, 0x43, 0x00, 0x08, 0x06, 0x06, 0x07, 0x06,
        0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C,
        0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F,
        0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20,
        0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28,
        0x37, 0x29, 0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27,
        0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32, 0xFF,
        0xC0, 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01,
        0x11, 0x00, 0xFF, 0xC4, 0x00, 0x1F, 0x00, 0x00, 0x01, 0x05,
        0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
        0x07, 0x08, 0x09, 0x0A, 0x0B, 0xFF, 0xDA, 0x00, 0x08, 0x01,
        0x01, 0x00, 0x00, 0x3F, 0x00, 0x37, 0x92, 0x04, 0x0A, 0xFF,
        0xD9,
    ).map { it.toByte() }.toByteArray()

    fun writeSource(dir: File, name: String, bytes: ByteArray): File {
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }

    /** A sparse file of exactly `sizeBytes` (all zero bytes; undecodable). */
    fun writeSparse(dir: File, name: String, sizeBytes: Long): File {
        val f = File(dir, name)
        java.io.RandomAccessFile(f, "rw").use { it.setLength(sizeBytes) }
        return f
    }

    /** The six v1 tables with the exact ADR 0006 DDL (from the committed 1.json). */
    fun createV1Schema(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `EntityRef` (`id` TEXT NOT NULL, `entityType` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `Transaction` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`id`) REFERENCES `EntityRef`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `Account` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `currency` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `AccountEntry` (`id` TEXT NOT NULL, `transactionId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`transactionId`) REFERENCES `Transaction`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`accountId`) REFERENCES `Account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_AccountEntry_transactionId` ON `AccountEntry` (`transactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_AccountEntry_accountId` ON `AccountEntry` (`accountId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `Note` (`id` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`id`) REFERENCES `EntityRef`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `TransactionNoteRelation` (`transactionId` TEXT NOT NULL, `noteId` TEXT NOT NULL, PRIMARY KEY(`transactionId`, `noteId`), FOREIGN KEY(`transactionId`) REFERENCES `Transaction`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`noteId`) REFERENCES `Note`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_TransactionNoteRelation_noteId_transactionId` ON `TransactionNoteRelation` (`noteId`, `transactionId`)")
        db.execSQL("PRAGMA user_version = 1")
    }
}
