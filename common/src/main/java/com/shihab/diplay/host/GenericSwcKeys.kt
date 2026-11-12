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
 * Volume on its own is the head unit's: pressing it changes the head-unit volume. Double-tapping
 * the learned map-view key arms the CarPlay map zoom, and the next volume presses step it once
 * per press — the same gesture [SwcCarPlayKeys] uses, so both steering modes behave alike.
 * Holding that key cannot be used as a modifier because the head unit turns a long press into a
 * screen-off, which the KEY_CHANGED notice cannot prevent.
 */
object GenericSwcKeys {
    private const val TAG = "DiPlay-SwcKeys"

    private var volumeId = ""
    /** 本次音量按压是否已作为缩放用掉；抬起时就不再改车机音量。 */
    private var volumeAsZoom = false
    /** 地图视图键按下的时刻；0 表示未按住。 */
    private var srcDownAt = 0L
    /** 待执行的单击切视图；第二击落在双击窗口内时取消。 */
    private var srcPendingTap: Runnable? = null
    /** 双击地图视图键之后的缩放态有效期；0 表示未进入缩放态。 */
    private var zoomArmedUntil = 0L
    /** 本次地图视图键按下是否属于一次双击。 */
    private var srcDoubleTapped = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }
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
        if (outcome == SwcWheelOutcome.MAP_VIEW) {
            handleMapViewKey(down, up, controller)
            return true
        }
        if (!down || repeatCount != 0) return true
        when (outcome) {
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

    /**
     * 地图视图键：单击切视图，双击进入缩放态。
     *
     * 长按会被车机当成息屏键，所以这里刻意不用按住做修饰。
     */
    private fun handleMapViewKey(down: Boolean, up: Boolean, controller: CarPlayController?) {
        val now = SystemClock.elapsedRealtime()
        if (down) {
            if (srcDownAt != 0L) return
            srcDownAt = now
            val pending = srcPendingTap
            if (pending != null) {
                handler.removeCallbacks(pending)
                srcPendingTap = null
                srcDoubleTapped = true
                zoomArmedUntil = now + SwcCarPlayKeys.ZOOM_ARMED_MILLIS
                AppLog.i(TAG, "map-view key double tap -> map zoom armed")
                return
            }
            srcDoubleTapped = false
            return
        }
        if (!up || srcDownAt == 0L) return
        srcDownAt = 0L
        if (srcDoubleTapped) {
            srcDoubleTapped = false
            return
        }
        val tap = Runnable {
            srcPendingTap = null
            controller?.cycleMapView()
        }
        srcPendingTap = tap
        handler.postDelayed(tap, SwcCarPlayKeys.SRC_DOUBLE_TAP_MILLIS)
    }

    fun cancelHold() {
        volumeId = ""
        volumeAsZoom = false
        srcDownAt = 0L
        srcDoubleTapped = false
        zoomArmedUntil = 0L
        srcPendingTap?.let { handler.removeCallbacks(it) }
        srcPendingTap = null
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
            volumeAsZoom = false
            if (outcome == SwcWheelOutcome.MUTE) {
                HeadUnitVolume.toggleMute()
                return
            }
            if (SwcCarPlayKeys.volumePressIsZoom(zoomArmed(now))) {
                volumeAsZoom = true
                zoomArmedUntil = now + SwcCarPlayKeys.ZOOM_ARMED_MILLIS
                controller?.zoomMap(outcome == SwcWheelOutcome.VOLUME_UP)
            }
            return
        }
        if (!up || volumeId != holdId) return
        volumeId = ""
        if (volumeAsZoom) {
            volumeAsZoom = false
            return
        }
        if (outcome == SwcWheelOutcome.VOLUME_UP) HeadUnitVolume.volumeUp()
        else HeadUnitVolume.volumeDown()
    }

    private fun zoomArmed(now: Long): Boolean = zoomArmedUntil > now
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
