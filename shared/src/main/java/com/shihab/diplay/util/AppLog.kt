package com.shihab.diplay.util

import android.util.Log

/**
 * Unified logging facade for the whole app (issue H-02).
 *
 * The project intentionally keeps `-dontoptimize` in its ProGuard config, so
 * `-assumenosideeffects` cannot strip `android.util.Log` calls from release
 * builds. Debug-level output is instead gated at the facade:
 *
 *  - [v], [d], [i] only forward to `android.util.Log` when the build is debug
 *    ([com.shihab.diplay.shared.BuildConfig.DEBUG]); release builds silently
 *    skip them.
 *  - [w], [e] always forward unchanged, so warnings and errors still show up
 *    in release logcats.
 *
 * Signatures mirror `android.util.Log` so existing call sites can be renamed
 * mechanically: `(tag, msg)` and `(tag, msg, throwable)`. Messages are never
 * rewritten, throwables are never swallowed, and nothing is written to disk.
 */
object AppLog {
    private val debug = com.shihab.diplay.shared.BuildConfig.DEBUG

    @JvmStatic
    fun v(tag: String, msg: String) {
        if (debug) Log.v(tag, msg)
    }

    @JvmStatic
    fun v(tag: String, msg: String, tr: Throwable?) {
        if (debug) Log.v(tag, msg, tr)
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        if (debug) Log.d(tag, msg)
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable?) {
        if (debug) Log.d(tag, msg, tr)
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        if (debug) Log.i(tag, msg)
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable?) {
        if (debug) Log.i(tag, msg, tr)
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable?) {
        Log.w(tag, msg, tr)
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
    }
}
