package com.shihab.diplay.host

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.shihab.diplay.airplay.CarPlayMediaButton
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.util.AppLog

/** CarPlay action for a steering-wheel key name. */
enum class SwcCarPlayAction {
    TRACK_NEXT,
    TRACK_PREV,
    ANSWER_CALL,
    END_CALL,
    SIRI,
}

/** What to do with one steering-wheel key name after key-test mode has swallowed it. */
enum class SwcWheelOutcome {
    TRACK_NEXT,
    TRACK_PREV,
    ANSWER_CALL,
    END_CALL,
    SIRI,
    /**
     * Volume up. Key-test mode skips the firmware's volume interrupt, so this drives the head
     * unit's settings service directly (same path as the firmware, one step per press).
     */
    VOLUME_UP,
    /**
     * Volume down. See [VOLUME_UP].
     */
    VOLUME_DOWN,
    /**
     * Mute toggle. See [VOLUME_UP].
     */
    MUTE,
    /**
     * Tap the map-view control. SRC is consumed here instead of being returned to the head unit.
     */
    MAP_VIEW,
    /**
     * Not a CarPlay key and not a volume key. Re-broadcast the original KEY_CHANGED notice so the
     * head unit's own consumers (e.g. WT_MusicKeyManager, which listens only to KEY_CHANGED, not
     * to KEY_CHANGED_TEST_MODE) act on it and the original function fires without a second press.
     */
    RELEASE,
}

/**
 * Takes steering-wheel keys while the CarPlay screen is in front.
 *
 * `com.coagent.intent.action.KEY_CHANGED` is only a notice. The head unit has already handled
 * the key by the time it arrives, so listening there cannot stop volume, mode or source.
 * Key-test mode (`coagent_settings_key_test_mode`) is the switch that swallows the press and
 * reports it as `KEY_CHANGED_TEST_MODE`. Mapped names, including the voice key, go to the
 * session. Volume on its own is the head unit's: short or long, it changes the head-unit volume.
 * With SRC held, one volume press steps the CarPlay map zoom once — a modifier gesture, never a
 * timed hold that runs away. SRC released without a volume press still taps the map-view control.
 * Every other unmapped name is re-broadcast as the original KEY_CHANGED so the head unit's own
 * consumers act on it.
 * Generic steering does not use this listener.
 */
object SwcCarPlayKeys {
    private const val TAG = "DiPlay-SwcKeys"
    private const val KEY_TEST_ACTION = HeadUnitKeyEvents.ACTION_KEY_TEST
    private const val KEY_CHANGED_ACTION = HeadUnitKeyEvents.ACTION_KEY_CHANGED
    private const val KEY_TEST_EXTRA = HeadUnitKeyEvents.EXTRA_CODE
    private const val KEY_STATE_EXTRA = HeadUnitKeyEvents.EXTRA_STATE
    private const val KEY_SOURCE_EXTRA = "Key_Source"
    private const val KEY_TEST_SETTING = HeadUnitKeyEvents.KEY_TEST_SETTING

    /** Same-name presses inside this window are framework repeats; run each mapped key once. */
    private const val REPEAT_WINDOW_MILLIS = 1000L
    /**
     * 按住 SRC 的有效窗口。音量键在这个窗口内点按时算缩放，超出则算单独按音量交给车机。
     * 这个窗口也兜住只报 DOWN、不报 UP 的固件：窗口过去后 SRC 仍按单击处理。
     */
    internal const val SRC_HOLD_WINDOW_MILLIS = 2_000L

    private var receiver: Receiver? = null
    private var appContext: Context? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** The CarPlay action for [name], or null when the key is not sent to the session. */
    internal fun actionFor(name: String?): SwcCarPlayAction? = when (name?.trim()?.uppercase()) {
        "NEXT" -> SwcCarPlayAction.TRACK_NEXT
        "PRE" -> SwcCarPlayAction.TRACK_PREV
        "TEL" -> SwcCarPlayAction.ANSWER_CALL
        "HANDUP" -> SwcCarPlayAction.END_CALL
        "VR" -> SwcCarPlayAction.SIRI
        else -> null
    }

    /** SRC 按住时音量键缩地图；单独按音量仍交给车机处理。 */
    internal fun volumePressIsZoom(srcHeld: Boolean): Boolean = srcHeld

