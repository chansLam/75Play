package com.shihab.diplay

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/**
 * Android 8 notification channel. Loaded only when [com.shihab.diplay.compat.realSdkInt] is at least 26.
 */
internal object OreoConnectNotification {
    private const val CHANNEL_ID = "carplay_connect"
    private const val IMPORTANCE_LOW = 2

    @JvmStatic
    fun builder(context: Context): Notification.Builder {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelClass = Class.forName("android.app.NotificationChannel")
        val channel = channelClass.getConstructor(String::class.java, CharSequence::class.java, Int::class.javaPrimitiveType)
            .newInstance(CHANNEL_ID, "CarPlay", IMPORTANCE_LOW)
        NotificationManager::class.java.getMethod("createNotificationChannel", channelClass)
            .invoke(manager, channel)
        return Notification.Builder::class.java
            .getConstructor(Context::class.java, String::class.java)
            .newInstance(context, CHANNEL_ID)
    }
}
