package cn.onan.sentinel

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun fixedCommandsContainNoInterpreterOrMutation() {
        for (target in Policy.targets.indices) {
            assertEquals(listOf("/system/bin/ps", "-A", "-w", "-o", "UID,PID,NAME"), Policy.command(1, target))
            assertEquals(listOf("/system/bin/cmd", "appops", "get", "--user", "0", Policy.targets[target]), Policy.command(2, target))
        }
    }
    @Test fun invalidCommandsRejected() {
        for ((kind, target) in listOf(-1 to 0, 3 to 0, 1 to -1, 2 to 3, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { Policy.command(kind, target) }
        }
    }
    @Test fun processNamesMatchBoundaryAndIncludeSubprocess() {
        val result = Parsers.process(" UID PID NAME\n 1000 12 com.miui.securitycenter\n 1000 13 com.miui.securitycenter:remote\n 1000 14 com.miui.securitycenter.evil\n 10001 15 private.chat.app\n 101000 16 com.miui.securitycenter", Policy.targets[0])
        assertEquals(Evidence("OK", "OBSERVED_PIDS=12,13"), result)
        assertFalse(result.value.contains("private"))
    }
    @Test fun emptyOrMalformedProcessListingIsNotAbsence() {
        for (raw in listOf("", "Permission denied", "UID PID NAME\ninvalid", "USER PID NAME")) assertEquals("UNSUPPORTED", Parsers.process(raw, Policy.targets[0]).status)
    }
    @Test fun absenceOnlyMeansNotObserved() {
        assertEquals("NOT_OBSERVED_IN_PS", Parsers.process("UID PID NAME\n1000 123 other\n101000 124 com.miui.securitycenter", Policy.targets[0]).value)
    }
    @Test fun appopsDropsPrivateAttributionAndRelativeTimes() {
        val a = Parsers.appOps("CAMERA: allow; time=+1s ago\n  attributionTag=private-message\nUid mode: COARSE_LOCATION: foreground\nREAD_SMS: ignore")
        val b = Parsers.appOps("READ_SMS: ignore\nUid mode: COARSE_LOCATION: foreground\nCAMERA: allow; time=+40s ago")
        assertEquals("PARTIAL", a.status)
        assertEquals(a, b)
        assertFalse(a.value.contains("private"))
    }
    @Test fun appopsUnknownOutputNeverBecomesEmptySuccess() {
        assertEquals("UNSUPPORTED", Parsers.appOps("unrecognized firmware response").status)
        assertEquals("DENIED", Parsers.appOps("SecurityException: permission denied").status)
        assertEquals("UNAVAILABLE", Parsers.appOps("Unknown package com.miui.securitycenter").status)
        assertEquals(Evidence("OK", "NO_RECORDED_OPERATIONS"), Parsers.appOps("No operations.\n"))
    }
    @Test fun baselineInvalidatedAfterUnavailableSample() {
        val b = Baselines()
        assertEquals("BASELINE", b.compare("a", Evidence("OK", "one")))
        assertEquals("SAMPLE", b.compare("a", Evidence("OK", "one")))
        assertEquals("STATE_CHANGED", b.compare("a", Evidence("OK", "two")))
        assertEquals("SAMPLE_UNAVAILABLE", b.compare("a", Evidence("DENIED", "no")))
        assertEquals("BASELINE", b.compare("a", Evidence("OK", "three")))
        b.clear()
        assertEquals("BASELINE", b.compare("a", Evidence("OK", "three")))
    }
    @Test fun twentyFourHourSimulationDoesNotClaimDeviceUptime() {
        val b = Baselines()
        var samples = 0
        for (minute in 0 until 24 * 60 step 5) {
            if (minute in 60..120) { b.clear(); continue }
            for (target in Policy.targets.indices) {
                b.compare("$target:process", Evidence("OK", "OBSERVED_PIDS=42")); samples++
            }
        }
        assertEquals((288 - 13) * 3, samples)
    }
}
