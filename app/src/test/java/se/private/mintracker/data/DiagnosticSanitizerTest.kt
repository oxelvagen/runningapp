package se.oxelv.mintracker.data

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test fun onlyAppClassAndMethodSurviveWithoutExceptionContents() {
        val error = IllegalStateException("secret@example.com token=secret lat=59.123 https://private")
        error.stackTrace = arrayOf(
            StackTraceElement("se.oxelv.mintracker.data.ActivityRepository", "saveLocal", "secret@example.com", 999),
            StackTraceElement("io.ktor.Private", "token", "private", 1),
            StackTraceElement("se.oxelv.mintracker.Bad", "https://private", "private", 1))
        assertEquals(listOf("se.oxelv.mintracker.data.ActivityRepository#saveLocal"), DiagnosticSanitizer.frames(error))
        assertFalse(DiagnosticSanitizer.frames(error).toString().contains("secret"))
    }
    @Test fun stackAndMetadataAreBoundedAndCodesSeparateNormalStages() {
        val error = Exception().apply { stackTrace = Array(100) { StackTraceElement("se.oxelv.mintracker.Test", "run", null, 0) } }
        assertEquals(20, DiagnosticSanitizer.frames(error).size)
        assertEquals(80, DiagnosticSanitizer.model("A".repeat(100)).length)
        assertEquals("Unknown", DiagnosticSanitizer.model("💥"))
        assertEquals("INFO", DiagnosticCode.GPS_STARTED.severity)
        assertEquals("WARNING", DiagnosticCode.PROCESS_INTERRUPTED.severity)
        assertEquals("CRITICAL", DiagnosticCode.UNCAUGHT_EXCEPTION.severity)
    }
}
