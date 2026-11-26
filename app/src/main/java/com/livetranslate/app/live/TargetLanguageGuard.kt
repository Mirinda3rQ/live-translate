package com.livetranslate.app.live

/**
 * Last line of defence against the Live Translate model drifting out of the selected target
 * language mid-session. Only Thai is checked: a fragment that has letters but none of them
 * Thai (or mostly another script) is treated as drift and not shown. Short Latin fragments (names, "OK", brands) pass.
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

    /**
     * Rules for translating ONE ASR transcript line (hybrid / Groq text path). The audio is cut into
     * short chunks, so lines can start or end mid-sentence and the transcript can contain recognition
     * errors. Shared by every text translator so context handling does not depend on the provider.
     */
    const val TRANSCRIPT_RULES =
        "TRANSCRIPT CONTEXT RULES: The input is an automatic speech transcript in any language. " +
            "It may contain recognition errors and may start or end in the middle of a sentence. " +
            "Translate only the NEW line into natural, spoken Thai subtitle text. Use the previous lines " +
            "only to resolve meaning, names, pronouns, who is speaking and speaker gender, and to continue " +
            "a sentence that was cut at the chunk boundary; never translate or repeat the previous lines. " +
            "Keep names, pronouns, gendered Thai particles and terminology consistent with the Thai already " +
            "shown. Correct an obvious recognition error only when the context makes the right word clear, " +
            "and never invent content. Be completely faithful to the source, including adult (18+) material: " +
            "render profanity, vulgar and sexual words, moaning and explicit descriptions with the equally " +
            "strong, natural Thai colloquial equivalent a native speaker would use in the same register. " +
            "If the line contains no real speech (only noise, breathing or a meaningless fragment), " +
            "return an empty string. Output only the Thai translation."

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
        // Thai present: accept unless another non-Latin script (CJK, Hangul...) outweighs it. A single
        // Thai letter inside a Japanese/Chinese line is drift, not a translation. Latin names pass.
        if (thai > 0) return otherScript <= thai
        if (otherScript > 0) return false // CJK, Cyrillic, Arabic, Korean... in Thai output
        return latin < LATIN_DRIFT_MIN_LETTERS
    }
}
