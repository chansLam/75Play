package com.shihab.diplay.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootControllerTest {
    @Test
    fun privilegedInstallAlwaysRestoresSystemReadOnlyBeforeReboot() {
        val script = privilegedInstallScript(
            sourceApk = "/data/app/75Play.apk",
            targetApk = "/system/priv-app/75Play.apk",
            nativeLibDir = "/data/app-lib/com.shihab.diplay",
        )

        val trap = script.indexOf("trap restore_system_ro EXIT")
        val signalTrap = script.indexOf("trap 'restore_system_ro; exit 1' HUP INT TERM")
        val firstMutableStep = script.indexOf("mkdir -p")
        val explicitRestore = script.indexOf("mount -o remount,ro /system; ro_status=")
        val clearTrap = script.indexOf("trap - EXIT HUP INT TERM")
        val failureExit = script.indexOf("if [ \$status -ne 0 ]; then exit \$status; fi")
        val sync = script.indexOf("sync;")
        val reboot = script.lastIndexOf("reboot")

        assertTrue(trap in 0 until firstMutableStep)
        assertTrue(signalTrap in (trap + 1) until firstMutableStep)
        assertTrue(explicitRestore in (firstMutableStep + 1) until clearTrap)
        assertTrue(clearTrap in (explicitRestore + 1) until failureExit)
        assertTrue(failureExit in (clearTrap + 1) until sync)
        assertTrue(sync in (failureExit + 1) until reboot)
    }

    @Test
    fun startupSyncCopiesWithoutRebooting() {
        val script = privilegedInstallScript(
            sourceApk = "/data/app/75Play.apk",
            targetApk = "/system/priv-app/75Play.apk",
            nativeLibDir = "/data/app-lib/com.shihab.diplay",
            reboot = false,
        )

        assertTrue(script.contains("sync;"))
        assertFalse(script.contains("reboot"))
    }

    @Test
    fun privilegedPathDetectionOnlyMatchesTheSystemCopy() {
        assertTrue(isPrivilegedApkPath("/system/priv-app/75Play.apk"))
        assertTrue(isPrivilegedApkPath("/system/priv-app/75Play/base.apk"))
        assertFalse(isPrivilegedApkPath("/data/app/com.shihab.diplay-1.apk"))
        assertFalse(isPrivilegedApkPath("/system/app/75Play.apk"))
    }

    @Test
    fun privilegedInstallPutsApkWhereKitKatScansAndKeepsNativeLibs() {
        val script = privilegedInstallScript(
            sourceApk = "/data/app/75Play.apk",
            targetApk = "/system/priv-app/75Play.apk",
            nativeLibDir = "/data/app-lib/com.shihab.diplay",
        )

        val copyApk = script.indexOf("cp '/data/app/75Play.apk' '/system/priv-app/75Play.apk'")
        val copyLibs = script.indexOf("cp \"\$so\" \"/system/lib/\${so##*/}\"")
        val dropStaleDir = script.indexOf("rm -rf '/system/priv-app/75Play'")
        val remountReadOnly = script.indexOf("mount -o remount,ro /system; ro_status=")

        assertTrue(copyApk > 0)
        assertTrue(copyLibs in (copyApk + 1) until dropStaleDir)
        assertTrue(dropStaleDir in (copyLibs + 1) until remountReadOnly)
        assertFalse(script.contains("/system/priv-app/75Play/75Play.apk"))
        assertTrue(RootController.PRIV_APP_APK == "/system/priv-app/75Play.apk")
    }

    @Test
    fun privilegedInstallSkipsLibCopyWhenLibsAlreadyLiveInSystemLib() {
        val script = privilegedInstallScript(
            sourceApk = "/system/priv-app/75Play.apk",
            targetApk = "/system/priv-app/75Play.apk",
            nativeLibDir = "/system/lib",
        )

        assertFalse(script.contains("/system/lib/\${so##*/}"))
        assertTrue(script.contains("cp '/system/priv-app/75Play.apk' '/system/priv-app/75Play.apk'"))
    }
}
