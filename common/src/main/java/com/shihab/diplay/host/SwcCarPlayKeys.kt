package com.shihab.diplay.host

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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
    VOLUME_UP,
    VOLUME_DOWN,
    /**
     * Not a CarPlay key. The framework already swallowed this press; test mode is opened
     * again only after a short gap so a later press can reach the head unit.
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
 * session. Volume is applied here because the MCU step was swallowed. Every other name drops
 * test mode so the head unit's own path is open for the next press.
 */
object SwcCarPlayKeys {
    private const val TAG = "DiPlay-SwcKeys"
    private const val KEY_TEST_ACTION = "com.coagent.intent.action.KEY_CHANGED_TEST_MODE"
    private const val KEY_TEST_EXTRA = "Key_code"
    private const val KEY_TEST_SETTING = "coagent_settings_key_test_mode"

    /** Same-name presses inside this window are framework repeats; run each mapped key once. */
    private const val REPEAT_WINDOW_MILLIS = 1000L

    /** How long an unmapped key leaves test mode off. */
    private const val PASS_THROUGH_MILLIS = 400L

    private var receiver: Receiver? = null
    private var appContext: Context? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val rearmTestMode = Runnable {
        val context = appContext ?: return@Runnable
        if (receiver == null) return@Runnable
        runCatching { Settings.System.putInt(context.contentResolver, KEY_TEST_SETTING, 1) }
    }

    /** The CarPlay action for [name], or null when the key is not sent to the session. */
    internal fun actionFor(name: String?): SwcCarPlayAction? = when (name?.trim()?.uppercase()) {
        "NEXT" -> SwcCarPlayAction.TRACK_NEXT
        "PRE" -> SwcCarPlayAction.TRACK_PREV
        "TEL" -> SwcCarPlayAction.ANSWER_CALL
        "HANDUP" -> SwcCarPlayAction.END_CALL
        "VR" -> SwcCarPlayAction.SIRI
        else -> null
    }

    /** Mapped keys go to CarPlay. Volume is adjusted locally. Every other name is released. */
    internal fun outcomeFor(name: String?): SwcWheelOutcome? {
        when (actionFor(name)) {
            SwcCarPlayAction.TRACK_NEXT -> return SwcWheelOutcome.TRACK_NEXT
            SwcCarPlayAction.TRACK_PREV -> return SwcWheelOutcome.TRACK_PREV
            SwcCarPlayAction.ANSWER_CALL -> return SwcWheelOutcome.ANSWER_CALL
            SwcCarPlayAction.END_CALL -> return SwcWheelOutcome.END_CALL
            SwcCarPlayAction.SIRI -> return SwcWheelOutcome.SIRI
            null -> Unit
        }
        val key = name?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (key) {
            "KEY_SWC_VOL_INC", "VOL_INC", "VOLUME_UP" -> SwcWheelOutcome.VOLUME_UP
            "KEY_SWC_VOL_DEC", "VOL_DEC", "VOLUME_DOWN" -> SwcWheelOutcome.VOLUME_DOWN
            else -> SwcWheelOutcome.RELEASE
        }
    }

    /** Enables key-test interception and starts mapping wheel keys. Call from onStart. */
    fun start(activity: Activity, controller: () -> CarPlayController?) {
        if (receiver != null) return
        val previousMode = runCatching {
            Settings.System.getInt(activity.contentResolver, KEY_TEST_SETTING, 0)
        }.getOrDefault(0)
        val changed = runCatching {
            Settings.System.putInt(activity.contentResolver, KEY_TEST_SETTING, 1)
        }.onFailure { AppLog.w(TAG, "cannot enable key test mode", it) }
            .isSuccess
        val r = Receiver(previousMode, changed, controller)
        val filter = IntentFilter(KEY_TEST_ACTION).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY }
        runCatching { activity.registerReceiver(r, filter) }
            .onFailure { AppLog.w(TAG, "register failed", it); return }
        appContext = activity.applicationContext
        receiver = r
    }

    /** Restores the framework's key handling and stops mapping. Call from onStop. */
    fun stop(activity: Activity) {
        handler.removeCallbacks(rearmTestMode)
        val r = receiver ?: return
        receiver = null
        appContext = null
        runCatching { activity.unregisterReceiver(r) }
            .onFailure { AppLog.w(TAG, "unregister failed", it) }
        if (r.changedSetting) {
            runCatching {
                Settings.System.putInt(activity.contentResolver, KEY_TEST_SETTING, r.previousMode)
            }
        }
    }

    /**
     * Opens the head-unit path. The press already delivered here was swallowed while the
     * setting was 1, and re-sending the test-mode broadcast does not make the framework
     * execute it. The setting is armed again after the gap.
     */
    private fun releaseToHeadUnit() {
        val context = appContext ?: return
        runCatching { Settings.System.putInt(context.contentResolver, KEY_TEST_SETTING, 0) }
        handler.removeCallbacks(rearmTestMode)
        handler.postDelayed(rearmTestMode, PASS_THROUGH_MILLIS)
    }

    private fun adjustVolume(context: Context, raise: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private class Receiver(
        val previousMode: Int,
        val changedSetting: Boolean,
        private val controller: () -> CarPlayController?,
    ) : BroadcastReceiver() {
        private var lastName: String? = null
        private var lastAt = 0L

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != KEY_TEST_ACTION) return
            val name = intent.getStringExtra(KEY_TEST_EXTRA) ?: return
            val outcome = outcomeFor(name) ?: return
            val now = SystemClock.elapsedRealtime()
            val once = outcome != SwcWheelOutcome.VOLUME_UP &&
                outcome != SwcWheelOutcome.VOLUME_DOWN &&
                outcome != SwcWheelOutcome.RELEASE
            if (once && name == lastName && now - lastAt < REPEAT_WINDOW_MILLIS) return
            if (once) {
                lastName = name
                lastAt = now
            }
            when (outcome) {
                SwcWheelOutcome.VOLUME_UP, SwcWheelOutcome.VOLUME_DOWN ->
                    adjustVolume(context, outcome == SwcWheelOutcome.VOLUME_UP)
                SwcWheelOutcome.RELEASE -> releaseToHeadUnit()
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
    }
}
