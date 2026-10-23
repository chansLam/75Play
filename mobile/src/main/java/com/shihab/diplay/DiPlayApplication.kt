package com.shihab.diplay

import android.content.Context
import android.os.Build
import com.shihab.diplay.compat.realSdkInt
import androidx.multidex.MultiDexApplication
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shihab.diplay.util.AppLog

class DiPlayApplication : MultiDexApplication() {

    // DIAGNOSTIC BUILD: writes an early boot marker log to /sdcard so that a crash
    // happening BEFORE the crash handler is installed (Application class load /
    // MultiDex.install in attachBaseContext) can still be located. SafeStackTrace is
    // deliberately NOT used here: this class must not pull in secondary-dex types
    // before multidex has finished loading, which could itself be the crash source.
    override fun attachBaseContext(base: Context) {
        bootLog("attach-start")
        try {
            super.attachBaseContext(base)
            bootLog("attach-after-super-ok")
        } catch (t: Throwable) {
            bootLog("attach-EXCEPTION: " + t.javaClass.name + ": " + t.message)
            try {
                val sw = StringWriter()
                sw.write(t.stackTraceToString())
                bootLog(sw.toString())
                AppLog.w(TAG, "attachBaseContext failed on " + t.javaClass.name, t)
            } catch (ignored: Throwable) {
                // Crash-recorder path: this logging must never itself crash startup — stay silent
                // (Log.w cannot throw) and rethrow the original exception below.
                AppLog.w(TAG, "bootLog stack dump failed: ${ignored.message}", ignored)
            }
            throw t
        }
    }

    override fun onCreate() {
        bootLog("oncreate-start")
        super.onCreate()
        bootLog("oncreate-after-super")
        setupCrashHandler()
        bootLog("handler-registered")
        // Apply the root-level "hide system navigation bar globally" on cold start when enabled.
        // Background thread + runCatching so a missing / failing su can never crash startup.
        if (RootNavbar.hiddenRequested(this)) {
            bootLog("rootnavbar-requested-true")
            Thread(
                {
                    runCatching { RootNavbar.applyHidden(true) }
                    bootLog("rootnavbar-done")
                },
                "root-navbar",
            ).apply { isDaemon = true }.start()
        } else {
            bootLog("rootnavbar-requested-false")
        }
        bootLog("oncreate-done")
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(this, thread, throwable)
            } catch (ignored: Throwable) {
                // Never re-enter a crashing handler or throw here — the default handler still
                // runs in finally, so the process dies exactly as it would without us.
                AppLog.w(TAG, "recordCrash failed on ${thread.name}: ${ignored.message}", ignored)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /** Writes a marker line to /sdcard (and app-private dir) without touching secondary-dex APIs. */
    private fun bootLog(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + msg + "\n"
        try {
            val f = File("/sdcard/diplay-boot.log")
            if (f.exists() && f.length() > 64 * 1024) f.delete()
            f.appendText(line)
        } catch (ignored: Throwable) {
            // Boot marker is best-effort diagnostics; missing /sdcard must not affect startup.
            AppLog.w(TAG, "boot-log sdcard write failed: ${ignored.message}")
        }
        try {
            File(applicationContext.filesDir, "diplay-boot.log").appendText(line)
        } catch (ignored: Throwable) {
            // Private-dir write fallback above already covers the common case; stay silent here.
            AppLog.w(TAG, "boot-log private write failed: ${ignored.message}")
        }
    }

    companion object {
        private const val TAG = "DiPlayApplication"

        fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val stringWriter = StringWriter()
            stringWriter.write(throwable.stackTraceToString())
            val stackTrace = stringWriter.toString()

            val logEntry = buildString {
                append("\n================ FATAL EXCEPTION ================\n")
                append("Time: ").append(timestamp).append("\n")
                append("Thread: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
                append("Android SDK: ").append(Build.VERSION.SDK_INT).append("\n")
                append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
                append("Fingerprint: ").append(Build.FINGERPRINT).append("\n")
                append("Exception: ").append(stackTrace).append("\n")
                append("=================================================\n")
            }

            AppLog.e(TAG, logEntry)

            // 1. Always write to private app directory (preserved)
            runCatching {
                val logDir = File(context.filesDir, "logs")
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "diplay_crash.log")
                logFile.appendText(logEntry)
            }

            // 2. Also write to app-specific external files dir if available (accessible via MTP/file manager)
            runCatching {
                val externalDir = context.getExternalFilesDir("logs")
                if (externalDir != null) {
                    if (!externalDir.exists()) externalDir.mkdirs()
                    File(externalDir, "diplay_crash.log").appendText(logEntry)
                }
            }

            // 3. On legacy Android (API <= 28), also attempt public locations for U-disk / file manager copy.
            if (realSdkInt() <= 28) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    )
                    if (downloads != null) {
                        if (!downloads.exists()) downloads.mkdirs()
                        if (downloads.canWrite()) {
                            File(downloads, "diplay-crash.log").appendText(logEntry)
                        }
                    }
                }
                runCatching {
                    @Suppress("DEPRECATION")
                    val sdcardDir = android.os.Environment.getExternalStorageDirectory()
                    if (sdcardDir != null && sdcardDir.canWrite()) {
                        File(sdcardDir, "diplay-crash.log").appendText(logEntry)
                        File(sdcardDir, "diplay-crash.log").appendText(logEntry)
                    }
                }
            }
        }
    }
}
