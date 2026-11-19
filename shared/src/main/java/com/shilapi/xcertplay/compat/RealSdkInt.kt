package com.shilapi.xcertplay.compat

import android.os.Build

/**
 * Returns the real API level, working around head units (e.g. Allwinner T3) that fake
 * [Build.VERSION.SDK_INT] (report 27 while [Build.VERSION.RELEASE] says 4.4.x).
 * Trust the release string when it contradicts the SDK int on the low end.
 *
 * Use this instead of [Build.VERSION.SDK_INT] for behavior branches. The faked SDK int
 * makes behavior gates take the wrong path on these ROMs (e.g. calling API 21+ USB methods
 * that don't exist on the real 4.4 framework, or attempting per-app VPN that the old
 * netd rejects).
 */
fun realSdkInt(): Int {
    val reported = Build.VERSION.SDK_INT
    val release = Build.VERSION.RELEASE ?: return reported
    // Map 4.4.x release to API 19 regardless of what SDK_INT claims.
    if (release.startsWith("4.4") && reported > 19) {
        return 19
    }
    // Map 5.x release to API 21/22.
    if (release.startsWith("5.0") && reported > 21) {
        return 21
    }
    if (release.startsWith("5.1") && reported > 22) {
        return 22
    }
    // Map 6.0 release to API 23.
    if (release.startsWith("6.0") && reported > 23) {
        return 23
    }
    return reported
}

