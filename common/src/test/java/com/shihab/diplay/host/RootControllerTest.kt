package com.shihab.diplay.host

import org.junit.Assert.assertTrue
import org.junit.Test

class RootControllerTest {
    @Test
    fun privilegedInstallAlwaysRestoresSystemReadOnlyBeforeReboot() {
        val script = privilegedInstallScript(
            sourceApk = "/data/app/75Play.apk",
            targetDir = "/system/priv-app/75Play",
            targetApk = "/system/priv-app/75Play/75Play.apk",
        )

        val trap = script.indexOf("trap restore_system_ro EXIT")
        val signalTrap = script.indexOf("trap 'restore_system_ro; exit 1' HUP INT TERM")
        val firstMutableStep = script.indexOf("mkdir -p")
        val explicitRestore = script.indexOf("mount -o remount,ro /system; ro_status=")
        val clearTrap = script.indexOf("trap - EXIT HUP INT TERM")
        val failureExit = script.indexOf("if [ \$status -ne 0 ]; then exit \$status; fi")
        val guardedSync = script.indexOf("sync || exit \$?")
        val reboot = script.lastIndexOf("reboot")

        assertTrue(trap in 0 until firstMutableStep)
        assertTrue(signalTrap in (trap + 1) until firstMutableStep)
        assertTrue(explicitRestore in (firstMutableStep + 1) until clearTrap)
        assertTrue(clearTrap in (explicitRestore + 1) until failureExit)
        assertTrue(failureExit in (clearTrap + 1) until guardedSync)
        assertTrue(guardedSync in (failureExit + 1) until reboot)
    }
}
