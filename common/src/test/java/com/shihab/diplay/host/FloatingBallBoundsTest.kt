// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingBallBoundsTest {
    @Test fun realScreenReachesThePhysicalEdges() {
        val (width, height) = floatingBallScreen(1920, 720, 1920, 680)
        assertEquals(1920, width)
        assertEquals(720, height)
        assertEquals(1920 - 56, floatingBallLimit(width, 56))
        assertEquals(720 - 56, floatingBallLimit(height, 56))
    }

    @Test fun missingRealScreenFallsBackToTheWindow() {
        val (width, height) = floatingBallScreen(0, 0, 1280, 720)
        assertEquals(1280, width)
        assertEquals(720, height)
    }

    @Test fun ballLargerThanTheScreenStaysAtTheOrigin() {
        assertEquals(0, floatingBallLimit(40, 56))
    }

    @Test fun releaseOnEachEdgeDocksThere() {
        val maxX = floatingBallLimit(1920, 56)
        val maxY = floatingBallLimit(720, 56)
        assertEquals(FloatingBallEdge.LEFT, floatingBallDock(0, 200, maxX, maxY, -30, 4))
        assertEquals(FloatingBallEdge.RIGHT, floatingBallDock(maxX, 200, maxX, maxY, 30, -2))
        assertEquals(FloatingBallEdge.TOP, floatingBallDock(400, 0, maxX, maxY, 3, -20))
        assertEquals(FloatingBallEdge.BOTTOM, floatingBallDock(400, maxY, maxX, maxY, -3, 20))
    }

    @Test fun releaseAwayFromTheEdgeStaysExpanded() {
        assertEquals(null, floatingBallDock(80, 400, 1864, 664, 10, -4))
    }

    @Test fun aCornerFollowsTheStrongerDrag() {
        val maxX = 1864
        val maxY = 664
        assertEquals(FloatingBallEdge.LEFT, floatingBallDock(0, 0, maxX, maxY, -40, -5))
        assertEquals(FloatingBallEdge.BOTTOM, floatingBallDock(0, maxY, maxX, maxY, -4, 30))
    }

    @Test fun tuckedFrameLeavesAnArrowOnThatEdge() {
        val screenW = 1920
        val screenH = 720
        val ball = 56
        val thick = 18
        val len = 42
        val left = floatingBallFrame(FloatingBallEdge.LEFT, 0, 180, screenW, screenH, ball, thick, len)
        val expectedTop = 180 + (ball - len) / 2
        assertEquals(FloatingBallFrame(0, expectedTop, thick, len), left)
        val right = floatingBallFrame(FloatingBallEdge.RIGHT, 1864, 180, screenW, screenH, ball, thick, len)
        assertEquals(FloatingBallFrame(screenW - thick, expectedTop, thick, len), right)
        val top = floatingBallFrame(FloatingBallEdge.TOP, 400, 0, screenW, screenH, ball, thick, len)
        val expectedLeft = 400 + (ball - len) / 2
        assertEquals(FloatingBallFrame(expectedLeft, 0, len, thick), top)
        val bottom = floatingBallFrame(FloatingBallEdge.BOTTOM, 400, 664, screenW, screenH, ball, thick, len)
        assertEquals(FloatingBallFrame(expectedLeft, screenH - thick, len, thick), bottom)
    }

    @Test fun tuckedFrameDefaultsToFullBallLength() {
        val left = floatingBallFrame(FloatingBallEdge.LEFT, 0, 180, 1920, 720, 56, 18)
        assertEquals(FloatingBallFrame(0, 180, 18, 56), left)
    }

    @Test fun expandingRestoresTheFullBall() {
        assertEquals(
            FloatingBallFrame(0, 180, 56, 56),
            floatingBallFrame(null, 0, 180, 1920, 720, 56, 48),
        )
    }

    @Test fun inwardDragLeavesTheEdge() {
        assertEquals(12f, floatingBallInward(FloatingBallEdge.LEFT, 12f, -3f))
        assertEquals(12f, floatingBallInward(FloatingBallEdge.RIGHT, -12f, 0f))
        assertEquals(8f, floatingBallInward(FloatingBallEdge.TOP, 1f, 8f))
        assertEquals(8f, floatingBallInward(FloatingBallEdge.BOTTOM, 1f, -8f))
    }
}
