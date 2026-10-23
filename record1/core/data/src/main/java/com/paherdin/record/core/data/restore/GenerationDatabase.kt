package com.paherdin.record.core.data.restore

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.paherdin.record.core.data.room.RecordDatabase
import java.io.File

/**
 * Opens a `RecordDatabase` at an arbitrary app-private file path (spec 4.5,
 * plan Task 3): the generation databases live under `files/vaults/...`, which
 * Room's relative-name resolution cannot reach. `Context.getDatabasePath`
 * returns an absolute `File` when the name starts with `/`, so passing the
 * absolute path as the Room name opens the exact file; Room still runs the
 * schema contract, `user_version`, and callback on it. The generation
 * directory (the file's parent) already exists before open.
 */
internal fun openGenerationDatabase(context: Context, dbFile: File): RecordDatabase =
    Room.databaseBuilder(context, RecordDatabase::class.java, dbFile.absolutePath)
        .addMigrations(RecordDatabase.MIGRATION_1_2)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()
