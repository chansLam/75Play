package com.shihab.diplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Android 8 notification channel. Loaded only when [com.shihab.diplay.compat.realSdkInt] is at least 26.
 */
internal object OreoConnectNotification {
    private const val CHANNEL_ID = "carplay_connect"

    @JvmStatic
    fun builder(context: Context): Notification.Builder {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "CarPlay", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
        return Notification.Builder(context, CHANNEL_ID)
    }
}
