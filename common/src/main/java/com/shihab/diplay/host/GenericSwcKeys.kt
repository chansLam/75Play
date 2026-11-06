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
    private var volumeId = ""
    private var volumeDownAt = 0L
    private var volumeZooming = false
    private var zoomRunnable: Runnable? = null
    private var zoomController: (() -> CarPlayController?)? = null
    private val broadcastRepeatGate = SwcBroadcastRepeatGate()

    /** True when [event] was a learned key and should not reach the head unit. */
    fun dispatch(context: Context, event: KeyEvent, controller: CarPlayController?): Boolean {
        if (!AirPlayPersistence.loadGenericSwcEnabled(context)) return false
        val action = GenericSwcMap.actionForKeyCode(
            AirPlayPersistence.loadGenericSwcBindings(context),
            event.keyCode,
        ) ?: return false
        return apply(action, "k:${event.keyCode}", event.action == KeyEvent.ACTION_DOWN, event.action == KeyEvent.ACTION_UP, event.repeatCount, controller)
    }

    /**
     * A learned head-unit key name. [pressed] is the broadcast's down edge.
     * True when the name was learned.
     */
    fun dispatchName(context: Context, name: String, pressed: Boolean, controller: CarPlayController?): Boolean {
        if (!AirPlayPersistence.loadGenericSwcEnabled(context)) return false
        val action = GenericSwcMap.actionForName(
            AirPlayPersistence.loadGenericSwcBindings(context),
            name,
        ) ?: return false
        val normalizedName = name.trim().uppercase()
        if (pressed && !action.isVolume() &&
            !broadcastRepeatGate.allow(normalizedName, SystemClock.elapsedRealtime())
        ) return true
        val down = pressed
        val up = !pressed
        return apply(action, "n:$normalizedName", down, up, repeatCount = 0, controller)
    }

    private fun apply(
        action: GenericSwcAction,
        holdId: String,
        down: Boolean,
        up: Boolean,
        repeatCount: Int,
        controller: CarPlayController?,
    ): Boolean {
        val outcome = action.toOutcome()
        if (action.isVolume()) {
            handleVolume(outcome, holdId, down, up, controller)
            return true
        }
        if (!down || repeatCount != 0) return true
        when (outcome) {
            SwcWheelOutcome.MAP_VIEW -> controller?.cycleMapView()
            SwcWheelOutcome.TRACK_NEXT -> controller?.sendMediaButton(CarPlayMediaButton.NEXT)
            SwcWheelOutcome.TRACK_PREV -> controller?.sendMediaButton(CarPlayMediaButton.PREVIOUS)
            SwcWheelOutcome.ANSWER_CALL -> controller?.answerCall()
            SwcWheelOutcome.END_CALL -> controller?.endCall()
            SwcWheelOutcome.SIRI -> controller?.requestSiri()
            else -> Unit
        }
        AppLog.i(TAG, "learned key $holdId -> $outcome")
        return true
    }

    fun cancelHold() {
        zoomRunnable?.let { handler.removeCallbacks(it) }
        zoomRunnable = null
        volumeZooming = false
        volumeId = ""
        zoomController = null
    }

    fun reset() {
        cancelHold()
        broadcastRepeatGate.reset()
    }

    private fun handleVolume(
        outcome: SwcWheelOutcome,
        holdId: String,
        down: Boolean,
        up: Boolean,
        controller: CarPlayController?,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (down) {
            if (volumeId == holdId) return
            volumeId = holdId
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
        if (!up || volumeId != holdId) return
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

private fun GenericSwcAction.isVolume(): Boolean = when (this) {
    GenericSwcAction.VOLUME_UP, GenericSwcAction.VOLUME_DOWN, GenericSwcAction.MUTE -> true
    else -> false
}

internal class SwcBroadcastRepeatGate(
    private val repeatWindowMillis: Long = 1_000L,
) {
    private var lastName: String? = null
    private var lastAt = 0L

    fun allow(name: String, now: Long): Boolean {
        if (name == lastName && now - lastAt in 0 until repeatWindowMillis) return false
        lastName = name
        lastAt = now
        return true
    }

    fun reset() {
        lastName = null
        lastAt = 0L
    }
}