    /** Mapped keys go to CarPlay. Volume keys drive the head unit volume; everything else is released. */
    internal fun outcomeFor(name: String?): SwcWheelOutcome? {
        when (actionFor(name)) {
            SwcCarPlayAction.TRACK_NEXT -> return SwcWheelOutcome.TRACK_NEXT
            SwcCarPlayAction.TRACK_PREV -> return SwcWheelOutcome.TRACK_PREV
            SwcCarPlayAction.ANSWER_CALL -> return SwcWheelOutcome.ANSWER_CALL
            SwcCarPlayAction.END_CALL -> return SwcWheelOutcome.END_CALL
            SwcCarPlayAction.SIRI -> return SwcWheelOutcome.SIRI
            null -> Unit
        }
        when (name?.trim()?.uppercase()) {
            "VOLUP", "KEY_SWC_VOL_INC" -> return SwcWheelOutcome.VOLUME_UP
            "VOLDOWN", "KEY_SWC_VOL_DEC" -> return SwcWheelOutcome.VOLUME_DOWN
            "MUTE" -> return SwcWheelOutcome.MUTE
        }
        when (name?.trim()?.uppercase()) {
            "SRC", "KEY_SWC_SRC" -> return SwcWheelOutcome.MAP_VIEW
        }
        name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return SwcWheelOutcome.RELEASE
    }

    /** True while this process is swallowing wheel keys for the CarPlay screen. */
    internal fun isHolding(): Boolean = receiver != null

    /**
     * Turns key-test mode off. The settings write happens before anything else so a crash
     * handler can still clear the switch if the main looper is already failing.
     */
    fun forceOff(context: Context) {
        runCatching { Settings.System.putInt(context.contentResolver, KEY_TEST_SETTING, 0) }
            .onFailure { AppLog.w(TAG, "cannot disable key test mode", it) }
    }

