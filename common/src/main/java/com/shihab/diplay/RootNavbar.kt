// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.util.AppLog

/**
 * Root-level "hide the system navigation bar globally". Sets the property the Android system
 * reads to decide whether a navigation bar exists (qemu.hw.mainkeys=1 means "no nav bar"),
 * then restarts the display / SystemUI stack so the change applies immediately and stays
 * hidden across every app until the property is reset. Requires a rooted head unit with a
 * working "su".
 *
 * OEM launcher docks (e.g. Changan / 氢桌面 left dock) are a separate window and may not
 * honour this flag — use the left-dock safe-area inset for those.
 *
 * CS75 (Freescale / KitKat) notes:
 *  - KitKat re-reads mainkeys when surfaceflinger / SystemUI restart; am force-stop alone
 *    often leaves the old NavigationBar up. Prefer killall surfaceflinger (screen blinks once).
 *  - Verify with getprop before treating apply as success.
 */
object RootNavbar {
    const val TAG = "DiPlay-RootNavbar"

    fun hiddenRequested(context: Context): Boolean = AirPlayPersistence.loadRootHideNavbar(context)

    /** True when a usable "su" responds with uid 0 (root). */
    fun available(): Boolean = RootShell.available()

    /** Hides (or restores) the navigation bar at the system level. Runs on a background thread. */
    fun applyHidden(hidden: Boolean): Boolean = runCatching {
        val su = RootShell.findSu()
        if (su == null) {
            AppLog.w(TAG, "applyHidden skipped: no usable su binary found")
            return@runCatching false
        }
        val prop = if (hidden) "1" else "0"
        // setprop → restart display stack (KitKat) → print getprop for verification.
        // Do not force-stop the OEM launcher; that only disrupts the home shell and does not
        // rebuild NavigationBar. policy_control is Lollipop+; ignore failures on KitKat.
        val script = buildString {
            append("setprop qemu.hw.mainkeys $prop; ")
            append("setprop persist.sys.mainkeys $prop; ")
            // Classic Freescale / KitKat path: surfaceflinger respawn reloads SystemUI + nav.
            append("killall surfaceflinger >/dev/null 2>&1 || ")
            append("busybox killall surfaceflinger >/dev/null 2>&1 || true; ")
            append("killall systemui >/dev/null 2>&1 || ")
            append("busybox killall systemui >/dev/null 2>&1 || true; ")
            append("killall S311WT_SystemUI >/dev/null 2>&1 || ")
            append("busybox killall S311WT_SystemUI >/dev/null 2>&1 || true; ")
            append("am force-stop com.android.systemui >/dev/null 2>&1 || true; ")
            append("am force-stop com.changan.systemui >/dev/null 2>&1 || true; ")
            append("sleep 2; ")
            append("am startservice -n com.android.systemui/.SystemUIService >/dev/null 2>&1 || true; ")
            if (hidden) {
                append("settings put global policy_control immersive.navigation=* >/dev/null 2>&1 || true; ")
            } else {
                append("settings delete global policy_control >/dev/null 2>&1 || true; ")
            }
            append("getprop qemu.hw.mainkeys")
        }
        val out = RootShell.exec(su, script, timeoutMs = 15_000L) ?: run {
            AppLog.w(TAG, "applyHidden: su script timed out or failed to start (su=$su)")
            return@runCatching false
        }
        val value = out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        val ok = value == prop
        if (ok) {
            AppLog.i(TAG, "navbar hidden=$hidden applied via su=$su (getprop=$value)")
        } else {
            AppLog.w(TAG, "navbar hidden=$hidden NOT verified via su=$su (getprop='$value', out='$out')")
        }
        ok
    }.getOrDefault(false)

    /**
     * Enables root-hidden navigation bar: triggers the su authorisation and applies the hide.
     * Returns false when su was denied or no working root exists, so the caller can fall back
     * to the disabled state.
     */
    fun enable(): Boolean = runCatching {
        if (RootShell.findSu() == null) return@runCatching false
        applyHidden(true)
    }.getOrDefault(false)
}
