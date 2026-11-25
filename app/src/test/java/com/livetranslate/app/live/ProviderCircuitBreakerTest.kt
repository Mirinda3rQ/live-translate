package com.livetranslate.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCircuitBreakerTest {
    @Test
    fun opensAfterConfiguredConsecutiveFailures() {
        val breaker = ProviderCircuitBreaker(failureThreshold = 2, cooldownMs = 1_000L)
        assertTrue(breaker.allowRequest())
        breaker.recordFailure()
        assertTrue(breaker.allowRequest())
        breaker.recordFailure()
        assertFalse(breaker.allowRequest())
        assertEquals(1_000L, breaker.remainingCooldownMs())
    }

    @Test
    fun successResetsFailureCountAndClosesCircuit() {
        val breaker = ProviderCircuitBreaker(failureThreshold = 2, cooldownMs = 1_000L)
        breaker.recordFailure()
        breaker.recordSuccess()
        breaker.recordFailure()
        assertTrue(breaker.allowRequest())
    }

    @Test
    fun permitsTrialAfterCooldown() {
        var now = 10_000L
        val breaker = ProviderCircuitBreaker(
            failureThreshold = 1,
            cooldownMs = 500L,
            clockMs = { now },
        )
        breaker.recordFailure()
        assertFalse(breaker.allowRequest())
        now += 499L
        assertFalse(breaker.allowRequest())
        now += 1L
        assertTrue(breaker.allowRequest())
        assertEquals(0L, breaker.remainingCooldownMs())
    }

    @Test
    fun failureDuringTrialReopensCircuit() {
        var now = 0L
        val breaker = ProviderCircuitBreaker(1, 100L) { now }
        breaker.recordFailure()
        now = 100L
        assertTrue(breaker.allowRequest())
        breaker.recordFailure()
        assertFalse(breaker.allowRequest())
        assertEquals(100L, breaker.remainingCooldownMs())
    }
}
