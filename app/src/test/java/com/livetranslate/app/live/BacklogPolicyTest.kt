package com.livetranslate.app.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BacklogPolicyTest {
    @Test
    fun keepsFreshChunks() {
        assertFalse(BacklogPolicy.shouldDrop(0L))
        assertFalse(BacklogPolicy.shouldDrop(3_000L))
    }

    @Test
    fun keepsChunkExactlyAtTheLimit() {
        assertFalse(BacklogPolicy.shouldDrop(BacklogPolicy.MAX_QUEUE_AGE_MS))
    }

    @Test
    fun dropsChunksOlderThanTheLimit() {
        assertTrue(BacklogPolicy.shouldDrop(BacklogPolicy.MAX_QUEUE_AGE_MS + 1))
        assertTrue(BacklogPolicy.shouldDrop(60_000L))
    }

    @Test
    fun honoursACustomLimit() {
        assertTrue(BacklogPolicy.shouldDrop(2_001L, maxAgeMs = 2_000L))
        assertFalse(BacklogPolicy.shouldDrop(2_000L, maxAgeMs = 2_000L))
    }
}
