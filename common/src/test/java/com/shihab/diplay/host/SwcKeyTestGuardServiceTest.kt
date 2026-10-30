package com.shihab.diplay.host

import android.app.Service
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SwcKeyTestGuardServiceTest {
    private val setting = "coagent_settings_key_test_mode"

    @Test fun aRestartedGuardClearsTheSwitchWhenCarPlayIsGone() {
        val app = RuntimeEnvironment.getApplication()
        Settings.System.putInt(app.contentResolver, setting, 1)
        val service = Robolectric.buildService(SwcKeyTestGuardService::class.java).create().get()
        val restart = service.onStartCommand(null, Service.START_FLAG_RETRY, 1)
        assertEquals(Service.START_NOT_STICKY, restart)
        assertEquals(0, Settings.System.getInt(app.contentResolver, setting, 1))
    }
}
