package cn.onan.sentinel

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SourceCoverageTest {
    @Test fun onlyRecoveredSourceClosesAndPartialSuccessDoesNotMaskOtherTargets() {
        EventStore(RuntimeEnvironment.getApplication()).use { s ->
            s.gap("START",1000);s.allSourcesUnavailable("START",1000,"OBSERVED_UNAVAILABLE")
            assertEquals(6,s.openSourceGaps())
            s.recordSample("SHIZUKU_PS",Policy.targets[0],Evidence("OK","NOT_OBSERVED_IN_PS"),"BASELINE",2000,2100,"test",1)
            assertEquals(5,s.openSourceGaps());s.recovered();assertEquals("1000",s.meta("gapAt"))
            s.recordSample("SHIZUKU_APPOPS",Policy.targets[0],Evidence("UNSUPPORTED","APPOPS_FORMAT_UNKNOWN"),"SAMPLE_UNAVAILABLE",2200,2300,"test",1)
            assertEquals(5,s.openSourceGaps());assertTrue(s.sourceSummary().contains("PARSE_ERROR"))
            for(target in Policy.targets) for(source in listOf("SHIZUKU_PS","SHIZUKU_APPOPS")) s.recordSample(source,target,Evidence("PARTIAL","mode"),"BASELINE",3000,3100,"test",2)
            assertEquals(0,s.openSourceGaps());s.recovered();assertEquals("",s.meta("gapAt"))
        }
    }
    @Test fun knownPauseAndUnknownLossBoundsRemainDistinctAndFailuresPersist() {
        EventStore(RuntimeEnvironment.getApplication()).use { s ->
            s.recordSample("SHIZUKU_PS",Policy.targets[0],Evidence("OK","OBSERVED_PIDS=123"),"BASELINE",1000,1100,"test",1)
            s.sourceUnavailable("SHIZUKU_PS",Policy.targets[0],"USER_PAUSED",2000,"KNOWN_STOP")
            s.readableDatabase.rawQuery("SELECT start_at,start_kind FROM source_gaps",null).use { it.moveToFirst();assertEquals(2000,it.getInt(0));assertEquals("KNOWN_STOP",it.getString(1)) }
            s.sourceUnavailable("SHIZUKU_PS",Policy.targets[0],"DELAY",1100,"UNKNOWN_LOWER_BOUND")
            s.recordSample("SHIZUKU_APPOPS",Policy.targets[0],Evidence("DENIED","APPOPS_PERMISSION_DENIED"),"SAMPLE_UNAVAILABLE",3000,3100,"test",2)
            assertEquals(2,s.openSourceGaps());assertTrue(s.sourceSummary().contains("PERMISSION_DENIED"))
            val out=StringWriter().also { s.export(it) }.toString()
            assertTrue(out.contains("UNKNOWN_LOWER_BOUND"));assertFalse(out.contains("OBSERVED_PIDS=123"));assertFalse(out.contains(Policy.targets[0]))
        }
    }
    @Test fun auxiliaryRetentionIsBoundedAndCleanupCannotRunWhileActive() {
        EventStore(RuntimeEnvironment.getApplication()).use { s ->
            s.atomic { repeat(2100) { n -> recordSample("SHIZUKU_PS",Policy.targets[0],Evidence("OK","NOT_OBSERVED_IN_PS"),"SAMPLE",n.toLong(),n.toLong(),"test",1) } }
            s.readableDatabase.rawQuery("SELECT count(*) FROM collection_runs",null).use { it.moveToFirst();assertTrue(it.getInt(0)<=2000) }
            assertTrue(StringWriter().also { s.export(it) }.toString().contains("AUXILIARY_collection_runs"))
            s.setMeta("active","true");assertThrows(IllegalStateException::class.java) { s.clearHistory() }
            s.setMeta("active","false");s.clearHistory();assertEquals(0,s.openSourceGaps())
        }
    }
    @Test fun reportUsesRetainedSourceIntervalsAndFlagsClockReversal() {
        EventStore(RuntimeEnvironment.getApplication()).use { s ->
            for(time in listOf(60000L,240000L,180000L)) s.recordSample("SHIZUKU_PS",Policy.targets[0],Evidence("OK","NOT_OBSERVED_IN_PS"),"SAMPLE",time,time,"test",1)
            val reports=StringWriter().also { s.export(it) }.toString().lineSequence().filter { it.isNotBlank() }.map { org.json.JSONObject(it) }.toList()
            val summary=reports.first { it.optString("type")=="SOURCE_HEALTH" }
            assertEquals(3,summary.getInt("validRetainedAttempts"));assertEquals(3,summary.getInt("maxRetainedSampleIntervalMinutes"))
            assertTrue(summary.getBoolean("clockReversed"))
        }
    }
    @Test fun failedExportRemovesSpoolAndDoesNotChangeDatabase() {
        val app=RuntimeEnvironment.getApplication()
        EventStore(app).use { s ->
            s.add("SAMPLE","OK","OBSERVED_PIDS=123",Policy.targets[0],"SHIZUKU_PS")
            assertThrows(java.io.IOException::class.java) {
                s.export(object:java.io.Writer() {
                    override fun write(c:CharArray,off:Int,len:Int) { throw java.io.IOException("provider failure") }
                    override fun flush()=Unit;override fun close()=Unit
                })
            }
            assertEquals(1,s.latest().size)
            assertTrue(app.cacheDir.listFiles()!!.none { it.name.startsWith("sentinel-redacted-") })
        }
    }
    @Test fun sqliteFullRollsBackWholeSampleWithoutResettingOldEvidence() {
        EventStore(RuntimeEnvironment.getApplication()).use { s ->
            s.add("SAMPLE","OK","old evidence")
            val db=s.writableDatabase
            val pages=db.rawQuery("PRAGMA page_count",null).use { it.moveToFirst();it.getLong(0) }
            db.rawQuery("PRAGMA max_page_count=$pages",null).use { it.moveToFirst() }
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                s.recordSample("SHIZUKU_PS",Policy.targets[0],Evidence("OK","x".repeat(4096)),"SAMPLE",1000,1001,"test",1)
            }
            assertEquals(2,db.version);assertEquals("old evidence",s.latest().single().evidence)
            db.rawQuery("SELECT count(*) FROM collection_runs",null).use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
        }
    }
    @Test fun lateOldServiceShutdownCannotCloseReplacementSession() {
        EventStore(RuntimeEnvironment.getApplication()).use { s ->
            s.setMeta("active","true");s.setMeta("activeOwner","new-owner")
            assertFalse(s.finishSession("old-owner","SERVICE_DESTROYED",1000))
            assertEquals("true",s.meta("active"));assertEquals(0,s.openSourceGaps());assertTrue(s.latest().isEmpty())
            assertTrue(s.finishSession("new-owner","USER_STOPPED",2000))
            assertEquals("false",s.meta("active"));assertEquals(6,s.openSourceGaps())
        }
    }
}
