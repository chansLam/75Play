package com.shihab.diplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Head-unit Settings Activity components shared by the shell and home app grid.
 * Includes common OEM packages kept on stripped Freescale ROMs when ACTION_SETTINGS is gone.
 */
object OemSettingsIntents {
    val candidates: List<ComponentName> = listOf(
        ComponentName("com.android.settings", "com.android.settings.Settings"),
        ComponentName("com.android.settings", "com.android.settings.SettingsActivity"),
        ComponentName("com.flyaudio.settings", "com.flyaudio.settings.activity.MainActivity"),
        ComponentName("com.ww.settings", "com.ww.settings.MainActivity"),
    )

    fun firstLaunchable(context: Context, newTask: Boolean = false): Intent? {
        val pm = context.packageManager
        for (component in candidates) {
            val intent = Intent().setComponent(component)
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) != null) return intent
        }
        return null
    }
}
