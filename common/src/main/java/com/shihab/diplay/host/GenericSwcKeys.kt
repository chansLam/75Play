package com.shihab.diplay.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.airplay.CarPlayMediaButton
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.util.AppLog

/**
 * Applies learned Android key codes while generic steering-wheel mode is on.
 * A short volume press still changes the head-unit volume. Holding volume zooms the map.
 */
object GenericSwcKeys {
    private const val TAG = "DiPlay-SwcKeys"
    private const val ZOOM_REPEAT_MILLIS = 220L
    private const val ZOOM_MAX_MILLIS = 8_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var volumeCode = Int.MIN_VALUE
    private var volumeDownAt = 0L
    private var volumeZooming = false
    private var zoomRunnable: Runnable? = null
    private var zoomController: (() -> CarPlayController?)? = null

    /** True when [event] was a learned key and should not reach the head unit. */
    fun dispatch(context: Context, event: KeyEvent, controller: CarPlayController?): Boolean {
        if (!AirPlayPersistence.loadGenericSwcEnabled(context)) return false
        val action = GenericSwcMap.actionForKeyCode(
            AirPlayPersistence.loadGenericSwcBindings(context),
            event.keyCode,
        ) ?: return false
        val outcome = action.toOutcome()
        val isVolume = outcome == SwcWheelOutcome.VOLUME_UP ||
            outcome == SwcWheelOutcome.VOLUME_DOWN ||
            outcome == SwcWheelOutcome.MUTE
        if (isVolume) {
            handleVolume(outcome, event, controller)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        when (outcome) {
            SwcWheelOutcome.MAP_VIEW -> controller?.cycleMapView()
            SwcWheelOutcome.TRACK_NEXT -> controller?.sendMediaButton(CarPlayMediaButton.NEXT)
            SwcWheelOutcome.TRACK_PREV -> controller?.sendMediaButton(CarPlayMediaButton.PREVIOUS)
            SwcWheelOutcome.ANSWER_CALL -> controller?.answerCall()
            SwcWheelOutcome.END_CALL -> controller?.endCall()
            SwcWheelOutcome.SIRI -> controller?.requestSiri()
            else -> Unit
        }
        AppLog.i(TAG, "learned key ${event.keyCode} -> $outcome")
        return true
    }

    fun cancelHold() {
        zoomRunnable?.let { handler.removeCallbacks(it) }
        zoomRunnable = null
        volumeZooming = false
        volumeCode = Int.MIN_VALUE
        zoomController = null
    }

    private fun handleVolume(outcome: SwcWheelOutcome, event: KeyEvent, controller: CarPlayController?) {
        val now = SystemClock.elapsedRealtime()
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (volumeCode == event.keyCode) return
            volumeCode = event.keyCode
            volumeDownAt = now
            volumeZooming = false
            zoomRunnable?.let { handler.removeCallbacks(it) }
            zoomRunnable = null
            if (outcome == SwcWheelOutcome.MUTE) {
                HeadUnitVolume.toggleMute()
                return
            }
            zoomController = { controller }
            val startZoom = Runnable {
                volumeZooming = true
                repeatZoom(outcome, now)
            }
            zoomRunnable = startZoom
            handler.postDelayed(startZoom, SwcCarPlayKeys.VOLUME_LONG_PRESS_MILLIS)
            return
        }
        if (event.action != KeyEvent.ACTION_UP || volumeCode != event.keyCode) return
        val zoomed = volumeZooming || SwcCarPlayKeys.volumePressIsZoom(now - volumeDownAt)
        cancelHold()
        if (zoomed || outcome == SwcWheelOutcome.MUTE) return
        if (outcome == SwcWheelOutcome.VOLUME_UP) HeadUnitVolume.volumeUp()
        else HeadUnitVolume.volumeDown()
    }

    private fun repeatZoom(outcome: SwcWheelOutcome, startedAt: Long) {
        if (!volumeZooming) return
        if (SystemClock.elapsedRealtime() - startedAt > ZOOM_MAX_MILLIS) {
            volumeZooming = false
            return
        }
        zoomController?.invoke()?.zoomMap(outcome == SwcWheelOutcome.VOLUME_UP)
        val next = Runnable { repeatZoom(outcome, startedAt) }
        zoomRunnable = next
        handler.postDelayed(next, ZOOM_REPEAT_MILLIS)
    }
}
