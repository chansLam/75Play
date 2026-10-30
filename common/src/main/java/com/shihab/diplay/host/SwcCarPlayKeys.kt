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
     * Not a CarPlay key. The framework already swallowed this press; test mode is opened
     * again only after a short gap so a later press can reach the head unit. Volume and
     * source (SRC) both take this path so the head unit keeps them.
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
 * session. Volume and source (SRC) drop test mode so the head unit's own path is open
 * for the next press.
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

    /** Mapped keys go to CarPlay. Volume, source and every other name are released. */
    internal fun outcomeFor(name: String?): SwcWheelOutcome? {
        when (actionFor(name)) {
            SwcCarPlayAction.TRACK_NEXT -> return SwcWheelOutcome.TRACK_NEXT
            SwcCarPlayAction.TRACK_PREV -> return SwcWheelOutcome.TRACK_PREV
            SwcCarPlayAction.ANSWER_CALL -> return SwcWheelOutcome.ANSWER_CALL
            SwcCarPlayAction.END_CALL -> return SwcWheelOutcome.END_CALL
            SwcCarPlayAction.SIRI -> return SwcWheelOutcome.SIRI
            null -> Unit
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
        runCatching { handler.removeCallbacks(rearmTestMode) }
    }

    /** Enables key-test interception and starts mapping wheel keys. Call from onStart. */
    fun start(activity: Activity, controller: () -> CarPlayController?) {
        if (receiver != null) return
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
        handler.removeCallbacks(rearmTestMode)
        val r = receiver
        receiver = null
        appContext = null
        if (r != null) {
            runCatching { activity.unregisterReceiver(r) }
                .onFailure { AppLog.w(TAG, "unregister failed", it) }
        }
        forceOff(activity)
        disarmGuard(activity.applicationContext)
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

    private class Receiver(
        private val controller: () -> CarPlayController?,
    ) : BroadcastReceiver() {
        private var lastName: String? = null
        private var lastAt = 0L

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != KEY_TEST_ACTION) return
            val name = intent.getStringExtra(KEY_TEST_EXTRA) ?: return
            val outcome = outcomeFor(name) ?: return
            val now = SystemClock.elapsedRealtime()
            val once = outcome != SwcWheelOutcome.RELEASE
            if (once && name == lastName && now - lastAt < REPEAT_WINDOW_MILLIS) return
            if (once) {
                lastName = name
                lastAt = now
            }
            when (outcome) {
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
