package com.shihab.diplay

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.shihab.diplay.airplay.VideoInCar
import com.shihab.diplay.compat.realSdkInt
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.FocusSupport
import com.shihab.diplay.ui.dp
import com.shihab.diplay.util.AppLog
import java.util.Locale

/** API 19-compatible player for iOS 27 video in car. */
@OptIn(UnstableApi::class)
class CarPlayVideoActivity : Activity() {
    private val playerHost = object : CarPlayVideo.PlayerHost {
        override fun applyRate() = this@CarPlayVideoActivity.applyRate()
        override fun applySeek() = this@CarPlayVideoActivity.applySeek()
        override fun skip(deltaMillis: Int) = this@CarPlayVideoActivity.skip(deltaMillis)
        override fun load() = this@CarPlayVideoActivity.load()
        override fun state() = this@CarPlayVideoActivity.state()
        override fun finish() = this@CarPlayVideoActivity.finish()
    }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var surfaceView: SurfaceView
    private lateinit var controls: View
    private lateinit var playPause: ImageView
    private lateinit var position: TextView
    private lateinit var length: TextView
    private lateinit var timeBar: SeekBar
    private var surface: Surface? = null
    private var loadedUrl: String? = null
    private var prepared = false
    private var scrubbing = false
    private var encryptionLogged = false
    private val hideControls = Runnable {
        controls.visibility = View.GONE
        main.removeCallbacks(tick)
    }
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(surfaceCallback)
            setBackgroundColor(Color.BLACK)
        }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val sources = IphoneResolvingDataSource.Factory(DefaultDataSource.Factory(this, http))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !prepared) {
                    prepared = true
                    AppLog.i(
                        TAG,
                        "prepared duration=${player.duration} ms size=${player.videoSize.width}x${player.videoSize.height}",
                    )
                    applyRate()
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) = logEncryption()

            override fun onPlayerError(error: PlaybackException) {
                AppLog.w(TAG, "player error ${error.errorCodeName}: ${causes(error)}")
                CarPlayVideo.onPlayerFailed(
                    when (error.errorCode) {
                        in 2000..2999 -> VideoInCar.ERROR_NETWORK
                        in 4000..4999 -> VideoInCar.ERROR_DECODER
                        else -> VideoInCar.ERROR_INCOMPATIBLE_ASSET
                    },
                )
            }
        })
        controls = controlBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        surfaceView.setOnClickListener { showControls() }
        CarPlayVideo.activity = playerHost
        load()
        showControls()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surface = holder.surface
            player.setVideoSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surface = null
            player.clearVideoSurface()
        }
    }

    private fun controlBar(): View {
        val back = TextView(this).apply {
            text = "<  " + getString(R.string.video_back_to_carplay)
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            minHeight = dp(64)
            minWidth = dp(64)
            setPadding(dp(24), 0, dp(24), 0)
            setBackgroundColor(0xCC0C1830.toInt())
            isFocusable = true
            FocusSupport.attachStrokeOverlay(this, 8)
            setOnClickListener { finish() }
        }
        fun button(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            contentDescription = label
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(0xCC0C1830.toInt())
            isFocusable = true
            FocusSupport.attachStrokeOverlay(this, 8)
            setOnClickListener { onClick(); showControls() }
        }
        playPause = button(android.R.drawable.ic_media_play, getString(R.string.video_play)) {
            CarPlayVideo.setPlaying(!CarPlayVideo.playing)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(button(android.R.drawable.ic_media_rew, getString(R.string.video_play)) { skip(-CarPlayVideo.SKIP_MILLIS) },
                LinearLayout.LayoutParams(dp(80), dp(80)))
            addView(playPause, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                marginStart = dp(48)
                marginEnd = dp(48)
            })
            addView(button(android.R.drawable.ic_media_ff, getString(R.string.video_play)) { skip(CarPlayVideo.SKIP_MILLIS) },
                LinearLayout.LayoutParams(dp(80), dp(80)))
        }
        fun timeText() = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        position = timeText()
        length = timeText()
        timeBar = SeekBar(this).apply {
            minimumHeight = dp(48)
            isFocusable = true
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) position.text = time(progress.toLong())
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    scrubbing = true
                    main.removeCallbacks(hideControls)
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    scrubbing = false
                    if (prepared) player.seekTo(bar.progress.toLong())
                    showControls()
                }
            })
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(position)
            addView(timeBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(length)
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(48), dp(32), dp(24))
            setBackgroundColor(0xE60C1830.toInt())
            addView(buttons)
            addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return FrameLayout(this).apply {
            addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
            addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { hideControls.run() }
        }
    }

    private fun showControls() {
        updatePlayPause()
        updateTime()
        controls.visibility = View.VISIBLE
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MILLIS)
        main.removeCallbacks(hideControls)
        if (!scrubbing) main.postDelayed(hideControls, CONTROLS_MILLIS)
    }

    private fun updatePlayPause() {
        playPause.setImageResource(if (CarPlayVideo.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        playPause.contentDescription = getString(if (CarPlayVideo.playing) R.string.video_pause else R.string.video_play)
    }

    private fun updateTime() {
        val duration = player.duration.takeIf { prepared && it != C.TIME_UNSET && it > 0L } ?: 0L
        timeBar.isEnabled = duration > 0
        timeBar.visibility = if (duration > 0) View.VISIBLE else View.INVISIBLE
        length.text = if (duration > 0) time(duration) else ""
        if (duration <= 0) {
            position.text = if (prepared) time(player.currentPosition) else ""
            return
        }
        timeBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        timeBar.secondaryProgress = player.bufferedPosition.coerceIn(0L, duration).toInt()
        if (!scrubbing) {
            val current = player.currentPosition.coerceIn(0L, duration)
            timeBar.progress = current.toInt()
            position.text = time(current)
        }
    }

    private fun time(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    fun state(): VideoInCar.PlayerState {
        val duration = player.duration.takeIf { prepared && it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        val current = player.currentPosition.coerceAtLeast(0L)
        return VideoInCar.PlayerState(
            prepared = prepared,
            playing = player.isPlaying,
            positionSeconds = current / 1000.0,
            durationSeconds = duration / 1000.0,
            bufferedSeconds = player.bufferedPosition / 1000.0,
        )
    }

    fun load() {
        val url = CarPlayVideo.url ?: return
        if (url == loadedUrl) return
        loadedUrl = url
        prepared = false
        encryptionLogged = false
        AppLog.i(TAG, "loading video scheme=${Uri.parse(url).scheme.orEmpty()} ${playbackNetworkSummary()}")
        val item = MediaItem.Builder().setUri(url)
            .apply { if (CarPlayVideo.streaming) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item, (CarPlayVideo.pendingSeekMillis ?: CarPlayVideo.startMillis).toLong())
        CarPlayVideo.pendingSeekMillis = null
        player.prepare()
        applyRate()
    }

    fun applyRate() {
        player.playWhenReady = CarPlayVideo.playing
        if (controls.visibility == View.VISIBLE) updatePlayPause()
    }

    fun skip(deltaMillis: Int) {
        if (!prepared) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMillis).coerceIn(0L, duration))
    }

    fun applySeek() {
        val target = CarPlayVideo.pendingSeekMillis ?: return
        if (prepared) player.seekTo(target.toLong())
        CarPlayVideo.pendingSeekMillis = null
    }

    private fun logEncryption() {
        if (encryptionLogged) return
        val manifest = player.currentManifest as? HlsManifest ?: return
        encryptionLogged = true
        val media = manifest.mediaPlaylist
        val keyScheme = media.segments.firstNotNullOfOrNull { it.fullSegmentEncryptionKeyUri }
            ?.let { Uri.parse(it).scheme ?: "relative" }
        val drm = media.protectionSchemes?.let { init ->
            (0 until init.schemeDataCount).map { init.get(it).uuid }
        }
        val sessionKeys = manifest.multivariantPlaylist.sessionKeyDrmInitData.map { it.schemeType }
        AppLog.i(
            TAG,
            "HLS encryption aes128KeyScheme=$keyScheme sampleAesSchemeType=${media.protectionSchemes?.schemeType} " +
                "drmUuids=$drm sessionKeys=$sessionKeys segments=${media.segments.size}",
        )
    }

    private fun causes(error: Throwable): String = generateSequence(error) { it.cause }.take(4)
        .joinToString(" <- ") { "${it.javaClass.simpleName}(${it.message?.replace(Regex("\\w+://\\S+"), "<url>")})" }

    private fun playbackNetworkSummary(): String {
        if (realSdkInt() < 23) return "network=legacy"
        return runCatching {
            Class.forName("com.shihab.diplay.VideoPlaybackNetwork")
                .getMethod("summary", android.content.Context::class.java)
                .invoke(null, this) as String
        }.getOrDefault("network=unavailable")
    }

    override fun onDestroy() {
        main.removeCallbacks(hideControls)
        main.removeCallbacks(tick)
        if (CarPlayVideo.activity === playerHost) {
            CarPlayVideo.activity = null
            CarPlayVideo.onPlayerClosed(if (prepared) player.currentPosition.toInt() else null)
        }
        player.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DiPlay-Video"
        const val CONTROLS_MILLIS = 5_000L
        const val TICK_MILLIS = 500L
    }
}
