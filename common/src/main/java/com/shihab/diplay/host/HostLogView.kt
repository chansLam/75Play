// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.os.Handler
import android.view.View
import com.shihab.diplay.CarPlayHostActivity

/**
 * 调试用屏幕日志行刷新（从 CarPlayHostActivity 抽出的非核心职责）。
 *
 * 维护一个保留窗口内的日志行环形缓冲：超过 [LOG_RETENTION_MILLIS] 的旧行被丢弃，
 * 文本刷到 statusView，并把 ScrollView 滚到底。过期重排由主 Handler 调度，时序与原先一致。
 */
internal class HostLogView(
    private val activity: CarPlayHostActivity,
    private val handler: Handler,
) {
    private val logLines = ArrayDeque<LogEntry>()

    val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }

    fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        activity.statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        handler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            handler.postDelayed(expireOldLogLines, delay)
        }
    }

    fun scrollLogsToBottom() {
        activity.statusScrollView?.post {
            activity.statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private data class LogEntry(val timestampMillis: Long, val text: String)

    companion object {
        private const val LOG_RETENTION_MILLIS = 5 * 60_000L
    }
}
