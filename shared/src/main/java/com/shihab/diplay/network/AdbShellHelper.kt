package com.shihab.diplay.network

import android.content.Context
import com.shihab.diplay.util.AppLog

/** Minimal ADB shell runner retained for [WifiScanPause] on API 29+. */
internal class AdbShellHelper(private val tag: String) {
    fun run(context: Context, command: String): String {
        AppLog.d(tag, "adb shell skipped on CS75 Plus: $command")
        return ""
    }

    fun close() = Unit
}
