package com.livetranslate.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {
    @Test
    fun masksApiKeys() {
        DiagnosticsLog.clear()
        DiagnosticsLog.add("net", "url=https://x/y?key=AIzaSyA1234567890abcdefghijklmnopqrstuv&a=1")
        DiagnosticsLog.add("net", "Authorization: Bearer gsk_abcdefghijklmnopqrstuvwxyz0123")
        val text = DiagnosticsLog.snapshot().joinToString("\n")
        assertFalse(text.contains("AIzaSy"))
        assertFalse(text.contains("gsk_abc"))
        assertTrue(text.contains("key=***"))
    }

    @Test
    fun keepsOnlyNewestEntries() {
        DiagnosticsLog.clear()
        repeat(350) { DiagnosticsLog.add("t", "n=$it") }
        val lines = DiagnosticsLog.snapshot()
        assertEquals(300, lines.size)
        assertTrue(lines.last().endsWith("n=349"))
        assertTrue(lines.first().endsWith("n=50"))
    }

    @Test
    fun entryStaysOnOneLineAndReportHasHeader() {
        DiagnosticsLog.clear()
        DiagnosticsLog.add("chunk", "line one\nline two")
        val report = DiagnosticsLog.buildReport("version=1.2.3")
        assertTrue(report.startsWith("version=1.2.3"))
        assertTrue(report.contains("line one line two"))
    }
}
