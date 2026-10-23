// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import com.shihab.diplay.DiPlayBluetooth
import com.shihab.diplay.compat.realSdkInt
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.LayoutMetrics
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Read-only system / hardware sheet. One selectable text block, not a row per field.
 */
internal object AboutInfoBlock {
    fun appBuildTime(ctx: Context): String? = buildTimeLabel(ctx)

    /** System fields, then hardware fields. Empty values are omitted. */
    fun fill(card: LinearLayout) {
        val ctx = card.context
        val system = present(systemRows(ctx))
        val hardware = present(hardwareRows(ctx))
        system.forEach { (label, value) -> card.addView(infoRow(ctx, label, value)) }
        if (system.isNotEmpty() && hardware.isNotEmpty()) card.addView(GlassSettingsRows.divider(ctx))
        hardware.forEach { (label, value) -> card.addView(infoRow(ctx, label, value)) }
    }

    private fun systemRows(ctx: Context): List<Pair<String, String?>> = listOf(
        ctx.getString(R.string.about_system_version) to releaseLabel(ctx),
        ctx.getString(R.string.about_build_time) to appBuildTime(ctx),
        ctx.getString(R.string.about_info_security) to securityPatch(),
        ctx.getString(R.string.about_info_storage) to storageLabel(ctx),
    )

    private fun hardwareRows(ctx: Context): List<Pair<String, String?>> {
        val unknown = ctx.getString(R.string.about_unknown)
        return listOf(
            ctx.getString(R.string.about_device_model) to deviceModel().ifBlank { unknown },
            ctx.getString(R.string.about_info_brand) to distinctBrand(),
            ctx.getString(R.string.about_info_device) to deviceNames(),
            ctx.getString(R.string.about_hardware_platform) to (blankToNull(Build.HARDWARE) ?: unknown),
            ctx.getString(R.string.about_info_board) to blankToNull(Build.BOARD),
            ctx.getString(R.string.about_info_cpu) to cpuLabel(ctx),
            ctx.getString(R.string.about_info_memory) to memoryLabel(ctx),
            ctx.getString(R.string.about_info_screen) to screenLabel(ctx),
            ctx.getString(R.string.about_info_wifi) to wifiSummary(ctx),
            ctx.getString(R.string.about_info_wifi_mac) to wifiMac(ctx),
            ctx.getString(R.string.about_bluetooth_mac) to (DiPlayBluetooth.localAddress(ctx) ?: unknown),
        )
    }

    private fun present(rows: List<Pair<String, String?>>): List<Pair<String, String>> =
        rows.mapNotNull { (label, value) ->
            val shown = value?.trim().orEmpty()
            if (shown.isEmpty()) null else label to shown
        }

