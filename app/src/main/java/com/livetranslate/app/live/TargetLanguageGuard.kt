package com.livetranslate.app.live

/**
 * Last line of defence against the Live Translate model drifting out of the selected target
 * language mid-session. Only Thai is checked: a fragment that has letters but none of them
 * Thai is treated as drift and not shown. Short Latin fragments (names, "OK", brands) pass.
 */
object TargetLanguageGuard {
    private const val LATIN_DRIFT_MIN_LETTERS = 8

    private val PLACEHOLDER = Regex(
        "[(\\[（【]\\s*(ไม่มีการแปล|ไม่มีเสียง|ไม่มีคำพูด|ไม่มีข้อความ|no translation|no speech|" +
            "no audio|silence|inaudible|unintelligible)[^)\\]）】]{0,30}[)\\]）】]\\.?",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Thai-only output contract placed above the user's (possibly stale) custom prompt, so a
     * saved prompt such as "translate Japanese" cannot change the output language.
     */
    fun thaiOnlySystemPrompt(userInstructions: String): String {
        val userPrompt = userInstructions.trim().ifBlank {
            com.livetranslate.app.data.UserSettings.Defaults.DEFAULT_SYSTEM_PROMPT
        }
        return """
            MANDATORY TRANSLATION CONTRACT — HIGHEST PRIORITY:
            The text may be in ANY language, including English, Japanese, Chinese, Korean, or mixed
            languages. Translate all intelligible content into natural, accurate Thai (ภาษาไทย).
            Output Thai subtitles only; never answer in English or another language and never copy
            the source text unchanged. Never apologize, refuse, explain limitations, or add commentary.
            If the text is genuinely unintelligible, return an empty string.
            Preserve meaning, names, numbers, tone, slang, profanity, and explicit adult dialogue.

            ADDITIONAL USER TRANSLATION INSTRUCTIONS:
            $userPrompt
        """.trimIndent()
    }

    /** Removes "(no translation)"-style placeholders a model wrote instead of a subtitle. */
    fun stripPlaceholders(text: String): String =
        PLACEHOLDER.replace(text, "").replace(Regex("[ \\t]{2,}"), " ").trim()

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
