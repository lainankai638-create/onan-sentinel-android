package cn.onan.sentinel

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

/** Run only on a disposable emulator/test device with the isolated preview package. */
class StoreInstrumentedTest {
    @Test fun persistenceGapsRedactionAndRetention() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EventStore(context)
        assertNotEquals("true", store.meta("active"))
        store.clearHistory()
        store.add("SAMPLE", "OK", "OBSERVED_PIDS=123,456", Policy.targets[0], "SHIZUKU_PS")
        store.setMeta("active", "true")
        store.setMeta("lastTick", "100000")
        store.close()
        val reopened = EventStore(context)
        reopened.recoverUnclean()
        assertEquals("false", reopened.meta("active"))
        assertTrue(reopened.latest().any { it.type == "UNCLEAN_STOP_DETECTED" })
        val report = StringWriter().also { reopened.export(it) }.toString()
        assertFalse(report.contains("123,456"))
        assertFalse(report.contains("com.miui.securitycenter"))
        repeat(Policy.MAX_ROWS + 10) { reopened.add("SAMPLE", "OK", "x".repeat(2048)) }
        assertTrue(reopened.latest(limit = Policy.MAX_ROWS).size <= Policy.MAX_ROWS)
        assertTrue(context.getDatabasePath("sentinel_v020.db").length() <= Policy.DB_BYTES)
        reopened.close()
    }
}
