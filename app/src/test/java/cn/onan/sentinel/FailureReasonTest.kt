package cn.onan.sentinel

import org.junit.Assert.*
import org.junit.Test

class FailureReasonTest {
    @Test fun migrationSpaceFailureRemainsVisibleThroughWrapper() {
        assertEquals("MIGRATION_SPACE_REQUIRED",FailureReason.of(RuntimeException("wrapper",IllegalStateException("MIGRATION_SPACE_REQUIRED"))))
    }
    @Test fun unknownExceptionTextNeverLeaksIntoStatus() {
        assertEquals("IllegalStateException",FailureReason.of(IllegalStateException("private content /user/path?token=secret")))
    }
}
