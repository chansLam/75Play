package com.shihab.diplay.compat

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class RealSdkIntTest {
    @Test fun kitkatReleaseOverridesASpoofedSdkInt() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 27)
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "RELEASE", "4.4.2")
        assertEquals(Build.VERSION_CODES.KITKAT, realSdkInt())
    }

    @Test fun realReleaseKeepsTheReportedSdk() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 27)
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "RELEASE", "8.1.0")
        assertEquals(27, realSdkInt())
    }
}