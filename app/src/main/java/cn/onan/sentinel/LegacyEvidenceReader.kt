package cn.onan.sentinel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

data class LegacySummary(val exists: Boolean, val rows: Long = 0)

/** v0.1.1 evidence.db schema inspection only. Never creates, migrates, prunes or resets the old DB. */
object LegacyEvidenceReader {
    fun inspect(context: Context): LegacySummary = inspectFile(context.getDatabasePath("evidence.db"))
    internal fun inspectFile(file: File): LegacySummary {
        if (!file.isFile) return LegacySummary(false)
        return SQLiteDatabase.openDatabase(file.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            check(db.isReadOnly) { "LEGACY_READ_ONLY_REQUIRED" }
            check(db.version == 1) { "LEGACY_SCHEMA_UNSUPPORTED" }
            val columns = db.rawQuery("PRAGMA table_info(evidence)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            check(columns == setOf("id", "time", "target", "kind", "grade", "source", "data")) { "LEGACY_SCHEMA_UNSUPPORTED" }
            val count = db.rawQuery("SELECT count(*) FROM evidence", null).use { it.moveToFirst(); it.getLong(0) }
            LegacySummary(true, count)
        }
    }
}
