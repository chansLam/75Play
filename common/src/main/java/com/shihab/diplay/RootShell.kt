// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import java.io.File
import com.shihab.diplay.compat.waitForMillis
import com.shihab.diplay.util.AppLog

/**
 * Shared root (`su`) probe and short command runner for KitKat head units.
 * Only accepts a binary that actually elevates to uid=0.
 *
 * Do not call [available] / [findSu] just to decide whether to show a settings row —
 * executing `su` pops the OEM root grant dialog. Use [suBinaryPresent] for UI gating,
 * or [peekSu] when a prior grant in this process is enough.
 */
object RootShell {
    private const val TAG = "DiPlay-RootShell"

    private val SU_PATHS = arrayOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/su/xbin/su",
        "/system/su",
        "/system/bin/.su",
        "/system/xbin/.su",
        "/system/xbin/kingroot",
    )

    @Volatile
    private var cachedSu: String? = null

    /**
     * True when a known absolute-path su binary exists on disk.
     * Does **not** execute su, so it will not trigger a root-authorization prompt.
     */
    fun suBinaryPresent(): Boolean = SU_PATHS.any { path ->
        path != "su" && File(path).exists()
    }

    /** Cached su from a prior successful elevation in this process, or null. Never executes. */
    fun peekSu(): String? = cachedSu

    /** True when a usable su responds with uid 0. Executes su (may prompt for grant). */
    fun available(): Boolean = findSu() != null

    /**
     * First su path that elevates to uid=0, or null.
     * Caches a successful path for the process lifetime so later calls do not re-prompt.
     */
    fun findSu(): String? {
        cachedSu?.let { return it }
        for (path in SU_PATHS) {
            if (path != "su" && !File(path).exists()) continue
            if (isRootSu(path)) {
                cachedSu = path
                return path
            }
        }
        return null
    }

    /** Drop the cached path after a failed elevation (e.g. binary missing / spawn failure). */
    fun clearCache() {
        cachedSu = null
    }

    /**
     * Run [command] under su `-c`. Merges stderr so a full error pipe cannot hang the child.
     * Returns trimmed combined output, or null on timeout / start failure.
     *
     * Timeouts do **not** clear the su cache — a slow command is not a revoked grant.
     * Cache is cleared only when the su process cannot be started.
     */
    fun exec(command: String, timeoutMs: Long = 3_000L): String? {
        val su = findSu() ?: return null
        return exec(su, command, timeoutMs)
    }

    fun exec(su: String, command: String, timeoutMs: Long): String? = runCatching {
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitForMillis(timeoutMs)
        if (!finished) {
            process.destroy()
            AppLog.w(TAG, "exec timed out after ${timeoutMs}ms (su=$su)")
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText() }.trim()
    }.getOrElse {
        AppLog.w(TAG, "exec failed to start (su=$su): ${it.message}")
        if (cachedSu == su) clearCache()
        null
    }

    /** Start a long-running root process (caller owns lifecycle / stdout). */
    fun start(command: String): Process? {
        val su = findSu() ?: return null
        return runCatching {
            ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
        }.getOrElse {
            AppLog.w(TAG, "start failed: ${it.message}")
            if (cachedSu == su) clearCache()
            null
        }
    }

    private fun isRootSu(path: String): Boolean = runCatching {
        val process = ProcessBuilder(path, "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitForMillis(3_000L)
        if (!finished) {
            process.destroy()
            return@runCatching false
        }
        process.inputStream.bufferedReader().use { it.readText() }.trim().contains("uid=0")
    }.getOrDefault(false)
}
