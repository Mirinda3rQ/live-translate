package com.livetranslate.app.live

/**
 * Pure helpers around Groq Whisper (no Android / network dependencies, so they are unit-testable).
 *
 * Whisper auto-detects the language of every 1-3 s chunk on its own. On short or breathy chunks
 * (common in 18+ audio) it flips between Japanese / Chinese / Korean, which produces a garbled
 * transcript and therefore a "weird" Thai translation. [WhisperLanguageLock] pins the language
 * once several chunks in a row agree, and re-checks with auto-detect now and then so a real
 * language change in the video is still followed.
 */
object WhisperLanguageNames {
    private val map = mapOf(
        "japanese" to "ja",
        "chinese" to "zh",
        "korean" to "ko",
        "english" to "en",
        "thai" to "th",
        "vietnamese" to "vi",
        "indonesian" to "id",
    )

    /** Whisper `verbose_json` reports the language as an English name ("japanese"); map it to ISO 639-1. */
    fun toIso(name: String?): String? = name?.trim()?.lowercase()?.let { map[it] }
}

class WhisperLanguageLock(
    private val lockAfter: Int = 3,
    private val redetectEvery: Int = 4,
    /** A pause this long (new video / new session) drops the lock so the next chunk auto-detects. */
    private val idleResetMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val recent = ArrayDeque<String>()
    private var locked: String? = null
    private var sinceAutoDetect = 0
    private var lastTouchMs = 0L

    private fun touch() {
        val now = clock()
        if (lastTouchMs != 0L && now - lastTouchMs > idleResetMs) clear()
        lastTouchMs = now
    }

    private fun clear() {
        recent.clear()
        locked = null
        sinceAutoDetect = 0
    }

    /** Language to force on the next chunk, or null to let Whisper auto-detect. */
    @Synchronized
    fun nextLanguage(): String? {
        touch()
        val lock = locked ?: return null
        sinceAutoDetect++
        if (sinceAutoDetect >= redetectEvery) {
            sinceAutoDetect = 0
            return null // periodic auto-detect so a real language switch is noticed
        }
        return lock
    }

    /**
     * Record what Whisper reported. [forced] results only echo the language we asked for, so they
     * carry no information and are ignored; only auto-detected chunks vote.
     */
    @Synchronized
    fun record(detectedIso: String?, forced: Boolean) {
        touch()
        if (forced || detectedIso == null) return
        recent.addLast(detectedIso)
        while (recent.size > lockAfter) recent.removeFirst()
        if (locked != null && detectedIso != locked) {
            locked = null // the video changed language (or the lock was wrong): back to auto-detect
            sinceAutoDetect = 0
        }
        if (locked == null && recent.size == lockAfter && recent.all { it == detectedIso }) {
            locked = detectedIso
            sinceAutoDetect = 0
        }
    }

    @Synchronized
    fun reset() {
        clear()
        lastTouchMs = 0L
    }

    @Synchronized
    fun currentLock(): String? = locked
}

/** Phrases Whisper is known to invent on silence, breathing or music; dropped only when they are the whole chunk. */
object WhisperHallucinations {
    private val known = listOf(
        "ご視聴ありがとうございました",
        "ご視聴ありがとうございます",
        "ご視聴いただきありがとうございました",
        "チャンネル登録お願いします",
        "チャンネル登録よろしくお願いします",
        "最後までご視聴いただきありがとうございました",
        "请不吝点赞订阅转发打赏支持明镜与点点栏目",
        "字幕由amaraorg社区提供",
        "thanksforwatching",
        "thankyouforwatching",
    )

    private val strip = Regex("[\\s\\p{P}\\p{S}]+")

    private fun normalize(text: String): String = strip.replace(text, "").lowercase()

    fun isKnown(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty()) return false
        return known.any { n == it }
    }
}
