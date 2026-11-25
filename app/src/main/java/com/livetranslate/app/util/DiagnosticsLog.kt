package com.livetranslate.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory ring buffer of recent translation events (provider used, source text,
 * translation, fallbacks, errors). Settings > "Copy diagnostics" puts it on the clipboard so
 * a problem can be reported with evidence instead of a description. Nothing is written to
 * disk or sent anywhere, and API keys are masked before an entry is stored.
 */
object DiagnosticsLog {
    private const val MAX_ENTRIES = 300
    private const val MAX_MESSAGE_CHARS = 400

    private val entries = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val GOOGLE_KEY = Regex("AIza[0-9A-Za-z_\\-]{20,}")
    private val GROQ_KEY = Regex("gsk_[0-9A-Za-z]{20,}")
    private val KEY_PARAM = Regex("(?i)([?&]key=)[^&\\s]+")
    private val BEARER = Regex("(?i)(bearer\\s+)[0-9A-Za-z._\\-]{16,}")

    fun redact(text: String): String {
        var out = GOOGLE_KEY.replace(text, "***")
        out = GROQ_KEY.replace(out, "***")
        out = KEY_PARAM.replace(out, "$1***")
        out = BEARER.replace(out, "$1***")
        return out
    }

    fun add(tag: String, message: String) {
        val line = redact(message).replace('\n', ' ').take(MAX_MESSAGE_CHARS)
        synchronized(entries) {
            entries.addLast("${timeFormat.format(Date())} [$tag] $line")
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }

    fun clear() = synchronized(entries) { entries.clear() }

    fun buildReport(header: String): String {
        val lines = snapshot()
        return header.trimEnd() + "\n\n--- last ${lines.size} events ---\n" + lines.joinToString("\n")
    }
}
