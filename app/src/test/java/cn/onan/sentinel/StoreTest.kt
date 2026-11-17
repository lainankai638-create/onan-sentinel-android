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
@Config(sdk = [34], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoreTest {
    private fun store() = EventStore(RuntimeEnvironment.getApplication())
    @Test fun dataSurvivesReopenAndUncleanSessionHasUncertainGap() {
        store().use {
            it.setMeta("active", "true"); it.setMeta("lastTick", "1234000")
            it.add("SAMPLE", "OK", "OBSERVED_PIDS=123", Policy.targets[0], "SHIZUKU_PS")
        }
        store().use {
            it.recoverUnclean()
            assertEquals("false", it.meta("active"))
            assertEquals("1234000", it.meta("gapAt"))
            assertTrue(it.latest().any { row -> row.type == "SAMPLE" })
            assertTrue(it.latest().any { row -> row.type == "UNCLEAN_STOP_DETECTED" })
            it.recoverUnclean()
            assertEquals(1, it.latest().count { row -> row.type == "UNCLEAN_STOP_DETECTED" })
        }
    }
    @Test fun repeatedDisconnectsDoNotInventGapClosures() {
        store().use {
            it.gap("SHIZUKU_DISCONNECTED", 1000)
            it.gap("PERMISSION_REQUIRED", 2000)
            assertEquals("1000", it.meta("gapAt"))
            assertEquals(1, it.latest().count { row -> row.type == "GAP_BEGIN" })
            assertEquals(0, it.latest().count { row -> row.type == "GAP_END" })
            it.recovered()
            assertEquals("", it.meta("gapAt"))
            assertEquals(1, it.latest().count { row -> row.type == "GAP_END" })
        }
    }
    @Test fun transactionFailureDoesNotLeaveHalfSession() {
        store().use {
            assertThrows(IllegalStateException::class.java) {
                it.atomic { setMeta("active", "true"); add("SESSION_START", "OK", "x"); error("test rollback") }
            }
            assertEquals("", it.meta("active"))
            assertTrue(it.latest().isEmpty())
        }
    }
    @Test fun earlierGapBoundIsPreservedAfterDelayedDisconnectDetection() {
        store().use {
            it.gap("DETECTED_AT_WAKE", 2000)
            it.gap("LAST_SAMPLE_BEFORE_SLEEP", 1000)
            assertEquals("1000", it.meta("gapAt"))
            assertTrue(it.latest().any { row -> row.type == "GAP_BOUND_ADJUSTED" && row.time == 1000L })
        }
    }
    @Test fun blockedExportDoesNotBlockRecordingOrStopping() {
        store().use {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val export = pool.submit {
                    it.export(object : java.io.Writer() {
                        override fun write(buffer: CharArray, off: Int, len: Int) { entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                        override fun flush() = Unit
                        override fun close() = Unit
                    })
                }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                pool.submit { it.add("SESSION_STOP", "UNAVAILABLE", "USER_STOPPED") }.get(3, java.util.concurrent.TimeUnit.SECONDS)
                release.countDown(); export.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }
    @Test fun exportRedactsPidTargetsAndPreciseTimestamps() {
        store().use {
            it.add("SAMPLE", "OK", "OBSERVED_PIDS=123,456", Policy.targets[0], "SHIZUKU_PS", 1234567)
            val output = StringWriter().also { writer -> it.export(writer) }.toString()
            assertFalse(output.contains("123,456"))
            assertFalse(output.contains("com.miui.securitycenter"))
            assertFalse(output.contains("1234567"))
            assertTrue(output.contains("TARGET_1"))
            assertTrue(output.contains("1260000").not())
            assertTrue(output.contains("1200000"))
        }
    }
    @Test fun cleanupRefusesActiveSessionAndPreservesMetadata() {
        store().use {
            it.setMeta("active", "true")
            it.add("SAMPLE", "OK", "x")
            assertThrows(IllegalStateException::class.java) { it.clearHistory() }
            assertEquals(1, it.latest().size)
            it.setMeta("active", "false"); it.clearHistory()
            assertTrue(it.latest().none { row -> row.type == "SAMPLE" })
            assertTrue(it.latest().any { row -> row.type == "LOCAL_CLEANUP" })
        }
    }
    @Test fun databaseAndRowsRemainBoundedUnderLoad() {
        store().use {
            it.atomic { repeat(Policy.MAX_ROWS + 100) { add("SAMPLE", "OK", "x".repeat(2048)) } }
            assertTrue(it.latest(limit = Policy.MAX_ROWS).size < Policy.MAX_ROWS)
            assertTrue(it.meta("retentionAt").isNotEmpty())
            val path = RuntimeEnvironment.getApplication().getDatabasePath("sentinel_v020.db")
            assertTrue(path.length() <= Policy.DB_BYTES)
        }
    }
}
