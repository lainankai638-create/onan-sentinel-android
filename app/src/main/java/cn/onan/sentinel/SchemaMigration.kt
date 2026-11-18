package cn.onan.sentinel

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Called inside SQLiteOpenHelper's upgrade transaction, before any schema mutation. */
internal object SchemaMigration {
    private val tables = linkedMapOf(
        "events" to "id,timestamp,elapsed,target,type,source,status,evidence",
        "meta" to "key,value",
        "sqlite_sequence" to "name,seq"
    )
    fun verifyV1(db: SQLiteDatabase) {
        check(db.version == 1) { "MIGRATION_VERSION_UNSUPPORTED" }
        val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'",null).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(0)) }
        }
        check(names == setOf("events","meta")) { "MIGRATION_SCHEMA_UNSUPPORTED" }
        for ((table, columns) in tables) {
            val actual = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            check(actual == columns.split(',')) { "MIGRATION_SCHEMA_UNSUPPORTED" }
        }
        db.rawQuery("PRAGMA quick_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
    }
    private fun digest(db: SQLiteDatabase): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        for ((table, columns) in tables) {
            hash.update(table.toByteArray())
            db.rawQuery("SELECT $columns FROM $table ORDER BY ${columns.substringBefore(',')}", null).use { c ->
                while (c.moveToNext()) for (i in 0 until c.columnCount) {
                    val bytes = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i).toByteArray(Charsets.UTF_8)
                    }
                    hash.update("${c.getType(i)}:${bytes.size}:".toByteArray()); hash.update(bytes)
                }
            }
        }
        return hash.digest()
    }
    fun backup(db: SQLiteDatabase, directory: File, availableBytes: () -> Long = { directory.usableSpace }): File {
        verifyV1(db)
        check(directory.isDirectory || directory.mkdirs()) { "MIGRATION_BACKUP_DIRECTORY_FAILED" }
        val final = File(directory, "sentinel-schema1-backup.db")
        val expected = digest(db)
        if (final.exists()) {
            SQLiteDatabase.openDatabase(final.path, null, SQLiteDatabase.OPEN_READONLY).use {
                verifyV1(it); check(expected.contentEquals(digest(it))) { "MIGRATION_BACKUP_MISMATCH" }
            }
            return final
        }
        // Account for backup, rollback journals and schema expansion; fail before modifying old rows.
        check(availableBytes() >= File(db.path).length() * 3 + 8L * 1024 * 1024) { "MIGRATION_SPACE_REQUIRED" }
        val temp = File(directory, "sentinel-schema1-backup.tmp")
        SQLiteDatabase.deleteDatabase(temp) // Only our incomplete temporary backup, never the original.
        try {
            SQLiteDatabase.openOrCreateDatabase(temp, null).use { copy ->
                copy.execSQL("PRAGMA synchronous=FULL")
                copy.beginTransaction()
                try {
                    db.rawQuery("SELECT sql FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' AND name!='android_metadata' AND sql IS NOT NULL ORDER BY type DESC", null).use {
                        while (it.moveToNext()) copy.execSQL(it.getString(0))
                    }
                    for ((table, columns) in tables) {
                        copy.delete(table, null, null)
                        db.rawQuery("SELECT $columns FROM $table", null).use { c ->
                            while (c.moveToNext()) {
                                val values = ContentValues()
                                for (i in 0 until c.columnCount) when (c.getType(i)) {
                                    Cursor.FIELD_TYPE_NULL -> values.putNull(c.getColumnName(i))
                                    Cursor.FIELD_TYPE_INTEGER -> values.put(c.getColumnName(i), c.getLong(i))
                                    Cursor.FIELD_TYPE_FLOAT -> values.put(c.getColumnName(i), c.getDouble(i))
                                    Cursor.FIELD_TYPE_BLOB -> values.put(c.getColumnName(i), c.getBlob(i))
                                    else -> values.put(c.getColumnName(i), c.getString(i))
                                }
                                copy.insertOrThrow(table, null, values)
                            }
                        }
                    }
                    copy.version = 1; copy.setTransactionSuccessful()
                } finally { copy.endTransaction() }
            }
            SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use {
                verifyV1(it); check(expected.contentEquals(digest(it))) { "MIGRATION_BACKUP_VERIFY_FAILED" }
            }
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            check(temp.renameTo(final)) { "MIGRATION_BACKUP_RENAME_FAILED" }
            return final // Retained until explicit user cleanup after stopping; no automatic deletion.
        } catch (e: Exception) { SQLiteDatabase.deleteDatabase(temp); throw e }
    }
    fun createV2(db: SQLiteDatabase, legacyMaxId: Long = 0) {
        db.execSQL("CREATE TABLE schema_history(version INTEGER PRIMARY KEY, legacy_max_id INTEGER NOT NULL)")
        db.execSQL("INSERT INTO schema_history VALUES(2,?)", arrayOf(legacyMaxId))
        db.execSQL("CREATE TABLE source_state(source TEXT NOT NULL,target TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,last_success INTEGER,last_attempt INTEGER,open_gap_id INTEGER,PRIMARY KEY(source,target))")
        db.execSQL("CREATE TABLE source_gaps(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,target TEXT NOT NULL,start_at INTEGER NOT NULL,end_at INTEGER,start_kind TEXT NOT NULL,reason TEXT NOT NULL)")
        db.execSQL("CREATE INDEX gaps_source_target ON source_gaps(source,target,id)")
        db.execSQL("CREATE TABLE collection_runs(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,target TEXT NOT NULL,started INTEGER NOT NULL,ended INTEGER NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,session TEXT NOT NULL,generation INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE retention_log(id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,rows_removed INTEGER NOT NULL,first_id INTEGER,last_id INTEGER,reason TEXT NOT NULL)")
    }
}
