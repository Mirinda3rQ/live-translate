package com.livetranslate.app.live

/**
 * Last line of defence against the Live Translate model drifting out of the selected target
 * language mid-session. Only Thai is checked: a fragment that has letters but none of them
 * Thai is treated as drift and not shown. Short Latin fragments (names, "OK", brands) pass.
 */
object TargetLanguageGuard {
    private const val LATIN_DRIFT_MIN_LETTERS = 8

    fun accepts(text: String, targetLanguageCode: String): Boolean {
        val target = targetLanguageCode.trim().lowercase().substringBefore('-').substringBefore('_')
        if (target != "th") return true

        var thai = 0
        var latin = 0
        var otherScript = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            when {
                ch.code in 0x0E00..0x0E7F -> thai++
                ch.code < 0x0250 -> latin++
                else -> otherScript++
            }
        }
        if (thai > 0) return true
        if (otherScript > 0) return false // CJK, Cyrillic, Arabic, Korean... in Thai output
        return latin < LATIN_DRIFT_MIN_LETTERS
    }
}
