// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.view.MotionEvent
import android.view.View
import com.shihab.diplay.CarPlayHostActivity
import com.shihab.diplay.media.CarPlayTouchMapper
import com.shihab.diplay.util.AppLog

/**
 * Multi-finger return swipe and CarPlay touch mapping for the Host gesture overlay.
 * Keeps [CarPlayHostActivity.onHostTouch] thin while preserving KitKat letterbox / swipe behavior.
 */
internal class HostTouchGestures(private val activity: CarPlayHostActivity) {

    fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (activity.systemAppsOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activity.gestureSequenceActive = false
                activity.gestureTracking = false
                // Some head-unit panels report every finger on the first DOWN, with no POINTER_DOWN.
                if (beginReturnSwipe(event)) return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (beginReturnSwipe(event)) return true
            }
        }

        if (activity.gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                event.pointerCount != activity.gestureFingerCount
            ) {
                activity.gestureTracking = false
            }
            if (!activity.gestureTracking || event.pointerCount != activity.gestureFingerCount) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    activity.gestureSequenceActive = false
                    activity.gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    activity.gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - activity.gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - activity.gestureStartY
                val swipeDistance = activity.dp(CarPlayHostActivity.SETTINGS_SWIPE_DISTANCE_DP)
                if (deltaY >= swipeDistance &&
                    deltaY >= deltaX * CarPlayHostActivity.SETTINGS_SWIPE_DIRECTION_RATIO
                ) {
                    // Swipe down with N fingers: return to the DiPlay shell (not Host settings).
                    activity.gestureSequenceActive = false
                    activity.gestureTracking = false
                    activity.returnToDiPlay()
                    return true
                }
                if (deltaY <= -swipeDistance &&
                    -deltaY >= deltaX * CarPlayHostActivity.SETTINGS_SWIPE_DIRECTION_RATIO
                ) {
                    // Swipe up with N fingers: briefly reveal hidden system bars, else back to DiPlay.
                    activity.gestureSequenceActive = false
                    activity.gestureTracking = false
                    if (!activity.showTemporarySystemBars()) FloatingBall.backToDiPlay(activity)
                    return true
                }
            }
            return true
        }

        val content = activity.contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            activity.touchOutsideContent = !content.contains(event.x, event.y)
        }
        if (activity.touchOutsideContent) {
            // Ignore the entire touch sequence when it starts in a letterbox bar.
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                activity.touchOutsideContent = false
            }
            return true
        }
        val contacts = CarPlayTouchMapper.contacts(event, content)
        val queued = activity.controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> AppLog.i(
                CarPlayHostActivity.TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    /** Start a return swipe once [CarPlayHostActivity.gestureFingerCount] fingers are down. */
    private fun beginReturnSwipe(event: MotionEvent): Boolean {
        if (activity.gestureSequenceActive || event.pointerCount != activity.gestureFingerCount) {
            return false
        }
        activity.gestureSequenceActive = true
        activity.gestureTracking = true
        activity.gestureStartX = pointerCentroid(event, horizontal = true)
        activity.gestureStartY = pointerCentroid(event, horizontal = false)
        activity.controller?.sendTouch(emptyList())
        activity.appendLog(
            "DiPlay return swipe tracking started; fingers=${activity.gestureFingerCount}",
        )
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }
}
