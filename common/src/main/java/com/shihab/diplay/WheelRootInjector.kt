// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import android.content.Context
import android.content.SharedPreferences
import com.shihab.diplay.host.CarPlayBackgroundSession
import com.shihab.diplay.host.R
import com.shihab.diplay.util.AppLog

/**
 * Root-injection fallback for the steering-wheel voice key.
 *
 * The CS75 head-unit shell consumes wheel key events before they reach the accessibility
 * input filter (WheelKeyService sees 0 events), so the standard filter-key path cannot see
 * them. On a rooted unit we instead read the raw input device stream with `getevent` (which
 * bypasses the shell entirely) and, when the user presses the learned steering-wheel voice
 * key, inject an Android KEYCODE_VOICE_ASSIST (231). CarPlayHostActivity's dispatchKeyEvent
 * treats that as the Siri key and asks CarPlay to open Siri.
 *
 * Injection only fires while a CarPlay session is active, so the key keeps its normal car
 * behaviour everywhere else.
 */
object WheelRootInjector {
    private const val TAG = "DiPlay-WheelRoot"
    private const val PREFS = "diplay"
    private const val K_LEARNED = "wheel_root_inject_learned"
    private const val K_ENABLED = "wheel_root_inject_enabled"
    private const val VOICE_ASSIST_ANDROID_KEYCODE = 231
    private const val LEARN_TIMEOUT_MS = 30_000L
    private const val INJECT_DEBOUNCE_MS = 800L
    private const val REOPEN_BACKOFF_MS = 1_500L

    /** getevent -l: `/dev/input/event2: EV_KEY KEY_VOICECOMMAND DOWN` */
    private val LABELED_DOWN = Regex(
        """(/dev/input/event\d+)\s*:\s*EV_KEY\s+(\S+)\s+DOWN""",
    )
    /** raw getevent: type 0001 (EV_KEY), value 00000001 (DOWN) */
    private val HEX_DOWN = Regex(
        """(/dev/input/event\d+)\s*:\s*0001\s+([0-9a-fA-F]+)\s+00000001\b""",
    )

    @Volatile private var running = false
    @Volatile private var process: Process? = null
    @Volatile private var lastInjectAt = 0L
    private val lock = Any()

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when `su` can elevate to uid 0 (a rooted head unit). May prompt for grant. */
    fun hasRoot(): Boolean = RootShell.available()

    /** Inject KEYCODE_VOICE_ASSIST (231) into the focused window. */
    fun injectVoiceAssist(): Boolean =
        RootShell.exec(
            "input keyevent $VOICE_ASSIST_ANDROID_KEYCODE",
            timeoutMs = 2_000L,
        ) != null

    /**
     * Learn the steering-wheel voice key: watch `getevent` until the first EV_KEY DOWN arrives,
     * persist it as "<device path>|<linux key name or hex code>", and report the result.
     */
    fun learn(context: Context, onResult: (String) -> Unit) {
        val app = context.applicationContext
        Thread({
            // Learning owns getevent; pause the watcher so the two do not fight over `process`.
            stop()
            val result = runCatching {
                if (!hasRoot()) {
                    return@runCatching app.getString(R.string.wheel_learn_failed, "no root")
                }
                val p = openGetevent()
                    ?: return@runCatching app.getString(R.string.wheel_learn_failed, "getevent")
                synchronized(lock) { process = p }
                try {
                    val learned = readFirstKeyDown(p, LEARN_TIMEOUT_MS)
                    if (learned != null) {
                        prefs(app).edit().putString(K_LEARNED, learned).apply()
                        val key = learned.substringAfter('|', learned)
                        AppLog.i(TAG, "learned wheel key $learned")
                        app.getString(R.string.wheel_learn_done, key)
                    } else {
                        app.getString(R.string.wheel_learn_timeout)
                    }
                } finally {
                    destroyGetevent(p)
                    synchronized(lock) {
                        if (process === p) process = null
                    }
                }
            }.getOrElse { app.getString(R.string.wheel_learn_failed, it.message ?: "") }
            // Resume watcher if the user already had injection enabled.
            if (isEnabled(app) && learned(app) != null) {
                start(app)
            }
            onResult(result)
        }, "wheel-root-learn").apply { isDaemon = true }.start()
    }

