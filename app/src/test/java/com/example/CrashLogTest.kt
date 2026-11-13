package com.example

import com.example.diagnostics.CrashLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {

    @Test
    fun report_keepsTheExceptionAndFrames_removesKeys() {
        val cause = java.util.regex.PatternSyntaxException("Syntax error", "^\\{(center|end|justify)}\\s?", 24)
        val error = IllegalStateException("HTTP 401 for key=sk-abcdefghijklmnop body {\"api_key\": \"AIzaSyA1234567890abcdefghijk\"} Authorization: Bearer abcdef123456", cause)
        val report = CrashLog.report("main", error)
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("Caused by: java.util.regex.PatternSyntaxException"))
        assertTrue("pattern kept for diagnosis", report.contains("(center|end|justify)"))
        assertTrue(report.contains("at com.example.CrashLogTest"))
        assertTrue(report.contains("Thread: main"))
        for (secret in listOf("sk-abcdefghijklmnop", "AIzaSyA1234567890abcdefghijk", "abcdef123456")) assertFalse("$secret in report", report.contains(secret))
    }

    @Test
    fun redact_leavesOrdinaryTextAlone() {
        val t = "java.lang.IndexOutOfBoundsException: index 5, length 3\n\tat a.b.C.d(C.kt:10)"
        assertTrue(CrashLog.redact(t) == t)
    }
}
