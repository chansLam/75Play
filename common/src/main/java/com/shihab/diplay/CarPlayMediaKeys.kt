package com.shihab.diplay
import com.shihab.diplay.host.CarPlayBackgroundSession

import android.content.Context
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioManager
import android.media.RemoteControlClient
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import com.shihab.diplay.airplay.CarPlayMediaButton
import com.shihab.diplay.host.R
import com.shihab.diplay.media.CarPlayNowPlaying
import com.shihab.diplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import com.shihab.diplay.util.AppLog

/**
 * Media keys for CarPlay.
 *
 * Android delivers media keys to a media session; BYD picks the session of the audio-focus
 * owner. Once CarPlay plays music, DiPlay holds audio focus and an active session until the
 * CarPlay session ends, so play also works after a pause. Keys go to the iPhone as CarPlay media
 * HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    /**
     * Guards every mutable field below. All check-then-act / swap / increment sequences
     * (controller↔expected comparison, focusOwner/focusEventRevision handshake, artwork
     * cache mutation, nowPlaying/artwork swap) run while this lock is held. External
     * listeners (the CarPlay sink) are always invoked outside the lock.
     */
    private val lock = Any()
    private var artworkOwner: Any? = null
    private var controller: CarPlayController? = null
    private var legacyClient: RemoteControlClient? = null
    private var legacyButtons: PendingIntent? = null
    private var legacyButtonComponent: ComponentName? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusOwner: Any? = null
    private var focusEventRevision = 0L
    private var focusHeld = false
    /** Some car firmware never sends a gain callback after the phone call ends. */
    private var transientFocusInterrupted = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null

    fun attach(context: Context, next: CarPlayController) {
        synchronized(lock) {
            if (controller !== next) {
                releaseLocked()
                artworkOwner = artworkQueue.newSession()
            }
            appContext = context.applicationContext
            controller = next
            next.playbackListener = { playing -> onIphonePlaying(next, playing) }
            next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
            next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
        }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    fun detach(expected: CarPlayController?) {
        synchronized(lock) {
            if (expected == null || controller !== expected) return
            expected.playbackListener = null
            expected.nowPlayingListener = null
            expected.artworkListener = null
            controller = null
            releaseLocked()
        }
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(lock) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        if (playing) mainHandler.post {
            synchronized(lock) {
                if (controller === expected) regainFocusLocked(onIphonePlaybackResume = true)
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(lock) {
                if (controller !== expected) return@synchronized
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                nowPlaying = update
                publishPlaybackStateLocked()
            }
        }
    }

    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        synchronized(lock) {
            if (controller !== expected) return
            artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
        }
    }

    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        synchronized(lock) {
            if (artworkOwner !== expected) {
                decoded?.recycle()
                return
            }
            artworkCache.remove(id)
            artworkCache[id] = decoded
            while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
            if (nowPlaying.artworkTransferId == id) {
                artwork = decoded
                publishPlaybackStateLocked()
            }
        }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() = regainFocusLocked(onIphonePlaybackResume = false)

    private fun regainFocusLocked(onIphonePlaybackResume: Boolean) {
        // Do not compete with an ongoing call. Only a fresh iPhone play transition may
        // retry a transient loss for which the head unit never sent AUDIOFOCUS_GAIN.
        if (transientFocusInterrupted && !onIphonePlaybackResume) return
        if (focusHeld && !transientFocusInterrupted) return
        @Suppress("DEPRECATION")
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        @Suppress("DEPRECATION")
        val result = audio.requestAudioFocus(
            legacyFocusListener ?: return,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        )
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) {
            transientFocusInterrupted = false
            forwardGrantedFocusLocked()
        }
        AppLog.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active && focusOwner == null) start(context) else if (active) regainFocusLocked()
        publishPlaybackStateLocked()
    }

    private fun start(context: Context) {
        val expectedController = controller ?: return
        val owner = Any().also { focusOwner = it }
        focusEventRevision = 0L
        transientFocusInterrupted = false
        @Suppress("DEPRECATION")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            onFocusChanged(expectedController, owner, change)
        }
        legacyFocusListener = listener
        @Suppress("DEPRECATION")
        val result = audio?.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusHeld = granted
        if (granted) forwardGrantedFocusLocked()
        val component = ComponentName(context, LegacyMediaKeyReceiver::class.java)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(component)
        @Suppress("DEPRECATION")
        val buttons = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        legacyButtons = buttons
        legacyButtonComponent = component
        // The ComponentName overload exists since API 8 and is the product's API 19 route.
        audio?.registerMediaButtonEventReceiver(component)
        legacyClient = RemoteControlClient(buttons).also {
            it.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or
                RemoteControlClient.FLAG_KEY_MEDIA_NEXT or RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS)
            audio?.registerRemoteControlClient(it)
        }
        AppLog.i(TAG, "media keys active focusGranted=$granted")
    }

    private fun forwardGrantedFocusLocked() {
        val expectedController = controller ?: return
        val owner = focusOwner ?: return
        val revision = focusEventRevision
        // Immediate grants do not promise a later focus callback. Defer dispatch until the caller
        // releases the media-key monitor. A newer real focus event invalidates this observation,
        // as do a controller or request replacement while the queued work waits.
        mainHandler.post { onFocusChanged(expectedController, owner, AudioManager.AUDIOFOCUS_GAIN, revision) }
    }

    private fun onFocusChanged(expectedController: CarPlayController, owner: Any, change: Int,
        grantedRevision: Long? = null) {
        val current = synchronized(lock) {
            if (controller !== expectedController || focusOwner !== owner ||
                (grantedRevision != null && grantedRevision != focusEventRevision)) false
            else {
                focusEventRevision += 1
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        focusHeld = false
                        transientFocusInterrupted = false
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> transientFocusInterrupted = true
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusHeld = true
                        transientFocusInterrupted = false
                    }
                }
                true
            }
        }
        if (!current) return
        AppLog.i(TAG, "audio focus change=$change")
        // Resolve the matching sink and invoke it outside the media-key monitor. An abandoned
        // request must never mute a newer controller, and these owners must not nest locks.
        val background = CarPlayBackgroundSession.snapshot()
        if (background?.controller === expectedController) background.sink.onMediaAudioFocusChanged(change)
    }

    private fun releaseLocked() {
        transientFocusInterrupted = false
        focusOwner = null
        artworkOwner = null
        artworkQueue.clear()
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        @Suppress("DEPRECATION")
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        legacyClient?.let { audio?.unregisterRemoteControlClient(it) }
        legacyButtonComponent?.let { audio?.unregisterMediaButtonEventReceiver(it) }
        legacyClient = null
        legacyButtons = null
        legacyButtonComponent = null
        legacyFocusListener?.let {
            @Suppress("DEPRECATION")
            audio?.abandonAudioFocus(it)
        }
        legacyFocusListener = null
        focusHeld = false
    }

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        legacyClient?.setPlaybackState(
            if (playing) RemoteControlClient.PLAYSTATE_PLAYING else RemoteControlClient.PLAYSTATE_PAUSED,
        )
    }

    private fun send(index: Int, source: String) {
        // While the car's video player is on screen the media keys drive it: a CarPlay play/pause
        // would make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            AppLog.i(TAG, "media key $source -> car video player $index")
            return
        }
        val sent = synchronized(lock) { controller }?.sendMediaButton(index) ?: false
        AppLog.i(TAG, "media key $source -> CarPlay $index sent=$sent")
    }

    fun onLegacyMediaButton(intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return
        send(index, KeyEvent.keyCodeToString(event.keyCode))
    }

    // Without art the car draws DiPlay's bright launcher icon instead.
    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    internal fun placeholderArt(context: Context): Bitmap? = androidx.core.content.ContextCompat
        .getDrawable(context, R.drawable.art_now_playing_placeholder)
        ?.toBitmap(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)

    /**
     * The art to show once the iPhone names transfer [id]. A pending transfer keeps [current], so the
     * placeholder does not flash between tracks.
     */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return squareArtwork(decoded).also { square -> if (square !== decoded) decoded.recycle() }
    }

    /**
     * Fits [source] inside a transparent square of at most [MAX_ARTWORK_DIMENSION]. Car clusters draw
     * art in a square box and stretch it, so 16:9 video thumbnails looked squashed.
     */
    internal fun squareArtwork(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (source.width == source.height && largest <= MAX_ARTWORK_DIMENSION) return source
        val side = minOf(largest, MAX_ARTWORK_DIMENSION)
        val scale = side.toFloat() / largest
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val left = (side - width) / 2
        val top = (side - height) / 2
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { square ->
            Canvas(square).drawBitmap(
                source,
                null,
                Rect(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

class LegacyMediaKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CarPlayMediaKeys.onLegacyMediaButton(intent)
    }
}
