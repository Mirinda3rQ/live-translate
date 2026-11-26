package com.livetranslate.app.live

/**
 * Pure backpressure rule for the translation queue (no Android dependencies, unit-testable).
 *
 * Translation runs one chunk at a time. If it is slower than the audio, chunks pile up and the
 * subtitles drift further and further behind the video. A chunk that waited longer than
 * [MAX_QUEUE_AGE_MS] is already too stale to be useful as a live subtitle, so it is skipped.
 */
object BacklogPolicy {
    const val MAX_QUEUE_AGE_MS = 10_000L

    fun shouldDrop(ageMs: Long, maxAgeMs: Long = MAX_QUEUE_AGE_MS): Boolean = ageMs > maxAgeMs
}
