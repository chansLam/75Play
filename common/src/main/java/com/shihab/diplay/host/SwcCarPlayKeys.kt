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
     * Not a CarPlay key. Re-broadcast the original KEY_CHANGED notice so the head unit's own
     * consumers (e.g. WT_MusicKeyManager, which listens only to KEY_CHANGED, not to
     * KEY_CHANGED_TEST_MODE) act on it and the original function fires without a second press.
     * Volume, source (SRC) and every other name take this path.
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
    private const val KEY_CHANGED_ACTION = "com.coagent.intent.action.KEY_CHANGED"
    private const val KEY_TEST_EXTRA = "Key_code"
    private const val KEY_STATE_EXTRA = "Key_state"
    private const val KEY_SOURCE_EXTRA = "Key_Source"
    private const val KEY_TEST_SETTING = "coagent_settings_key_test_mode"

    /** Same-name presses inside this window are framework repeats; run each mapped key once. */
    private const val REPEAT_WINDOW_MILLIS = 1000L

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
    }
}
