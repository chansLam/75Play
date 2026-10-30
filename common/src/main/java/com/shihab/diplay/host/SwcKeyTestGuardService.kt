package com.shihab.diplay.host

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Stays started while CarPlay is swallowing steering-wheel keys.
 *
 * [Service.START_STICKY] makes the system start this process again after a crash or a native
 * kill. The new process is not holding the keys, so the restarted service turns key-test mode
 * off and exits. No notification: foreground-service notifications are an API 26 requirement,
 * and this target is KitKat.
 */
class SwcKeyTestGuardService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (SwcCarPlayKeys.isHolding()) return START_STICKY
        SwcCarPlayKeys.forceOff(this)
        stopSelf()
        return START_NOT_STICKY
    }
}