    /** Enables key-test interception and starts mapping wheel keys. Call from onStart. */
    fun start(activity: Activity, controller: () -> CarPlayController?) {
        if (receiver != null) return
        HeadUnitVolume.attachDebug(activity.applicationContext)
        runCatching {
            Settings.System.putInt(activity.contentResolver, KEY_TEST_SETTING, 1)
        }.onFailure { AppLog.w(TAG, "cannot enable key test mode", it) }
        val r = Receiver(controller)
        val filter = IntentFilter(KEY_TEST_ACTION).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY }
        runCatching { activity.registerReceiver(r, filter) }
            .onFailure { AppLog.w(TAG, "register failed", it); return }
        appContext = activity.applicationContext
        receiver = r
        // A sticky guard is restarted by the system after this process dies, and that restart
        // clears the switch. A Java crash also clears it, but a native kill does not run that handler.
        armGuard(activity.applicationContext)
    }

    /**
     * Stops mapping and turns key-test mode off. Always writes 0: a sudden end can leave the
     * switch at 1, and the next start would otherwise remember that 1 and restore it.
     */
    fun stop(activity: Activity) {
        val r = receiver
        receiver = null
        appContext = null
        if (r != null) {
            runCatching { activity.unregisterReceiver(r) }
                .onFailure { AppLog.w(TAG, "unregister failed", it) }
        }
        forceOff(activity)
        disarmGuard(activity.applicationContext)
        r?.cancelHold()
        GenericSwcKeys.cancelHold()
    }

    private fun armGuard(context: Context) {
        runCatching {
            context.startService(Intent(context, SwcKeyTestGuardService::class.java))
        }.onFailure { AppLog.w(TAG, "key test guard did not start", it) }
    }

    private fun disarmGuard(context: Context) {
        runCatching {
            context.stopService(Intent(context, SwcKeyTestGuardService::class.java))
        }
    }

    /**
     * For an unmapped key (volume, source, ...), re-broadcast the received test-mode notice as
     * the original `KEY_CHANGED` broadcast so the head unit's consumers (WT_MusicKeyManager and
     * similar, which register only for KEY_CHANGED) act on it and the original function fires
     * without a second press. The key-test switch stays on for mapped keys, so this process
     * keeps intercepting wheel keys for the CarPlay screen.
     */
    private fun rebroadcastAsKeyChanged(received: Intent) {
        val context = appContext ?: return
        val fwd = Intent(KEY_CHANGED_ACTION).apply {
            val code = received.getStringExtra(KEY_TEST_EXTRA)
            val state = received.getStringExtra(KEY_STATE_EXTRA)
            val source = received.getIntExtra(KEY_SOURCE_EXTRA, -1)
            if (code != null) putExtra(KEY_TEST_EXTRA, code)
            if (state != null) putExtra(KEY_STATE_EXTRA, state)
            if (source >= 0) putExtra(KEY_SOURCE_EXTRA, source)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching { context.sendBroadcast(fwd) }
            .onFailure { AppLog.w(TAG, "rebroadcast KEY_CHANGED failed", it) }
    }

    private class Receiver(
        private val controller: () -> CarPlayController?,
    ) : BroadcastReceiver() {
        private var lastName: String? = null
        private var lastAt = 0L
        private var volumeName: String? = null
        /** 本次音量按压是否已作为缩放用掉；UP 到来时就不再改车机音量。 */
        private var volumeAsZoom = false
        /** SRC 按下的时刻；0 表示未按住。 */
        private var srcDownAt = 0L
        /** SRC 这一轮按住期间已经缩放过，抬起时不再切地图视图。 */
        private var srcConsumedByZoom = false
        private var srcFallback: Runnable? = null

        fun cancelHold() {
            srcFallback?.let { handler.removeCallbacks(it) }
            srcFallback = null
            volumeName = null
            volumeAsZoom = false
            srcDownAt = 0L
            srcConsumedByZoom = false
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != KEY_TEST_ACTION) return
            val name = intent.getStringExtra(KEY_TEST_EXTRA) ?: return
            val outcome = outcomeFor(name) ?: return
            val state = intent.getStringExtra(KEY_STATE_EXTRA)
            val now = SystemClock.elapsedRealtime()
            val isVolume = outcome == SwcWheelOutcome.VOLUME_UP ||
                outcome == SwcWheelOutcome.VOLUME_DOWN ||
                outcome == SwcWheelOutcome.MUTE
            if (isVolume) {
                handleVolume(outcome, name, state, now)
                AppLog.i(TAG, "wheel $name/$state -> $outcome")
                return
            }
            if (outcome == SwcWheelOutcome.MAP_VIEW) {
                handleSrc(name, state, now)
                return
            }
            if (name == lastName && now - lastAt < REPEAT_WINDOW_MILLIS) return
            lastName = name
            lastAt = now
            when (outcome) {
                SwcWheelOutcome.RELEASE -> rebroadcastAsKeyChanged(intent)
                else -> {
                    val c = controller() ?: return
                    when (outcome) {
                        SwcWheelOutcome.TRACK_NEXT -> c.sendMediaButton(CarPlayMediaButton.NEXT)
                        SwcWheelOutcome.TRACK_PREV -> c.sendMediaButton(CarPlayMediaButton.PREVIOUS)
                        SwcWheelOutcome.ANSWER_CALL -> c.answerCall()
                        SwcWheelOutcome.END_CALL -> c.endCall()
                        SwcWheelOutcome.SIRI -> c.requestSiri()
                        else -> return
                    }
                }
            }
            AppLog.i(TAG, "wheel $name -> $outcome")
        }

        /**
         * Firmware maps mKeystate 0/1/2 to NONE/DOWN/UP.
         *
         * 音量单独按：交给车机调音量（长按也一样，不再有按住即连续缩放）。
         * 按住 SRC 时按音量：这一次按压算缩放一步，抬起后不再调音量。
         */
        private fun handleVolume(outcome: SwcWheelOutcome, name: String, state: String?, now: Long) {
            val down = state == "DOWN" || state == "NONE"
            val up = state == "UP" || state == "LONG_UP" || state == "LONG"
            if (down) {
                if (volumeName == name) return
                volumeName = name
                volumeAsZoom = false
                if (outcome == SwcWheelOutcome.MUTE) {
                    HeadUnitVolume.toggleMute()
                    return
                }
                if (srcHolding(now)) {
                    volumeAsZoom = true
                    srcConsumedByZoom = true
                    controller()?.zoomMap(outcome == SwcWheelOutcome.VOLUME_UP)
                }
                return
            }
            if (!up || volumeName != name) return
            volumeName = null
            if (volumeAsZoom) {
                volumeAsZoom = false
                return
            }
            if (outcome == SwcWheelOutcome.VOLUME_UP) HeadUnitVolume.volumeUp()
            else HeadUnitVolume.volumeDown()
        }

        /**
         * SRC 是修饰键：按住时音量键缩地图；单独抬起（期间没缩放过）仍切一次地图视图。
         * 按下只记录时刻、抬起才切视图，避免长按 SRC 时画面先跳一下。
         */
        private fun handleSrc(name: String, state: String?, now: Long) {
            val pressed = state == "DOWN" || state == "NONE"
            if (pressed) {
                if (srcDownAt != 0L) return
                srcDownAt = now
                srcConsumedByZoom = false
                srcFallback?.let { handler.removeCallbacks(it) }
                // 只报 DOWN 不报 UP 的固件：窗口过去后仍按单击处理，不让 SRC 卡死在按住状态。
                val fallback = Runnable {
                    srcFallback = null
                    if (srcDownAt != 0L && !srcConsumedByZoom) {
                        srcDownAt = 0L
                        controller()?.cycleMapView()
                    }
                }
                srcFallback = fallback
                handler.postDelayed(fallback, SRC_HOLD_WINDOW_MILLIS)
                return
            }
            srcFallback?.let { handler.removeCallbacks(it) }
            srcFallback = null
            val wasHeld = srcDownAt != 0L
            srcDownAt = 0L
            val consumed = srcConsumedByZoom
            srcConsumedByZoom = false
            if (wasHeld && !consumed) controller()?.cycleMapView()
        }

        private fun srcHolding(now: Long): Boolean =
            srcDownAt != 0L && now - srcDownAt <= SRC_HOLD_WINDOW_MILLIS
    }
}
