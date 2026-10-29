package com.shihab.diplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Day/night on this Coagent head unit. The dashboard writes [SETTING] when the
 * headlights change (`NightTheme` / `DaytimeTheme`) and broadcasts
 * [ACTION_THEME_CHANGE]. Android `uiMode` usually does not move with the lights.
 */
internal object HeadlightTheme {
    const val SETTING = "coagent_theme"
    const val NIGHT = "NightTheme"
    const val DAY = "DaytimeTheme"
    const val ACTION_THEME_CHANGE = "coagent.action.THEME_CHANGE"
    const val ACTION_THEME_CHANGE_SHORT = "com.coagent.themechange"

    fun nightOrNull(theme: String?): Boolean? = when (theme?.trim()) {
        NIGHT -> true
        DAY -> false
        else -> null
    }

    fun read(context: Context): String? = runCatching {
        Settings.System.getString(context.contentResolver, SETTING)
    }.getOrNull()
}

/**
 * Night state for "follow system": the headlight theme when the head unit has
 * written one, otherwise the explicit `uiMode` night bit. Undefined `uiMode`
 * with no headlight theme stays null so callers can keep the previous state.
 */
internal fun Context.followSystemNight(
    uiMode: Int = resources.configuration.uiMode,
): Boolean? = HeadlightTheme.nightOrNull(HeadlightTheme.read(this)) ?: nightModeOrNull(uiMode)

/**
 * Re-reads the headlight theme when the head unit writes the setting or broadcasts
 * a theme change. [start] from onResume, [stop] from onPause. The callback runs
 * on the main thread.
 */
internal class HeadlightThemeWatch(
    private val context: Context,
    private val onChanged: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = onChanged()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = onChanged()
    }
    private var started = false

    fun start() {
        if (started) return
        started = true
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(HeadlightTheme.SETTING),
                false,
                observer,
            )
        }
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(HeadlightTheme.ACTION_THEME_CHANGE).apply {
                    addAction(HeadlightTheme.ACTION_THEME_CHANGE_SHORT)
                },
            )
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        runCatching { context.unregisterReceiver(receiver) }
    }
}
