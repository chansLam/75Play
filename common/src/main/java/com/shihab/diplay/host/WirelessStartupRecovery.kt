package com.shihab.diplay.host

import com.shihab.diplay.WirelessStartupRetryBudget

/**
 * Thin holder for Host wireless-startup retry generation and budget.
 * Keeps retry counters off the Activity surface; Host still owns scheduling/UI.
 */
internal class WirelessStartupRecovery {
    val budget = WirelessStartupRetryBudget()
    @Volatile var generation: Int = 0
        private set
    @Volatile var stopped: Boolean = false

    fun bumpGeneration(): Int {
        generation += 1
        return generation
    }

    fun isStale(expected: Int): Boolean = expected != generation || stopped

    fun nextDelayMillis(): Long? = if (stopped) null else budget.nextDelayMillis()

    fun firstFrame(session: Any, nowMillis: Long): Boolean = budget.firstFrame(session, nowMillis)

    fun resetIfStable(session: Any, nowMillis: Long): Boolean = budget.resetIfStable(session, nowMillis)

    fun disconnected() = budget.disconnected()

    fun manualRetry() {
        stopped = false
        budget.manualRetry()
    }

    fun stop() {
        stopped = true
    }
}
