package com.shihab.diplay.host

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File

/**
 * Root / privileged-install helper for the wired USB permission problem.
 *
 * On KitKat the system keys USB permissions by device path, and a re-enumerated iPhone gets a new
 * path every time, so a normal sideload always falls back to the permission dialog. The only way to
 * grant the new path silently is the hidden `grantDevicePermission`, gated by MANAGE_USB
 * (signature|system). This class detects whether the device can be rooted and, when the user
 * chooses, installs the APK under /system/priv-app so the app becomes a privileged system app and
 * holds MANAGE_USB. Without root the caller keeps using the system dialog.
 */
internal class RootController(private val context: Context) {

    private val appContext = context.applicationContext

    /** True when the running app already holds MANAGE_USB (privileged system install). */
    fun hasManageUsb(): Boolean =
        appContext.checkCallingOrSelfPermission(MANAGE_USB_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** True when the APK lives on the system partition (system app). */
    fun isSystemApp(): Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    /**
     * Probes whether `su` actually works, not just whether a binary exists. Cached after the first
     * successful probe; a failed probe is retried so a later Magisk/SuperSU install is noticed.
     */
    fun isRootAvailable(): Boolean {
        if (rootConfirmed) return true
        return runCatching {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            val ok = exit == 0 && output.contains("uid=0")
            if (ok) rootConfirmed = true
            ok
        }.getOrDefault(false)
    }

    /**
     * Copies the current APK into /system/priv-app and reboots so the package manager grants
     * MANAGE_USB. This is destructive and intentionally requires an explicit user action. Returns
     * a human-readable error message on failure, null on success (reboot then takes over).
     */
    fun installAsPrivilegedApp(): String? {
        if (!isRootAvailable()) return "设备未获得 root 权限"
        val sourceApk = appContext.applicationInfo.sourceDir
            ?: return "无法定位当前安装包"
        if (!File(sourceApk).exists()) return "安装包文件不存在：$sourceApk"

        val targetName = "75Play.apk"
        val targetDir = "/system/priv-app/75Play"
        val targetApk = "$targetDir/$targetName"
        // Mount /system rw, copy, fix perms/context, then reboot. Best-effort restorecon for
        // SELinux-enabled builds; the chmod/chown cover the classic i.MX KitKat rootfs.
        val script = buildString {
            append("mount -o remount,rw /system && ")
            append("mkdir -p '$targetDir' && ")
            append("cp '$sourceApk' '$targetApk' && ")
            append("chmod 644 '$targetApk' && ")
            append("chown root:root '$targetApk' && ")
            append("(restorecon '$targetApk' || true) && ")
            append("mount -o remount,ro /system && ")
            append("sync && ")
            append("reboot")
        }
        return runCatching {
            val process = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0) null else "特权安装失败（exit=$exit）：$output"
        }.getOrElse { error -> "特权安装异常：${error.message}" }
    }

    companion object {
        const val MANAGE_USB_PERMISSION = "android.permission.MANAGE_USB"

        @Volatile
        private var rootConfirmed = false

        /** A short device descriptor for diagnostics (no PII). */
        fun deviceDescriptor(): String =
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