    fun learned(context: Context): String? = prefs(context).getString(K_LEARNED, null)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, false)

    /**
     * Enable or disable injection. Returns false when enabling but the watcher could not start
     * (no root / getevent), so the caller can revert the switch and prefs.
     *
     * Synchronized against [start]/[stop] so an in-flight enable cannot leave the watcher
     * running after a concurrent disable.
     */
    fun setEnabled(context: Context, on: Boolean): Boolean = synchronized(lock) {
        if (!on) {
            prefs(context).edit().putBoolean(K_ENABLED, false).apply()
            stopLocked()
            return true
        }
        if (learned(context) == null) return false
        prefs(context).edit().putBoolean(K_ENABLED, true).apply()
        if (!startLocked(context)) {
            prefs(context).edit().putBoolean(K_ENABLED, false).apply()
            return false
        }
        // A concurrent disable may have cleared the pref while we blocked in openGetevent.
        if (!prefs(context).getBoolean(K_ENABLED, false)) {
            stopLocked()
            return false
        }
        return true
    }

    /**
     * Continuously watch getevent; inject VOICE_ASSIST when the learned key is pressed during CarPlay.
     * Returns false if getevent could not be started (caller should treat as not enabled).
     */
    fun start(context: Context): Boolean = synchronized(lock) { startLocked(context) }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running = false
        process?.let { destroyGetevent(it) }
        process = null
    }

    private fun startLocked(context: Context): Boolean {
        if (running) return true
        val token = learned(context) ?: return false
        if (!token.contains('|')) return false
        // Do not call hasRoot() up front — that double-prompts. openGetevent → findSu once.
        val first = openGetevent() ?: return false
        running = true
        process = first
        Thread({
            var current: Process? = first
            try {
                while (running) {
                    val p = current ?: run {
                        current = openGetevent()
                        if (current == null) {
                            try {
                                Thread.sleep(REOPEN_BACKOFF_MS)
                            } catch (_: InterruptedException) {
                                return@run null
                            }
                            return@run null
                        }
                        synchronized(lock) { process = current }
                        current
                    }
                    if (p == null) {
                        if (!running) break
                        try {
                            Thread.sleep(REOPEN_BACKOFF_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                    try {
                        val reader = p.inputStream.bufferedReader()
                        while (running) {
                            val line = reader.readLine() ?: break
                            val hit = parseKeyDown(line) ?: continue
                            val matched = "${hit.first}|${hit.second}"
                            if (matched != token) continue
                            if (!CarPlayBackgroundSession.hasSession()) continue
                            val now = System.currentTimeMillis()
                            if (now - lastInjectAt < INJECT_DEBOUNCE_MS) continue
                            lastInjectAt = now
                            AppLog.i(TAG, "inject VOICE_ASSIST for $token")
                            injectVoiceAssist()
                        }
                    } finally {
                        destroyGetevent(p)
                        synchronized(lock) {
                            if (process === p) process = null
                        }
                        current = null
                    }
                    if (running) {
                        try {
                            Thread.sleep(REOPEN_BACKOFF_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            } finally {
                synchronized(lock) {
                    running = false
                    process = null
                }
            }
        }, "wheel-root-watch").apply { isDaemon = true }.start()
        return true
    }

    /**
     * Prefer labeled `getevent -l` (KEY_ names). Fall back to raw hex dump when -l is missing
     * on older toolboxes.
     */
    private fun openGetevent(): Process? =
        RootShell.start("getevent -l 2>/dev/null || getevent")

    private fun destroyGetevent(p: Process) {
        runCatching { p.destroy() }
    }

    private fun readFirstKeyDown(p: Process, timeoutMs: Long): String? {
        val watchdog = Thread({
            try {
                Thread.sleep(timeoutMs)
                destroyGetevent(p)
            } catch (t: InterruptedException) {
                // learning finished early
                AppLog.w(TAG, "wheel root learning watchdog interrupted", t)
            }
        }, "wheel-root-learn-watchdog").also {
            it.isDaemon = true
            it.start()
        }
        try {
            val reader = p.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: return null
                val hit = parseKeyDown(line) ?: continue
                return "${hit.first}|${hit.second}"
            }
        } finally {
            watchdog.interrupt()
        }
    }

    /** Visible for unit tests: device path + key token from one getevent line. */
    internal fun parseKeyDown(line: String): Pair<String, String>? {
        LABELED_DOWN.find(line)?.let { m ->
            return m.groupValues[1] to m.groupValues[2]
        }
        HEX_DOWN.find(line)?.let { m ->
            return m.groupValues[1] to m.groupValues[2].lowercase()
        }
        return null
    }
}
