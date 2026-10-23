package com.shihab.diplay.session

import com.shihab.diplay.CarPlayViewAreas

/**
 * Session display descriptor.
 *
 * Moved out of the `host` package: [CarPlaySessionFactory] lives in `session` and must not depend
 * on host types. Host-side code ([com.shihab.diplay.host.CarPlayBackgroundSession],
 * [com.shihab.diplay.host.HostSessionLifecycle]) still constructs and consumes this — the dependency
 * direction is now host -> session only.
 */
internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // Compare unscaled startup window dimensions, not the scaled video canvas.
    val windowWidth: Int,
    val windowHeight: Int,
    /** The view areas this session declared, or null for the whole screen as one area. */
    val viewAreas: CarPlayViewAreas? = null,
)
