// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterScreenTest {
    private val centre = ClusterCandidate(displayId = 0, widthPixels = 1024, heightPixels = 600)
    private val cluster = ClusterCandidate(displayId = 2, widthPixels = 800, heightPixels = 480)

    @Test fun noSecondaryDisplaySelectsNothing() {
        assertNull(ClusterScreen.select(defaultDisplayId = 0, router = null, presentation = emptyList()))
        assertNull(ClusterScreen.select(defaultDisplayId = 0, router = centre, presentation = listOf(centre)))
        assertNull(ClusterScreen.select(defaultDisplayId = 0, router = null, presentation = listOf(centre)))
    }

    @Test fun aStripOnAnotherDisplayIsNotACluster() {
        val strip = ClusterCandidate(displayId = 3, widthPixels = 400, heightPixels = 40)
        assertNull(ClusterScreen.select(defaultDisplayId = 0, router = strip, presentation = listOf(strip)))
    }

    @Test fun routerDisplayIsUsedWhenItIsNotTheCentreScreen() {
        val chosen = ClusterScreen.select(defaultDisplayId = 0, router = cluster, presentation = emptyList())
        assertEquals(cluster, chosen)
    }

    @Test fun categoryDisplayIsUsedWhenTheRouterHasNoPresentationDisplay() {
        val chosen = ClusterScreen.select(defaultDisplayId = 0, router = null, presentation = listOf(centre, cluster))
        assertEquals(cluster, chosen)
    }

    @Test fun routerWinsOverAnotherPresentationDisplay() {
        val other = ClusterCandidate(displayId = 4, widthPixels = 640, heightPixels = 480)
        val chosen = ClusterScreen.select(defaultDisplayId = 0, router = cluster, presentation = listOf(other))
        assertEquals(cluster, chosen)
    }

    @Test fun centreRouterFallsThroughToARealCluster() {
        val chosen = ClusterScreen.select(defaultDisplayId = 0, router = centre, presentation = listOf(cluster))
        assertEquals(cluster, chosen)
    }

    @Test fun streamKeepsAModestPanelAndCapsAWideOne() {
        assertEquals(800 to 480, ClusterScreen.streamPixels(800, 480))
        assertEquals(1280 to 480, ClusterScreen.streamPixels(1920, 720))
        assertEquals(800 to 480, ClusterScreen.streamPixels(801, 481))
        assertNull(ClusterScreen.streamPixels(1, 480))
    }

    @Test fun decisionNamesTheRefusalOnASingleScreen() {
        assertEquals(
            "router has no presentation display; category empty; arrows only",
            ClusterScreen.decision(defaultDisplayId = 0, router = null, presentation = emptyList()),
        )
        assertEquals(
            "router rejected: centre id=0 1024x600; category rejected: centre id=0 1024x600; arrows only",
            ClusterScreen.decision(defaultDisplayId = 0, router = centre, presentation = listOf(centre)),
        )
    }

    @Test fun decisionNamesAShortPanelAndAChosenRouter() {
        val strip = ClusterCandidate(displayId = 3, widthPixels = 400, heightPixels = 40)
        assertEquals(
            "router rejected: below 160px id=3 400x40; category empty; arrows only",
            ClusterScreen.decision(defaultDisplayId = 0, router = strip, presentation = emptyList()),
        )
        assertEquals(
            "router id=2 800x480",
            ClusterScreen.decision(defaultDisplayId = 0, router = cluster, presentation = emptyList()),
        )
        assertEquals(
            "category id=2 800x480",
            ClusterScreen.decision(defaultDisplayId = 0, router = centre, presentation = listOf(cluster)),
        )
    }

    @Test fun advertisedClusterIsTheMapAndDoesNotTakeInput() {
        val config = ClusterScreen.streamConfig(cluster, fps = 30)
        assertEquals(800, config?.widthPixels)
        assertEquals(480, config?.heightPixels)
        assertEquals(30, config?.fps)
        assertEquals(0, config?.features)
        assertEquals(ClusterScreen.MAP_URL, config?.initialUrl)
        assertNull(ClusterScreen.streamConfig(ClusterCandidate(9, 1, 1), fps = 30))
    }
}
