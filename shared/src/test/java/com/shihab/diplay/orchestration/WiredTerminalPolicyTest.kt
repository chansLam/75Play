package com.shihab.diplay.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredTerminalPolicyTest {
    @Test
    fun liveWiredTerminalIsReportedAsFailure() {
        assertFalse(
            shouldIgnoreWiredControlTerminal(
                closed = false,
                aborted = false,
                phaseIsWired = true,
            ),
        )
    }

    @Test
    fun explicitShutdownOrPhaseChangeIgnoresTerminal() {
        assertTrue(shouldIgnoreWiredControlTerminal(true, false, true))
        assertTrue(shouldIgnoreWiredControlTerminal(false, true, true))
        assertTrue(shouldIgnoreWiredControlTerminal(false, false, false))
    }
}