    private fun infoRow(ctx: Context, label: String, value: String): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pv = LayoutMetrics.rowPadV(ctx)
            setPadding(0, pv, 0, pv)
            minimumHeight = LayoutMetrics.rowMinH(ctx)
        }
        row.addView(
            GlassSettingsRows.label(ctx, label, LayoutMetrics.settingsRowTextSize(ctx), GlassColors.TEXT_BODY).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, 0, GlassSettingsRows.dp(ctx, 8), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.78f),
        )
        row.addView(
            GlassSettingsRows.label(ctx, value, LayoutMetrics.settingsCapTextSize(ctx), GlassColors.TEXT_PRIMARY, bold = true).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_END
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextIsSelectable(true)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.22f),
        )
        return row
    }

    private fun releaseLabel(ctx: Context): String {
        val release = Build.VERSION.RELEASE?.trim().orEmpty().ifBlank { ctx.getString(R.string.about_unknown) }
        val real = realSdkInt()
        val reported = Build.VERSION.SDK_INT
        return if (real != reported) {
            ctx.getString(R.string.about_info_release_reported, release, real, reported)
        } else {
            ctx.getString(R.string.about_info_release, release, real)
        }
    }

    private fun securityPatch(): String? {
        if (realSdkInt() < 23) return null
        return blankToNull(Build.VERSION.SECURITY_PATCH)
    }

    private fun buildTimeLabel(ctx: Context): String? {
        val stamped = runCatching {
            ctx.assets.open("build-time.txt").bufferedReader().use { it.readText() }.trim()
        }.getOrNull()
        if (!stamped.isNullOrBlank()) return stamped
        val modified = runCatching { File(ctx.applicationInfo.sourceDir).lastModified() }.getOrDefault(0L)
        if (modified <= 0L) return null
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(modified))
    }

    private fun storageLabel(ctx: Context): String? = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        if (total <= 0L) return null
        ctx.getString(R.string.about_info_free_total, gigabytes(stat.availableBytes), gigabytes(total))
    }.getOrNull()

    private fun deviceModel(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it?.trim().orEmpty() }
            .filter { it.isNotEmpty() && !it.equals("unknown", true) }
            .distinctBy { it.lowercase(Locale.US) }
            .joinToString(" ")

    private fun distinctBrand(): String? {
        val brand = blankToNull(Build.BRAND) ?: return null
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        if (brand.equals(manufacturer, true)) return null
        return brand
    }

    private fun deviceNames(): String? {
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(Build.DEVICE, Build.PRODUCT)
            .map { it?.trim().orEmpty() }
            .filter { it.isNotEmpty() && !it.equals("unknown", true) && !it.equals(model, true) }
            .distinctBy { it.lowercase(Locale.US) }
            .joinToString(" / ")
            .ifBlank { null }
    }

    private fun cpuLabel(ctx: Context): String {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val hardware = cpuHardware()
        return if (hardware == null) {
            ctx.getString(R.string.about_info_cpu_value, cores)
        } else {
            ctx.getString(R.string.about_info_cpu_named, cores, hardware)
        }
    }

    private fun cpuHardware(): String? {
        val lines = runCatching { File("/proc/cpuinfo").readLines() }.getOrNull() ?: return null
        val keys = listOf("hardware", "model name")
        for (key in keys) {
            val found = lines.firstNotNullOfOrNull { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2 && parts[0].trim().equals(key, true)) parts[1].trim() else null
            }
            if (!found.isNullOrBlank() && !found.equals("unknown", true)) return found
        }
        return null
    }

    private fun memoryLabel(ctx: Context): String? = runCatching {
        val manager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        if (info.totalMem <= 0L) return null
        ctx.getString(R.string.about_info_free_total, gigabytes(info.availMem), gigabytes(info.totalMem))
    }.getOrNull()

    private fun screenLabel(ctx: Context): String? = runCatching {
        val window = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val metrics = DisplayMetrics()
        window.defaultDisplay.getRealMetrics(metrics)
        val hz = window.defaultDisplay.refreshRate.roundToInt()
        if (hz in 20..240) {
            ctx.getString(R.string.about_info_screen_hz, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, hz)
        } else {
            ctx.getString(R.string.about_info_screen_value, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }.getOrNull()

    @SuppressLint("HardwareIds")
    private fun wifiSummary(ctx: Context): String? {
        val info = wifiInfo(ctx) ?: return null
        val ipInt = info.ipAddress
        val ip = if (ipInt == 0) null else String.format(
            Locale.US,
            "%d.%d.%d.%d",
            ipInt and 0xff,
            ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff,
            ipInt ushr 24 and 0xff,
        )
        val raw = info.ssid?.trim()?.removeSurrounding("\"")
        val ssid = raw?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", true) && it != "0x" }
        return when {
            ssid != null && ip != null -> "$ssid · $ip"
            ssid != null -> ssid
            else -> ip
        }
    }

    @SuppressLint("HardwareIds")
    private fun wifiMac(ctx: Context): String? {
        val mac = wifiInfo(ctx)?.macAddress?.trim()?.lowercase(Locale.US) ?: return null
        if (mac.isEmpty() || mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") return null
        return mac
    }

    private fun wifiInfo(ctx: Context?): android.net.wifi.WifiInfo? {
        val context = ctx ?: return null
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching { manager.connectionInfo }.getOrNull()
    }

    private fun gigabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)

    private fun blankToNull(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) }
}
