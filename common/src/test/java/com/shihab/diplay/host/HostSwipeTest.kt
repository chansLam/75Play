// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import org.junit.Assert.assertEquals
import org.junit.Test

class HostSwipeTest {
    private val distance = 40f
    private val ratio = 1.05f

    @Test fun leftSwipeFliesTheClusterMap() {
        assertEquals(HostSwipe.FlyCluster, hostSwipe(deltaX = -80f, deltaY = 10f, distance, ratio))
    }

    @Test fun downSwipeReturnsToTheShell() {
        assertEquals(HostSwipe.ReturnToShell, hostSwipe(deltaX = 10f, deltaY = 80f, distance, ratio))
    }

    @Test fun upSwipeRevealsTheBars() {
        assertEquals(HostSwipe.RevealBars, hostSwipe(deltaX = -10f, deltaY = -80f, distance, ratio))
    }

    @Test fun aShortMoveStaysPending() {
        assertEquals(HostSwipe.Pending, hostSwipe(deltaX = -10f, deltaY = 4f, distance, ratio))
    }
}
