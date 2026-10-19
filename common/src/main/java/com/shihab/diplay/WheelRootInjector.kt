package com.shihab.diplay

import java.util.concurrent.TimeUnit
import android.content.Context
import android.content.SharedPreferences
import com.shihab.diplay.host.CarPlayBackgroundSession
import com.shihab.diplay.host.R

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
    private const val PREFS = "diplay"
    private const val K_LEARNED = "wheel_root_inject_learned"
    private const val K_ENABLED = "wheel_root_inject_enabled"
    private const val VOICE_ASSIST_ANDROID_KEYCODE = 231

    private var running = false
    @Volatile private var process: Process? = null

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when `su` can elevate to uid 0 (a rooted head unit). */
    fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val ok = p.waitFor(3000, TimeUnit.MILLISECONDS)
        val out = if (ok) p.inputStream.bufferedReader().use { it.readText() } else ""
        if (!ok) p.destroy()
        ok && out.contains("uid=0")
    }.getOrDefault(false)

    /** Inject KEYCODE_VOICE_ASSIST (231) into the focused window. */
    fun injectVoiceAssist(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "input keyevent $VOICE_ASSIST_ANDROID_KEYCODE")
            .redirectErrorStream(true).start()
        if (!p.waitFor(2000, TimeUnit.MILLISECONDS)) { p.destroy(); return@runCatching false }
        true
    }.getOrDefault(false)

    /**
     * Learn the steering-wheel voice key: watch `getevent` until the first EV_KEY DOWN arrives,
     * persist it as "<device path>|<linux key name>", and report the human-readable result.
     */
    fun learn(context: Context, onResult: (String) -> Unit) {
        val res = context.applicationContext
        Thread {
            val result = runCatching {
                val p = ProcessBuilder("su", "-c", "getevent").redirectErrorStream(true).start()
                val r = p.inputStream.bufferedReader()
                val lineRegex = Regex("(/dev/input/event\\d+):\\s+EV_KEY\\s+(\\S+)\\s+(DOWN|UP)")
                val deadline = System.currentTimeMillis() + 30000
                var found = false
                while (System.currentTimeMillis() < deadline) {
                    val line = r.readLine() ?: break
                    val m = lineRegex.find(line) ?: continue
                    val (dev, key, act) = m.destructured
                    if (act == "DOWN") {
                        val learned = "$dev|$key"
                        prefs(context).edit().putString(K_LEARNED, learned).apply()
                        found = true
                        break
                    }
                }
                // Always tear down the long-running getevent, whether we learned a key or timed out.
                p.destroy()
                if (found) {
                    val learned = prefs(context).getString(K_LEARNED, "") ?: ""
                    val parts = learned.split("|")
                    return@runCatching res.getString(R.string.wheel_learn_done,
                        if (parts.size > 1) parts[1] else learned)
                }
                res.getString(R.string.wheel_learn_timeout)
            }.getOrElse { res.getString(R.string.wheel_learn_failed, it.message ?: "") }
            onResult(result)
        }.start()
    }

    fun learned(context: Context): String? = prefs(context).getString(K_LEARNED, null)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, on).apply()
        if (!on) stop()
        else if (learned(context) != null) start(context)
    }

    /** Continuously watch getevent; inject VOICE_ASSIST when the learned key is pressed during CarPlay. */
    fun start(context: Context) {
        if (running || !hasRoot()) return
        val learned = learned(context) ?: return
        val parts = learned.split("|")
        if (parts.size < 2) return
        val dev = parts[0]
        val key = parts[1]
        running = true
        Thread {
            runCatching {
                val p = ProcessBuilder("su", "-c", "getevent").redirectErrorStream(true).start()
                process = p
                val r = p.inputStream.bufferedReader()
                val lineRegex = Regex("$dev:\\s+EV_KEY\\s+$key\\s+DOWN")
                while (running) {
                    val line = r.readLine() ?: break
                    if (lineRegex.find(line) != null && CarPlayBackgroundSession.hasSession()) {
                        injectVoiceAssist()
                    }
                }
            }
            running = false
            process = null
        }.start()
    }

    fun stop() {
        running = false
        process?.destroy()
        process = null
    }
}
