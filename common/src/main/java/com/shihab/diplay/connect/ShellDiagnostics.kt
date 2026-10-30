// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.connect

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import com.shihab.diplay.compat.realSdkInt
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.shihab.diplay.ActiveSessionLog
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CrashRecord
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.DiagnosticExportStore
import com.shihab.diplay.DiagnosticRedactor
import com.shihab.diplay.DisplayDiagnosticSnapshot
import com.shihab.diplay.LegacyConnectionProbe
import com.shihab.diplay.ProcessExitDiagnostics
import com.shihab.diplay.SessionLogFile
import com.shihab.diplay.StartupDiagnosticSnapshot
import com.shihab.diplay.host.CarPlayBackgroundSession
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.GlassColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shihab.diplay.util.AppLog

/**
 * 诊断报告导出与应用内预览。
 * Activity 仍持有 `export` 文档选择器（单测会反射替换该字段），这里只接 launch 回调。
 */
class ShellDiagnostics(
    private val activity: FragmentActivity,
    private val versionLabel: () -> String,
    private val setupError: () -> String?,
    private val dp: (Int) -> Int,
    private val toast: (String) -> Unit,
    private val rechoose: () -> Unit,
) {
    private var exportInProgress = false
    private var preferredReportRoot: File? = null
    private var exportButton: Button? = null

    fun chooseDestination(launchPicker: (String) -> Unit) {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        if (realSdkInt() < Build.VERSION_CODES.LOLLIPOP) {
            // Use our own USB volume picker: KitKat DocumentsUI often hides vendor mounts.
            val choices = DiagnosticExportStore.availableUsbVolumes(activity.applicationContext)
            if (choices.isEmpty()) {
                DiPlayDialog.showConfirm(
                    activity, activity.getString(R.string.save_diagnostic_report),
                    activity.getString(R.string.report_no_usb_drive),
                    activity.getString(R.string.save_diagnostic_report), activity.getString(R.string.done),
                    onConfirm = { export(null) },
                )
            } else {
                val labels = arrayOf(activity.getString(R.string.report_auto_save_locations)) +
                    choices.map { activity.getString(R.string.report_usb_location, it.absolutePath) }
                DiPlayDialog.showSingleChoice(
                    activity, activity.getString(R.string.choose_save_location), labels.toList(), 0,
                ) { selected ->
                    preferredReportRoot = choices.getOrNull(selected - 1)
                    export(null)
                }
            }
            return
        }
        runCatching { launchPicker(reportFileName()) }.onFailure { export(null) }
    }

    fun export(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = activity.getString(R.string.saving_report) }
        val appContext = activity.applicationContext
        val fileName = reportFileName()
        val requestedRoot = preferredReportRoot
        preferredReportRoot = null
        Thread({
            val result = runCatching {
                ActiveSessionLog.flush()
                val report = buildString {
                    appendLine("DiPlay ${versionLabel()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("--- Last crash ---")
                    appendLine(CrashRecord.report(appContext))
                    appendLine("--- Legacy USB / wireless / TLS provider diagnostics ---")
                    append(LegacyConnectionProbe.report(appContext))
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("CarPlay setup: ${if (setupError() == null) "ready" else "authentication unavailable"}")
                    appendLine("Video: H.264; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shihab.diplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    appendLine("--- Last received boot and app-launch result ---")
                    appendLine(StartupDiagnosticSnapshot.report(appContext))
                    appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
                        "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
                    appendLine()
                    appendLine("--- Recent own-app process exits (Android 11+) ---")
                    appendLine(ProcessExitDiagnostics.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report, requestedRoot)
                savedReport to report
            }
            activity.runOnUiThread {
                exportInProgress = false
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = activity.getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    val msg = when {
                        savedReport.savedInApp -> activity.getString(R.string.diagnostic_report_saved_in_app)
                        savedReport.savedPath != null -> activity.getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath) +
                            if (savedReport.copyPaths.isEmpty()) "\n" + activity.getString(R.string.report_usb_copy_unavailable) else "\n" + activity.getString(R.string.report_usb_copy_paths, savedReport.copyPaths.joinToString("\n"))
                        uri == null -> "Downloads/DiPlay/$fileName"
                        else -> activity.getString(R.string.your_report_was_saved_to_the_selected_location)
                    }
                    DiPlayDialog.showChoices(
                        activity, activity.getString(R.string.diagnostic_report_saved), msg, listOf(
                            activity.getString(R.string.view_diagnostic_report) to { showReport(report) },
                            activity.getString(R.string.share) to {
                                runCatching {
                                    activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                        clipData = android.content.ClipData.newRawUri(activity.getString(R.string.report_clip_label), savedReport.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, activity.getString(R.string.share_diagnostic_report)))
                                }.onFailure { showReport(report) }
                            },
                            activity.getString(R.string.done) to {},
                        ),
                    )
                } else {
                    DiPlayDialog.showConfirm(
                        activity, activity.getString(R.string.could_not_save_the_report),
                        activity.getString(R.string.check_that_storage_is_available_or_choose_another_save_loc),
                        activity.getString(R.string.choose_location), activity.getString(R.string.close),
                        onConfirm = { rechoose() },
                    )
                }
            }
        }, "diplay-export").apply { isDaemon = true }.start()
    }

    private fun showReport(report: String) {
        // Some head units (e.g. Allwinner T3, real API 19) choke laying out a multi-hundred-KB
        // TextView: the tap then appears dead. Show only the tail in-app; the full report is
        // already saved to storage by the export step.
        val preview = if (report.length > REPORT_PREVIEW_CHARS) {
            activity.getString(R.string.diplay_report_preview_tail, REPORT_PREVIEW_CHARS / 1024) +
                report.takeLast(REPORT_PREVIEW_CHARS)
        } else {
            report
        }
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(activity.getString(R.string.diagnostic_report_copy_hint), 14, GlassColors.MUTED))
        try {
            body.addView(label(preview, 13, GlassColors.INK).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        } catch (error: Throwable) {
            AppLog.w("DiPlay", "report preview failed", error)
            body.addView(label(activity.getString(R.string.diagnostic_report_preview_unavailable), 14, GlassColors.MUTED))
        }
        runCatching {
            DiPlayDialog.showCustom(
                activity, activity.getString(R.string.view_diagnostic_report),
                ScrollView(activity).apply { addView(body) }, activity.getString(R.string.close),
            )
        }.onFailure { error ->
            AppLog.w("DiPlay", "report dialog failed", error)
            toast(activity.getString(R.string.diagnostic_report_preview_unavailable))
        }
    }

    private fun reportFileName() =
        "75Play-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    companion object {
        /** In-app report preview cap: weak head units cannot lay out a huge TextView. */
        private const val REPORT_PREVIEW_CHARS = 100 * 1024
    }
}
