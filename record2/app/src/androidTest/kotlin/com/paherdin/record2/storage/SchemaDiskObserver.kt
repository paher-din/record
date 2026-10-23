package com.paherdin.record2.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

internal object SchemaDiskObserver {
    val tables = setOf(
        "record_data_state", "account", "category", "record_transaction", "note",
        "transaction_note_link", "native_finance_draft", "change_proposal", "commit_operation",
        "agent_session", "agent_run", "agent_message", "agent_message_reference", "ai_control",
    )

    fun snapshot(path: File): Map<String, List<List<Any?>>> =
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { observer ->
            observer.execSQL("BEGIN DEFERRED TRANSACTION")
            try {
                tables.associateWith { table ->
                    observer.rawQuery("SELECT * FROM `$table` ORDER BY 1, 2", null).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add((0 until cursor.columnCount).map { column ->
                                    when (cursor.getType(column)) {
                                        Cursor.FIELD_TYPE_NULL -> null
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                                        else -> error("Unexpected storage type in $table column $column")
                                    }
                                })
                            }
                        }
                    }
                }
            } finally {
                observer.execSQL("ROLLBACK")
            }
        }
}
