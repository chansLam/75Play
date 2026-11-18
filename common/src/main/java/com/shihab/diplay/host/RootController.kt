package com.shihab.diplay.host

import android.content.Context
import android.os.Build
import com.shihab.diplay.RootShell
import java.io.File

/**
 * Root / privileged-install helper for the wired USB permission problem.
 *
 * On KitKat the system keys USB permissions by device path, and a re-enumerated iPhone gets a new
 * path every time, so a normal sideload always falls back to the permission dialog. The only way to
 * grant the new path silently is the hidden `grantDevicePermission`, gated by MANAGE_USB
 * (signature|system), and that permission is granted only to a privileged app. KitKat scans
 * `*.apk` files sitting directly in `/system/priv-app` and does not walk subdirectories, and a
 * privileged system app does not unpack JNI out of the apk (it expects those libraries in
 * `/system/lib`). This class installs the current APK at `/system/priv-app/75Play.apk`, copies the
 * already-extracted native libraries to `/system/lib`, and reboots. Without root the caller keeps
 * using the system dialog.
 */
internal class RootController(private val context: Context) {

    private val appContext = context.applicationContext

    /** True when the running app already holds MANAGE_USB (privileged system install). */
    fun hasManageUsb(): Boolean =
        appContext.checkCallingOrSelfPermission(MANAGE_USB_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Probes whether `su` actually elevates. Uses the same binary search as the rest of the app,
     * so a head unit whose `su` is not on `PATH` is still found. A failed probe is retried.
     */
    fun isRootAvailable(): Boolean = RootShell.findSu() != null

    /**
     * Copies the current APK to `/system/priv-app/75Play.apk` and reboots so the package manager
     * grants MANAGE_USB. This is destructive and intentionally requires an explicit user action.
     * Returns a human-readable error message on failure, null on success (reboot then takes over).
     */
    fun installAsPrivilegedApp(): String? {
        val su = RootShell.findSu() ?: return "设备未获得 root 权限"
        val sourceApk = appContext.applicationInfo.sourceDir
            ?: return "无法定位当前安装包"
        if (!File(sourceApk).exists()) return "安装包文件不存在：$sourceApk"
        // The app lib directory is already empty once PackageManager has treated this as a
        // privileged system app, so unpack straight from the apk instead of trusting it.
        val stage = File(appContext.cacheDir, "priv-native-stage")
        stage.listFiles()?.forEach { it.delete() }
        val extracted = PrivilegedNativeLibraries.extractFromApk(File(sourceApk), stage)
        if (extracted == 0) return "无法从安装包解出本机库，已取消安装以免重启后无法启动"

        val targetApk = PRIV_APP_APK
        val script = privilegedInstallScript(sourceApk, targetApk, stage.absolutePath)
        return runCatching {
            val process = ProcessBuilder(su, "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0) null else "特权安装失败（exit=$exit）：$output"
        }.getOrElse { error -> "特权安装异常：${error.message}" }
    }

    companion object {
        const val MANAGE_USB_PERMISSION = "android.permission.MANAGE_USB"

        /** Flat path. KitKat never scans `/system/priv-app/<name>/<name>.apk`. */
        const val PRIV_APP_APK = "/system/priv-app/75Play.apk"

        /** A short device descriptor for diagnostics (no PII). */
        fun deviceDescriptor(): String =
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}"
    }
}

/**
 * Builds a KitKat `/system` install script that always attempts to restore the read-only mount.
 *
 * [targetApk] must be a file directly inside `/system/priv-app`. Native libraries are copied to
 * `/system/lib` because a non-updated privileged app does not unpack JNI, and the linker still
 * searches `java.library.path` after the app's own lib directory is cleared.
 */
internal fun privilegedInstallScript(sourceApk: String, targetApk: String, nativeLibDir: String): String =
    buildString {
        append("mount -o remount,rw /system || exit \$?; ")
        append("status=0; ")
        append("restore_system_ro() { mount -o remount,ro /system || true; }; ")
        append("trap restore_system_ro EXIT; ")
        append("trap 'restore_system_ro; exit 1' HUP INT TERM; ")
        append("mkdir -p '/system/priv-app' || status=\$?; ")
        append("if [ \$status -eq 0 ]; then cp '$sourceApk' '$targetApk' || status=\$?; fi; ")
        append("if [ \$status -eq 0 ]; then chmod 644 '$targetApk' || status=\$?; fi; ")
        append("if [ \$status -eq 0 ]; then chown 0:0 '$targetApk' || status=\$?; fi; ")
        append("if [ \$status -eq 0 ]; then restorecon '$targetApk' || true; fi; ")
        if (!nativeLibDir.startsWith("/system/lib")) {
            append("if [ \$status -eq 0 ] && [ -d '$nativeLibDir' ]; then ")
            append("for so in '$nativeLibDir'/*.so; do ")
            append("if [ \$status -ne 0 ]; then break; fi; ")
            append("if [ -f \"\$so\" ]; then ")
            append("cp \"\$so\" \"/system/lib/\${so##*/}\" || status=\$?; ")
            append("if [ \$status -eq 0 ]; then chmod 644 \"/system/lib/\${so##*/}\" || status=\$?; fi; ")
            append("if [ \$status -eq 0 ]; then chown 0:0 \"/system/lib/\${so##*/}\" || status=\$?; fi; ")
            append("if [ \$status -eq 0 ]; then restorecon \"/system/lib/\${so##*/}\" || true; fi; ")
            append("fi; ")
            append("done; ")
            append("fi; ")
        }
        // Drop the previous subdirectory install. KitKat ignores it, and leaving it wastes space.
        append("if [ \$status -eq 0 ] && [ -d '/system/priv-app/75Play' ]; then rm -rf '/system/priv-app/75Play' || status=\$?; fi; ")
        append("mount -o remount,ro /system; ro_status=\$?; ")
        append("if [ \$ro_status -eq 0 ]; then trap - EXIT HUP INT TERM; fi; ")
        append("if [ \$status -eq 0 ] && [ \$ro_status -ne 0 ]; then status=\$ro_status; fi; ")
        append("if [ \$status -ne 0 ]; then exit \$status; fi; ")
        append("sync || exit \$?; reboot")
    }
