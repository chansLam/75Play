package com.shihab.diplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class AospRfcommPlanTest {
    @Test
    fun cachedServiceTriesInsecureBeforeTheLongSecureLookup() {
        val steps = AospRfcommPlan.steps(serviceCached = true)
        assertEquals(AospRfcommPlan.Method.INSECURE, steps[0].method)
        assertEquals(12_000L, steps[0].timeoutMillis)
        assertEquals(AospRfcommPlan.Method.SECURE, steps[1].method)
        assertEquals(AospRfcommPlan.Method.CHANNEL, steps[2].method)
        assertEquals(6_000L, steps[2].timeoutMillis)
    }

    @Test
    fun coldServiceKeepsTheKitKatBudgetOnTheFirstLookup() {
        val steps = AospRfcommPlan.steps(serviceCached = false)
        assertEquals(
            listOf(
                AospRfcommPlan.Method.SECURE,
                AospRfcommPlan.Method.INSECURE,
                AospRfcommPlan.Method.CHANNEL,
            ),
            steps.map { it.method },
        )
        assertEquals(18_000L, steps[0].timeoutMillis)
        assertEquals(8_000L, steps[1].timeoutMillis)
        assertEquals(6_000L, steps[2].timeoutMillis)
    }
}
