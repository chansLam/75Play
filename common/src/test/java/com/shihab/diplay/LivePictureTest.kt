package com.shihab.diplay

import android.graphics.Paint
import android.os.Looper
import android.view.TextureView
import android.view.View
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LivePictureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private class RecordingTexture : TextureView(RuntimeEnvironment.getApplication()) {
        var applied: Paint? = null
        override fun setLayerPaint(paint: Paint?) { applied = paint }
    }
    @Before fun reset() {
        CarPlayPicture.showOriginal(false)
        CarPlayPicture.preferences(context).edit().clear().commit()
    }
    @Test fun changesReachBothTexturesAndOriginalComparisonDoesNotOverwriteSettings() {
        val main = RecordingTexture()
        val cluster = RecordingTexture()
        val mainBinding = CarPlayPicture.Binding(main)
        val clusterBinding = CarPlayPicture.Binding(cluster)
        try {
            assertNull(main.applied)
            assertNull(cluster.applied)
            CarPlayPicture.preferences(context).edit().putInt(CarPlayPicture.CONTRAST, 125).apply()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(main.applied?.colorFilter)
            assertNotNull(cluster.applied?.colorFilter)
            CarPlayPicture.showOriginal(true)
            assertNull(main.applied)
            assertNull(cluster.applied)
            assertEquals(125, CarPlayPicture.value(CarPlayPicture.preferences(context), CarPlayPicture.CONTRAST))
            CarPlayPicture.showOriginal(false)
            assertNotNull(main.applied)
            assertNotNull(cluster.applied)
            mainBinding.close()
            val old = main.applied
            CarPlayPicture.reset(CarPlayPicture.preferences(context))
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(old, main.applied)
            assertNull(cluster.applied)
        } finally {
            mainBinding.close(); clusterBinding.close(); CarPlayPicture.showOriginal(false)
        }
    }
}
