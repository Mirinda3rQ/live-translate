package com.livetranslate.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperGuardsTest {
    @Test
    fun mapsWhisperLanguageNames() {
        assertEquals("ja", WhisperLanguageNames.toIso("Japanese"))
        assertEquals("zh", WhisperLanguageNames.toIso("chinese"))
        assertNull(WhisperLanguageNames.toIso("klingon"))
        assertNull(WhisperLanguageNames.toIso(null))
    }

    @Test
    fun autoDetectsUntilThreeChunksAgree() {
        val lock = WhisperLanguageLock()
        assertNull(lock.nextLanguage())
        lock.record("ja", forced = false)
        lock.record("ja", forced = false)
        assertNull(lock.nextLanguage())
        lock.record("ja", forced = false)
        assertEquals("ja", lock.currentLock())
        assertEquals("ja", lock.nextLanguage())
    }

    @Test
    fun disagreeingChunksDoNotLock() {
        val lock = WhisperLanguageLock()
        lock.record("ja", forced = false)
        lock.record("zh", forced = false)
        lock.record("ja", forced = false)
        assertNull(lock.currentLock())
        assertNull(lock.nextLanguage())
    }

    @Test
    fun redetectsPeriodicallyAndUnlocksOnLanguageChange() {
        val lock = WhisperLanguageLock(lockAfter = 3, redetectEvery = 4)
        repeat(3) { lock.record("ja", forced = false) }
        // chunks 1-3 are forced, chunk 4 is auto-detect
        assertEquals("ja", lock.nextLanguage())
        assertEquals("ja", lock.nextLanguage())
        assertEquals("ja", lock.nextLanguage())
        assertNull(lock.nextLanguage())
        // the auto-detect chunk heard Chinese: lock is released
        lock.record("zh", forced = false)
        assertNull(lock.currentLock())
        assertNull(lock.nextLanguage())
    }

    @Test
    fun forcedResultsAreIgnored() {
        val lock = WhisperLanguageLock()
        repeat(3) { lock.record("ja", forced = false) }
        lock.record("zh", forced = true) // echo of a forced language says nothing
        assertEquals("ja", lock.currentLock())
    }

    @Test
    fun resetClearsLock() {
        val lock = WhisperLanguageLock()
        repeat(3) { lock.record("ja", forced = false) }
        lock.reset()
        assertNull(lock.currentLock())
        assertNull(lock.nextLanguage())
    }

    @Test
    fun longIdleGapDropsTheLock() {
        var now = 1_000L
        val lock = WhisperLanguageLock(idleResetMs = 60_000L, clock = { now })
        repeat(3) { lock.record("ja", forced = false) }
        assertEquals("ja", lock.nextLanguage())
        now += 61_000L // paused / new video
        assertNull(lock.nextLanguage())
        assertNull(lock.currentLock())
    }

    @Test
    fun dropsKnownHallucinationsOnlyWhenWholeChunk() {
        assertTrue(WhisperHallucinations.isKnown("ご視聴ありがとうございました。"))
        assertTrue(WhisperHallucinations.isKnown("Thanks for watching!"))
        assertTrue(WhisperHallucinations.isKnown("字幕由Amara.org社区提供"))
        assertFalse(WhisperHallucinations.isKnown("ありがとうございました"))
        assertFalse(WhisperHallucinations.isKnown("ご視聴ありがとうございました、次はこちら"))
        assertFalse(WhisperHallucinations.isKnown(""))
    }
}
