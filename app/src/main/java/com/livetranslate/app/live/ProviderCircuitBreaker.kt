package com.livetranslate.app.live

/**
 * Small in-memory circuit breaker for a translation provider.
 *
 * It opens after consecutive failures and allows a new trial after the cooldown.
 * A successful request closes and resets the circuit. All methods are synchronized
 * because audio callbacks and coroutine continuations may touch the same instance.
 */
class ProviderCircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var consecutiveFailures = 0
    private var openUntilMs = 0L

    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(cooldownMs > 0L) { "cooldownMs must be positive" }
    }

    @Synchronized
    fun allowRequest(): Boolean {
        if (openUntilMs == 0L) return true
        if (clockMs() < openUntilMs) return false
        // Half-open: permit a trial request. A failure will open it again.
        openUntilMs = 0L
        return true
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        openUntilMs = 0L
    }

    @Synchronized
    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            openUntilMs = clockMs() + cooldownMs
        }
    }

    @Synchronized
    fun remainingCooldownMs(): Long =
        if (openUntilMs == 0L) 0L else (openUntilMs - clockMs()).coerceAtLeast(0L)

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 2
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}
