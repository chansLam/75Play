package com.shihab.diplay.host

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.util.AppLog

/**
 * While generic steering is on and CarPlay is in front, swallow wheel keys the way this car's
 * built-in path does, then apply the names the driver learned.
 *
 * Learning itself listens to [HeadUnitKeyEvents.ACTION_KEY_CHANGED] without this switch.
 * The switch is only on here so a learned key is not also handled by the head unit.
 * A name that was not learned is re-broadcast as the original KEY_CHANGED.
 */
object GenericSwcBroadcast {
    private const val TAG = "DiPlay-SwcKeys"

    private var receiver: Receiver? = null

    internal fun isHolding(): Boolean = receiver != null

    fun start(activity: Activity, controller: () -> CarPlayController?) {
        HeadUnitVolume.attachDebug(activity.applicationContext)
        if (receiver != null) {
            runCatching {
                Settings.System.putInt(activity.contentResolver, HeadUnitKeyEvents.KEY_TEST_SETTING, 1)
            }
            return
        }
        runCatching {
            Settings.System.putInt(activity.contentResolver, HeadUnitKeyEvents.KEY_TEST_SETTING, 1)
        }.onFailure { AppLog.w(TAG, "cannot enable key test mode", it) }
        val next = Receiver(activity.applicationContext, controller)
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            addAction(HeadUnitKeyEvents.ACTION_KEY_TEST)
            HeadUnitKeyEvents.learnActions().forEach { action ->
                if (action != HeadUnitKeyEvents.ACTION_KEY_CHANGED && action != HeadUnitKeyEvents.ACTION_KEY_TEST) {
                    addAction(action)
                }
            }
        }
        runCatching { activity.registerReceiver(next, filter) }
            .onFailure {
                AppLog.w(TAG, "register failed", it)
                SwcCarPlayKeys.forceOff(activity)
                return
            }
        receiver = next
        runCatching {
            activity.applicationContext.startService(Intent(activity, SwcKeyTestGuardService::class.java))
        }.onFailure { AppLog.w(TAG, "key test guard did not start", it) }
    }

    fun stop(activity: Activity) {
        val current = receiver
        receiver = null
        if (current != null) {
            runCatching { activity.unregisterReceiver(current) }
                .onFailure { AppLog.w(TAG, "unregister failed", it) }
            current.cancel()
        }
        if (current != null) SwcCarPlayKeys.forceOff(activity)
        if (current != null) {
            runCatching {
                activity.applicationContext.stopService(Intent(activity, SwcKeyTestGuardService::class.java))
            }
        }
    }

    private class Receiver(
        private val appContext: Context,
        private val controller: () -> CarPlayController?,
    ) : BroadcastReceiver() {
        fun cancel() = GenericSwcKeys.reset()

        override fun onReceive(context: Context, intent: Intent) {
            val press = HeadUnitKeyEvents.parse(intent) ?: return
            val handled = GenericSwcKeys.dispatchName(context, press.token, press.pressed, controller())
            if (handled && press.pressed && press.edgeOnly) {
                GenericSwcKeys.dispatchName(context, press.token, pressed = false, controller())
            }
            if (!handled && intent.action == HeadUnitKeyEvents.ACTION_KEY_TEST) rebroadcast(intent)
        }

        private fun rebroadcast(received: Intent) {
            val fwd = Intent(HeadUnitKeyEvents.ACTION_KEY_CHANGED).apply {
                val code = received.getStringExtra(HeadUnitKeyEvents.EXTRA_CODE)
                val state = received.getStringExtra(HeadUnitKeyEvents.EXTRA_STATE)
                val source = received.getIntExtra("Key_Source", -1)
                if (code != null) putExtra(HeadUnitKeyEvents.EXTRA_CODE, code)
                if (state != null) putExtra(HeadUnitKeyEvents.EXTRA_STATE, state)
                if (source >= 0) putExtra("Key_Source", source)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            }
            runCatching { appContext.sendBroadcast(fwd) }
                .onFailure { AppLog.w(TAG, "rebroadcast KEY_CHANGED failed", it) }
        }
    }
}
