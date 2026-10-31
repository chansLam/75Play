// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import android.content.Context
import android.content.Intent
import com.shihab.diplay.glance.CarPlayGlance
import com.shihab.diplay.glance.CarPlayGlance.Snapshot
import com.shihab.diplay.util.AppLog

/**
 * Forwards CarPlay next-turn guidance to the instrument cluster (仪表盘) exactly the way the
 * OEM Tencent navigation does, so the head-unit's CoagentService turns it into CAN commands
 * and renders the turn arrow / speed limit / road name on the dash cluster.
 *
 * The Tencent S311 navigation (`com.tencent.wecarnavi`) sends a plain broadcast
 * `WECARNAVIAUTO_STANDARD_BROADCAST_SEND` with `KEY_TPYE` (+ alias `KEY_TYPE`) = 2000/2002 and
 * the `rg_on_*` extras. CoagentService.RunService's TencentNaviReceiver listens for it, then
 * forwards to the dash MCU via McuSendCmd. We replicate that sender one-to-one; the broadcast is
 * exported and open, so **no system permission is required**.
 *
 * The data comes from [CarPlayGlance] (the same next-turn source the BYD HUD widget uses);
 * the maneuver type is mapped **directly from Apple's RouteGuidanceManeuverType to the Tencent
 * turnType** value space (see [turnCode]) — there is deliberately no BYD Gaode hop here, the
 * Changan CoagentService consumes `rg_on_direction` as the Tencent turnType it expects.
 *
 * NOTE: only turn/route data is sent — CarPlay cannot draw its live map onto the cluster
 * (no system addSurface path); see docs/75Play-仪表盘导航协议.md.
 */
object NaviClusterBroadcast {
    const val TAG = "DiPlay-NaviCluster"

    const val ACTION = "WECARNAVIAUTO_STANDARD_BROADCAST_SEND"

    // keyType
    const val KEY_NAV_STATE = 2000 // 导航启停（TYPE 1=开始 2/7=退出 23=模拟退出）
    const val KEY_TURN = 2002 // 转向/路线（rg_on_*）

    private const val EXTRA_KEY_TYPE = "KEY_TPYE"
    private const val EXTRA_KEY_TYPE_ALIAS = "KEY_TYPE"
    private const val EXTRA_TYPE = "TYPE"

    @Volatile
    private var attached = false

    @Volatile
    private var attachedApp: Context? = null

    private var lastSent: Snapshot? = null
    private var lastManeuver: Int? = null

