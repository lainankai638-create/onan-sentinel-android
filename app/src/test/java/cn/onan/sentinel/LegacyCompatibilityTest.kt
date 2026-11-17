package cn.onan.sentinel

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyCompatibilityTest {
    @Test fun v011SchemaRemainsByteIdenticalAfterMonitoringAndCleanup() {
        val context = RuntimeEnvironment.getApplication()
        val old = context.getDatabasePath("evidence.db")
        old.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(old, null).use {
            // Exact DDL from v0.1.1 c5b14b5 Evidence.kt, no real user data involved.
            it.execSQL("CREATE TABLE evidence(id INTEGER PRIMARY KEY, time INTEGER NOT NULL, target TEXT NOT NULL, kind TEXT NOT NULL, grade TEXT NOT NULL, source TEXT NOT NULL, data TEXT NOT NULL)")
            it.execSQL("CREATE INDEX evidence_target_time ON evidence(target,time)")
            it.version = 1
            it.execSQL("INSERT INTO evidence(time,target,kind,grade,source,data) VALUES(123456, 'com.miui.securitycenter', '流量快照', '已确认', 'synthetic fixture', '{}')")
        }
        fun digest() = MessageDigest.getInstance("SHA-256").digest(old.readBytes())
        val before = digest()
        assertEquals(LegacySummary(true, 1), LegacyEvidenceReader.inspect(context))
        EventStore(context).use {
            it.add("SAMPLE", "OK", "OBSERVED_PIDS=1")
            it.clearHistory()
        }
        assertEquals(LegacySummary(true, 1), LegacyEvidenceReader.inspect(context))
        assertArrayEquals(before, digest())
    }
    @Test fun unknownLegacySchemaIsRejectedWithoutReset() {
        val context = RuntimeEnvironment.getApplication()
        val old = context.getDatabasePath("evidence.db")
        old.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(old, null).use { it.version = 9; it.execSQL("CREATE TABLE important(value TEXT)") }
        val before = old.readBytes()
        assertThrows(IllegalStateException::class.java) { LegacyEvidenceReader.inspect(context) }
        assertArrayEquals(before, old.readBytes())
    }
    @Test fun missingLegacyDatabaseIsNotCreated() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(LegacyEvidenceReader.inspect(context).exists)
        assertFalse(context.getDatabasePath("evidence.db").exists())
    }
}
