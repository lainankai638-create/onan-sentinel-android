package cn.onan.sentinel

import org.junit.Assert.*
import org.junit.Test

/** Fixtures follow AOSP android-14.0.0_r1 source; they are not Redmi device captures. */
class Android14FormatTest {
    private fun fixture(name: String) = javaClass.getResource("/android14/$name")!!.readText()

    @Test fun noOperationsWithReportedDefaultIsRecognizedWithoutInventingAnOp() {
        assertEquals(Evidence("PARTIAL", "NO_RECORDED_OPERATIONS\nREPORTED_DEFAULT_MODE=allow"),
            Parsers.appOps(fixture("appops-empty-default.txt")))
    }

    @Test fun defaultModesAndWhitespaceAreNormalized() {
        for (mode in listOf("allow", "ignore", "deny", "default", "foreground", "errored", "ask")) {
            assertEquals(Evidence("PARTIAL", "NO_RECORDED_OPERATIONS\nREPORTED_DEFAULT_MODE=$mode"),
                Parsers.appOps("\r\n  No operations. \r\n\tDefault mode: $mode  \r\n"))
        }
        assertEquals(Evidence("OK", "NO_RECORDED_OPERATIONS"), Parsers.appOps("\n No operations. \n\n"))
    }

    @Test fun emptyMarkerCannotHideUnknownOrContradictoryOutput() {
        for (suffix in listOf("Default mode: unexpected", "Default mode: allow; private text",
            "Default mode: allow\nDefault mode: deny", "CAMERA: allow", "vendor warning")) {
            assertEquals("UNSUPPORTED", Parsers.appOps("No operations.\n$suffix").status)
        }
        assertEquals("UNSUPPORTED", Parsers.appOps("Default mode: allow").status)
        assertEquals("UNSUPPORTED", Parsers.appOps("vendor warning\nNo operations.").status)
    }

    @Test fun errorsTakePriorityOverEmptyMarker() {
        assertEquals("DENIED", Parsers.appOps("No operations.\nDefault mode: allow\nSecurityException: denied").status)
        assertEquals("DENIED", Parsers.appOps("No operations.\nPermission Denial: getAppOps").status)
        assertEquals("UNAVAILABLE", Parsers.appOps("No operations.\nNo UID for com.miui.securitycenter in user 0").status)
    }

    @Test fun aospUidPrefixDoesNotIdentifyUnprefixedRowsAsPackageModes() {
        val result = Parsers.appOps(fixture("appops-uid-and-package.txt"))
        assertEquals(Evidence("PARTIAL", listOf("UID:COARSE_LOCATION=foreground",
            "UNSCOPED:FINE_LOCATION=foreground", "UNSCOPED:COARSE_LOCATION=allow",
            "UNSCOPED:CAMERA=allow", "UNSCOPED:RECORD_AUDIO=ignore").sorted().joinToString("\n")), result)
        assertFalse(result.value.contains("time="))
        assertFalse(result.value.contains("running"))
        assertFalse(result.value.contains("duration="))
    }

    @Test fun processNameMayContainSpacesWithoutInvalidatingOtherRows() {
        assertEquals(Evidence("OK", "OBSERVED_PIDS=100,101"),
            Parsers.process(fixture("ps-names.txt").replace("\n", "\r\n"), Policy.targets[0]))
    }

    @Test fun vendorHeadersNamesAndTruncationAreNotGuessed() {
        for (raw in listOf("USER PID NAME\nu0_a1 100 com.miui.securitycenter",
            "UID PID CMD\n1000 100 com.miui.securitycenter",
            "UID PID NAME\nsystem 100 com.miui.securitycenter",
            "UID PID NAME\n1000 100", "ps: bad -o 'NAME'")) {
            assertEquals("UNSUPPORTED", Parsers.process(raw, Policy.targets[0]).status)
        }
        assertEquals(Evidence("OK", "NOT_OBSERVED_IN_PS"),
            Parsers.process("UID PID NAME\n1000 100 com.miui.secur", Policy.targets[0]))
    }

    @Test fun reportedDefaultChangeIsComparedAndUnknownOutputResetsBaseline() {
        val baselines = Baselines()
        val allow = Parsers.appOps(fixture("appops-empty-default.txt"))
        val deny = Parsers.appOps("No operations.\nDefault mode: deny")
        assertEquals("BASELINE", baselines.compare("appops", allow))
        assertEquals("SAMPLE", baselines.compare("appops", allow))
        assertEquals("STATE_CHANGED", baselines.compare("appops", deny))
        assertEquals("SAMPLE_UNAVAILABLE", baselines.compare("appops", Parsers.appOps("No operations.\nDefault mode: new-mode")))
        assertEquals("BASELINE", baselines.compare("appops", allow))
    }

    @Test fun vendorExtensionsRemainPartialAndDoNotLeakRawDetails() {
        val result = Parsers.appOps("CAMERA: allow; time=+1s ago\n  attributionTag=private-message\nVENDOR_OP: new-mode")
        assertEquals(Evidence("PARTIAL", "UNSCOPED:CAMERA=allow"), result)
        assertEquals("UNSUPPORTED", Parsers.appOps("VENDOR_OP: new-mode").status)
    }
}
