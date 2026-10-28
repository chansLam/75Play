package com.shihab.diplay.host

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import com.shihab.diplay.media.AndroidMediaSink
import java.util.concurrent.Executors

/**
 * TextureView and SurfaceView listeners for the CarPlay canvas.
 * Host keeps `textureListener` and `fallbackSurfaceCallback` pointing at these objects so
 * lifecycle tests can still reach the SurfaceView callback.
 *
 * Surface destroy callbacks must return quickly on KitKat: awaiting decoder detach on the UI
 * thread can block for the sink's full timeout (~2.5s) and ANR. [beginSurfaceDetach] already
 * removes the surface from the sink and queues workers; confirmation and [CarPlayVideoSurfaceOwner]
 * cleanup run on a background thread.
 */
internal class HostVideoSurfaces(
    private val owner: CarPlayVideoSurfaceOwner<Surface>,
    private val bridge: Bridge,
) {
    var currentSurfaceTexture: SurfaceTexture? = null
        private set

    val current: Surface? get() = owner.current

    fun forgetTexture() {
        currentSurfaceTexture = null
    }

    interface Bridge {
        fun log(message: String)
        fun attach(surface: Surface)
        fun layout(width: Int, height: Int)
        fun schedule(width: Int, height: Int)
        fun destroyed(): Boolean
        fun fallbackHolder(): SurfaceHolder?
        fun videoSize(): Pair<Int, Int>?
        fun smoothVideo(): Boolean
        fun refreshMainPicture()
        /** A decoded frame reached the TextureView. SurfaceView uses the session listener instead. */
        fun framePresented()
        fun liveSink(): AndroidMediaSink?
        fun retiringSinks(): Collection<AndroidMediaSink>
    }

    val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = current
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    owner.replace(it, releaseOnDetach = true)
                    currentSurfaceTexture = texture
                }
            }
            bridge.log(if (existing === surface) "Texture surface reused" else "Texture surface created")
            bridge.attach(surface)
            bridge.layout(width, height)
            bridge.schedule(width, height)
            // The stream may already be running. Ask for a frame on this surface now.
            bridge.refreshMainPicture()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            bridge.layout(width, height)
            bridge.schedule(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            val surface = current
            currentSurfaceTexture = null
            if (surface == null) {
                bridge.log("Texture surface destroyed")
                return true
            }
            // Return false so TextureView leaves the SurfaceTexture to us until codecs leave;
            // releasing early while MediaCodec still outputs would crash KitKat.
            beginDetachAsync(surface, parkMain = bridge.smoothVideo()) { confirmed ->
                owner.clear(surface)
                runCatching { texture.release() }
                bridge.log("Texture surface destroyed detachConfirmed=$confirmed")
            }
            return false
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = bridge.framePresented()
    }

    val fallbackCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (bridge.destroyed() || holder !== bridge.fallbackHolder()) return
            val surface = holder.surface
            owner.replace(surface, releaseOnDetach = false)
            bridge.log("SurfaceView video surface created valid=${surface.isValid}")
            bridge.attach(surface)
            // A still CarPlay screen sends no frames, so ask for one instead of showing the parked gap.
            if (bridge.smoothVideo()) bridge.refreshMainPicture()
            bridge.videoSize()?.let { (width, height) -> bridge.layout(width, height) }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (holder !== bridge.fallbackHolder()) return
            // Holder dimensions describe the fitted video, not the host window/CarPlay canvas.
            bridge.videoSize()?.let { (viewWidth, viewHeight) -> bridge.layout(viewWidth, viewHeight) }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val surface = holder.surface
            // beginSurfaceDetach stops new codecs from targeting this surface immediately. Waiting for
            // confirmation belongs off the UI thread so KitKat does not ANR during back-to-shell / app
            // switches. The SurfaceHolder may invalidate the Surface after this returns; workers must
            // already have left (or be leaving) via the queued detach.
            beginDetachAsync(surface, parkMain = bridge.smoothVideo()) { confirmed ->
                owner.clear(surface)
                bridge.log("SurfaceView video surface destroyed detachConfirmed=$confirmed")
            }
        }
    }

    private fun beginDetachAsync(surface: Surface, parkMain: Boolean, onSettled: (Boolean) -> Unit) {
        val live = bridge.liveSink()
        val detaches = (listOfNotNull(live) + bridge.retiringSinks()).distinct()
            .map { sink ->
                sink.beginSurfaceDetach(surface, parkMain = parkMain && sink === live)
            }
        DETACH_WAIT.execute {
            val confirmed = if (detaches.isEmpty()) true else detaches.map { it.await() }.all { it }
            onSettled(confirmed)
        }
    }

    private companion object {
        private val DETACH_WAIT = Executors.newSingleThreadExecutor { task ->
            Thread(task, "carplay-surface-detach").apply { isDaemon = true }
        }
    }
}