    /**
     * Subscribes to [CarPlayGlance] and forwards each guidance update as a 2002 turn broadcast,
     * and connected/disconnected as 2000 start/stop. Safe to call more than once.
     */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        attachedApp = context.applicationContext
        CarPlayGlance.listener = ::onSnapshot
        // Initial state, in case a route was already active before we subscribed.
        val snap = CarPlayGlance.snapshot()
        if (snap.connected) sendTurn(app = attachedApp ?: return, snap)
    }

    /** Detaches the subscription. */
    fun detach() {
        attached = false
        attachedApp = null
        CarPlayGlance.listener = null
    }

    /** Tells the cluster the route is over: 2000 / TYPE=7 (退出导航). */
    fun clear(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(buildIntent(KEY_NAV_STATE, type = 7))
        lastSent = null
        lastManeuver = null
    }

    private fun onSnapshot(snap: Snapshot) {
        val app = attachedApp ?: return
        if (!snap.connected) {
            // CarPlay disconnected: tell the cluster the route is over.
            if (lastSent != null) {
                app.sendBroadcast(buildIntent(KEY_NAV_STATE, type = 7))
                lastSent = null
                lastManeuver = null
            }
            return
        }
        // Route ended but CarPlay is still connected: maneuverType goes null, so the dash
        // would keep the last arrow forever. Send 2000/TYPE=7 to clear it.
        if (snap.maneuverType == null) {
            if (lastSent != null) {
                app.sendBroadcast(buildIntent(KEY_NAV_STATE, type = 7))
                lastSent = null
                lastManeuver = null
            }
            return
        }
        sendTurn(app, snap)
    }

    private fun sendTurn(app: Context, snap: Snapshot) {
        val maneuver = snap.maneuverType ?: return
        if (maneuver == lastManeuver && snap == lastSent) return

        // Navigation session just started (first turn of a route): send 2000/TYPE=1
        // (NAVIGATION_START) so CoagentService clears the MCU speed-limit state via
        // McuSendCmd.sendNaviSpeedLimitInfo(10,0,0,1,1). Only the first turn of a session
        // triggers it; subsequent turns and the cleared (null maneuver) state don't.
        if (lastManeuver == null) {
            app.sendBroadcast(buildIntent(KEY_NAV_STATE, type = 1))
        }
        lastManeuver = maneuver
        lastSent = snap

        val intent = buildIntent(KEY_TURN)
        intent.putExtra("rg_on_direction", turnCode(maneuver))
        intent.putExtra("rg_on_distance", snap.distanceMeters)
        intent.putExtra("rg_on_dist_to_dest", (snap.remainingMeters ?: 0L).toInt())
        intent.putExtra("rg_on_time_to_dest", (snap.remainingSeconds ?: 0L).toInt())
        intent.putExtra("rg_on_current_road_name", snap.currentRoad)
        intent.putExtra("rg_on_next_road_name", snap.road) // the road the next maneuver turns onto
        app.sendBroadcast(intent)
        AppLog.i(TAG, "sent 2002 turn=$maneuver dist=${snap.distanceMeters} road=${snap.road}")
    }

    /**
     * Apple iAP2 RouteGuidanceManeuverType → Tencent turnType sent in `rg_on_direction`.
     *
     * ROOT CAUSE: `rg_on_direction` must carry the **Tencent turnType** value space (the same codes
     * the OEM Tencent S311 navigation sends), NOT the BYD/HUD Gaode codes. The Changan head unit's
     * CoagentService.RunService reads `rg_on_direction` and forwards it straight into the dash MCU
     * (`McuSendCmd.sendNaviInfo(road_type, dist)`, no conversion), so the dash arrow's direction is
     * decided purely by the Tencent turnType the value falls in. Decompiling S311Navigation.apk
     * (`sdk_rd_ic_turn_<n>` arrow set, which is exactly the turnType index) fixes the semantics:
     *
     *   turnType  1 = straight   2 = left   3 = right   4 = U-turn
     *            5 = slight right  6 = sharp right  7 = slight left  8 = sharp left
     *
     * Apple maneuver types are grouped the same way the HUD widget and BYD HUD group them
     * (verified on the car): left=1,20  right=2,21  sharp-left=47  sharp-right=48
     * slight-left=13,22,49,52  slight-right=14,23,50,53  U-turn=4,18,19,26
     * straight=3,5,8,9,11,51  roundabout=6,7,28..46  destination=10,12,24,25,27.
     *
     * Before this fix we mapped through the BYD Gaode code, where RIGHT=2; Changan read 2 as the
     * Tencent turnType → left arrow (observed: right turn → left arrow). We now map Apple directly
     * to the Tencent turnType with no BYD hop.
     *
     * Roundabout enhancement: Apple roundabouts (6 enter / 7 exit / 28..46 exit N) carry no
     * left-right side, and used to map to straight(1) so they rendered a plain straight arrow.
     * Tencent has dedicated roundabout turnTypes 90-93 (`routeguide/b/j.java` `b` map: 90→icon35
     * left-right-angle, 91→icon36 left-slight, 92→icon40 right-slight, 93→icon41 right-right-angle)
     * which the dash MCU renders. Since Apple gives no side, we map all roundabouts to turnType 93
     * (right-right-angle, icon41) — the most common roundabout exit direction on right-hand traffic
     * — so the cluster shows a roundabout arrow instead of a phantom straight. destination
     * (10/12/24/25/27) keeps mapping to straight(1).
     */
    private fun turnCode(maneuver: Int): Int = when (maneuver) {
        1, 20 -> 2          // Apple left → Tencent left
        2, 21 -> 3          // Apple right → Tencent right
        47 -> 8             // Apple sharp-left → Tencent sharp-left
        48 -> 6             // Apple sharp-right → Tencent sharp-right
        13, 22, 49, 52 -> 7 // Apple slight-left → Tencent slight-left
        14, 23, 50, 53 -> 5 // Apple slight-right → Tencent slight-right
        4, 18, 19, 26 -> 4  // Apple U-turn → Tencent U-turn
        3, 5, 8, 9, 11, 51 -> 1 // Apple straight
        10, 12, 24, 25, 27 -> 1 // Apple destination → straight, no arrow
        6, 7, in 28..46 -> 93   // Apple roundabout → Tencent roundabout right-exit (icon41)
        else -> 1                // unknown → straight, no phantom arrow
    }

    private fun buildIntent(keyType: Int, type: Int? = null): Intent {
        val intent = Intent(ACTION)
        intent.putExtra(EXTRA_KEY_TYPE, keyType)
        intent.putExtra(EXTRA_KEY_TYPE_ALIAS, keyType)
        if (type != null) intent.putExtra(EXTRA_TYPE, type)
        return intent
    }
}
