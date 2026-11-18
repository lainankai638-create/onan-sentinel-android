package cn.onan.sentinel

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val path get() = context.getDatabasePath("sentinel_v020.db")
    private fun original(rows: Int = 3, width: Int = 20) {
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, elapsed INTEGER NOT NULL, target TEXT NOT NULL, type TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL, evidence TEXT NOT NULL)")
            db.execSQL("CREATE INDEX events_time ON events(timestamp)")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.beginTransaction()
            try {
                repeat(rows) { n -> db.insertOrThrow("events",null,ContentValues().apply {
                    put("timestamp",1000L+n);put("elapsed",n);put("target",Policy.targets[0]);put("type","SAMPLE")
                    put("source","SHIZUKU_PS");put("status","OK");put("evidence","中文,\n"+"x".repeat(width))
                }) }
                db.execSQL("INSERT INTO meta VALUES('historical','keep,\n中文')")
                db.version=1;db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    private fun readRows(db: SQLiteDatabase): List<List<String?>> = db.rawQuery("SELECT * FROM events ORDER BY id",null).use { c ->
        buildList { while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) }
    }
    @Test fun migrationPreservesEveryOriginalFieldAndKeepsVerifiedV1Backup() {
        original()
        val before=SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use { readRows(it) }
        EventStore(context).use { store ->
            assertEquals(2,store.writableDatabase.version)
            assertEquals(before,readRows(store.readableDatabase))
            assertEquals("keep,\n中文",store.meta("historical"));assertEquals(0,store.openSourceGaps())
            val output=StringWriter().also { store.export(it) }.toString()
            assertTrue(output.contains("legacy_schema_1"))
            assertFalse(output.contains("SOURCE_GAP")) // No invented historical per-source coverage.
        }
        SQLiteDatabase.openDatabase(context.noBackupFilesDir.resolve("sentinel-schema1-backup.db").path,null,SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(1,it.version);assertEquals(before,readRows(it))
        }
    }
    @Test fun fullRowQuotaMigrationDoesNotPruneOldEvents() {
        original(Policy.MAX_ROWS,1024)
        EventStore(context).use { store ->
            assertEquals(2,store.readableDatabase.version)
            store.readableDatabase.rawQuery("SELECT count(*),sum(length(evidence)) FROM events",null).use {
                assertTrue(it.moveToFirst());assertEquals(Policy.MAX_ROWS,it.getInt(0));assertEquals(Policy.MAX_ROWS*1028,it.getInt(1))
            }
        }
    }
    @Test fun insufficientBackupSpaceLeavesOriginalSchemaAndRowsUntouched() {
        original()
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            val before=readRows(db)
            assertThrows(IllegalStateException::class.java) { SchemaMigration.backup(db,context.noBackupFilesDir) { 0L } }
            assertEquals(1,db.version);assertEquals(before,readRows(db))
            assertFalse(context.noBackupFilesDir.resolve("sentinel-schema1-backup.db").exists())
        }
    }
    @Test fun interruptedUpgradeRollsBackAndCanRetryUsingSameBackup() {
        original()
        val failed=object:SQLiteOpenHelper(context,"sentinel_v020.db",null,2) {
            override fun onCreate(db: SQLiteDatabase) = error("Unexpected new DB")
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                SchemaMigration.backup(db,context.noBackupFilesDir);SchemaMigration.createV2(db,3);error("injected interruption")
            }
        }
        assertThrows(IllegalStateException::class.java) { failed.writableDatabase };failed.close()
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use { assertEquals(1,it.version);assertEquals(3,readRows(it).size) }
        val backup=context.noBackupFilesDir.resolve("sentinel-schema1-backup.db").readBytes()
        EventStore(context).use { assertEquals(2,it.writableDatabase.version);assertEquals(3,readRows(it.readableDatabase).size) }
        assertArrayEquals(backup,context.noBackupFilesDir.resolve("sentinel-schema1-backup.db").readBytes())
    }
    @Test fun unknownVersionOrShapeRefusesWithoutReset() {
        original()
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.version=3 }
        EventStore(context).use { store -> assertThrows(android.database.sqlite.SQLiteException::class.java) { store.writableDatabase } }
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.version=1;it.execSQL("ALTER TABLE events ADD COLUMN unexpected TEXT") }
        EventStore(context).use { store -> assertThrows(IllegalStateException::class.java) { store.writableDatabase } }
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use { assertEquals(1,it.version);assertEquals(3,readRows(it).size) }
    }
    @Test fun migrationPreservesAutoincrementHighWatermarkAfterDeletion() {
        original(10)
        SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM events WHERE id>3") }
        EventStore(context).use {
            it.add("SESSION_START","OK","new")
            assertEquals(11L,it.latest().first().id)
        }
    }
}
