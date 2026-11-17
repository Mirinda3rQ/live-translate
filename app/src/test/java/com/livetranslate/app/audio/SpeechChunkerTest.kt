package com.livetranslate.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    private val frame = ByteArray(3_200) // 100 ms

    private fun SpeechChunker.speak(frames: Int): List<ByteArray> =
        (1..frames).flatMap { offer(frame, audible = true) }

    private fun SpeechChunker.pause(frames: Int): List<ByteArray> =
        (1..frames).flatMap { offer(frame, audible = false) }

    @Test
    fun cutsAtPauseAfterEnoughSpeech() {
        val c = SpeechChunker()
        assertTrue(c.speak(20).isEmpty())          // 2 s of speech, no cut yet
        assertTrue(c.pause(3).isEmpty())           // 0.3 s pause is not enough
        val chunks = c.pause(1)                    // 0.4 s pause ends the phrase
        assertEquals(1, chunks.size)
        assertEquals(20 * 3_200, chunks[0].size)
    }

    @Test
    fun shortUtteranceWaitsForLongPause() {
        val c = SpeechChunker()
        c.speak(8)                                 // 0.8 s
        assertTrue(c.pause(9).isEmpty())
        assertEquals(1, c.pause(1).size)           // 1.0 s of silence flushes it
    }

    @Test
    fun tooShortNoiseIsNeverFlushed() {
        val c = SpeechChunker()
        c.speak(3)                                 // 0.3 s
        assertTrue(c.pause(30).isEmpty())
    }

    @Test
    fun continuousSpeechIsCappedAtMax() {
        val c = SpeechChunker()
        val chunks = c.speak(50)                   // 5 s without a pause
        assertEquals(1, chunks.size)
        assertEquals(128_000, chunks[0].size)
    }

    @Test
    fun resetDropsPendingAudio() {
        val c = SpeechChunker()
        c.speak(20)
        c.reset()
        assertTrue(c.pause(10).isEmpty())
    }
}
