package com.shihab.diplay.host

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

/**
 * Bridges the Coagent head unit's steering-wheel keys to CarPlay while the CarPlay screen is in
 * front, the way the OEM tool does it: it turns on the framework's key-test mode
 * (`coagent_settings_key_test_mode`), which intercepts the wheel and broadcasts the raw key
 * names on `com.coagent.intent.action.KEY_CHANGED_TEST_MODE`, then maps the names to CarPlay
 * actions. Volume and SRC keys keep their native (MCU-side) switching behaviour and are
 * deliberately left unmapped.
 */
object SwcCarPlayKeys {
    private const val TAG = "DiPlay-SwcKeys"
    private const val KEY_TEST_ACTION = "com.coagent.intent.action.KEY_CHANGED_TEST_MODE"
    private const val KEY_TEST_EXTRA = "Key_code"
    private const val KEY_TEST_SETTING = "coagent_settings_key_test_mode"

    /** Same-name presses inside this window are framework repeats; run each key once. */
    private const val REPEAT_WINDOW_MILLIS = 1000L

    private var receiver: Receiver? = null

    /** The CarPlay action for [name], or null when the key keeps its native function. */
    internal fun actionFor(name: String?): SwcCarPlayAction? = when (name?.trim()?.uppercase()) {
        "NEXT" -> SwcCarPlayAction.TRACK_NEXT
        "PRE" -> SwcCarPlayAction.TRACK_PREV
        "TEL" -> SwcCarPlayAction.ANSWER_CALL
        "HANDUP" -> SwcCarPlayAction.END_CALL
        "VR" -> SwcCarPlayAction.SIRI
        else -> null
    }

    /** Enables key-test interception and starts mapping wheel keys. Call from onStart. */
    fun start(activity: Activity, controller: () -> CarPlayController?) {
        if (receiver != null) return
        val previousMode = runCatching {
            Settings.System.getInt(activity.contentResolver, KEY_TEST_SETTING, 0)
        }.getOrDefault(0)
        // Some ROMs deny third-party writes to custom system settings; the broadcast listener
        // still works when the user enables key-test mode via the OEM tool by hand.
        val changed = runCatching {
            Settings.System.putInt(activity.contentResolver, KEY_TEST_SETTING, 1)
        }.onFailure { AppLog.w(TAG, "cannot enable key test mode; set it via the OEM tool", it) }
            .isSuccess
        val r = Receiver(previousMode, changed, controller)
        runCatching { activity.registerReceiver(r, IntentFilter(KEY_TEST_ACTION)) }
            .onFailure { AppLog.w(TAG, "register failed", it); return }
        receiver = r
    }

    /** Restores the framework's key handling and stops mapping. Call from onStop. */
    fun stop(activity: Activity) {
        val r = receiver ?: return
        receiver = null
        runCatching { activity.unregisterReceiver(r) }
            .onFailure { AppLog.w(TAG, "unregister failed", it) }
        if (r.changedSetting) {
            runCatching {
                Settings.System.putInt(activity.contentResolver, KEY_TEST_SETTING, r.previousMode)
            }
        }
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
            val now = SystemClock.elapsedRealtime()
            if (name == lastName && now - lastAt < REPEAT_WINDOW_MILLIS) return
            lastName = name
            lastAt = now
            val action = actionFor(name) ?: return
            val c = controller() ?: return
            when (action) {
                SwcCarPlayAction.TRACK_NEXT -> c.sendMediaButton(CarPlayMediaButton.NEXT)
                SwcCarPlayAction.TRACK_PREV -> c.sendMediaButton(CarPlayMediaButton.PREVIOUS)
                SwcCarPlayAction.ANSWER_CALL -> c.answerCall()
                SwcCarPlayAction.END_CALL -> c.endCall()
                SwcCarPlayAction.SIRI -> c.requestSiri()
            }
            AppLog.i(TAG, "wheel $name -> $action")
        }
    }
}
