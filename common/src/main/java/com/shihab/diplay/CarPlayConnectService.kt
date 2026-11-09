package com.shihab.diplay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.SessionLogFile
import com.shihab.diplay.compat.realSdkInt
import com.shihab.diplay.connect.ConnectBroadcasts
import com.shihab.diplay.host.CarPlayBackgroundSession
import com.shihab.diplay.host.R
import com.shihab.diplay.util.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the wireless CarPlay handshake in the background (replacing the old connect-only
 * CarPlayHostActivity path that flashed a black window on API 19 head units).
 *
 * Lifecycle:
 *  - DiPlayActivity starts us with [ACTION_START]; we build the controller stack, store it in
 *    [CarPlayBackgroundSession], and forward connection stage changes as DiPlay broadcasts.
 *  - On success (onSessionActive) we broadcast ACTION_CONNECTED and keep running: DiPlay then
 *    brings CarPlayHostActivity to the foreground, which adopts the already-connected controller
 *    via attachUi() and finally stops this service.
 *  - On failure or user cancel we tear the stack down, broadcast ACTION_CONNECT_FAILED and stop.
 *
 * The controller itself is process-global: we only ever hand it off — never rebuild it.
 */
class CarPlayConnectService : Service() {
    private val connectGeneration = AtomicInteger()

    @Volatile private var started = false
    @Volatile private var succeeded = false
    private var controller: com.shihab.diplay.orchestration.CarPlayController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var controlReceiver: BroadcastReceiver? = null
    private var connectLog: SessionLogFile? = null
    private var preserveSessionService = false
    private val waitingForParkedRelease = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        connecting = true
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diplay:carplay-connect")
                .also { it.acquire(CONNECT_WAKELOCK_TIMEOUT_MILLIS) }
        }
        controlReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ConnectBroadcasts.ACTION_CONNECT_CANCEL, ACTION_CANCEL -> cancelConnect()
                }
            }
        }
        runCatching {
            registerReceiver(
                controlReceiver,
                IntentFilter().apply {
                    addAction(ConnectBroadcasts.ACTION_CONNECT_CANCEL)
                    addAction(ACTION_CANCEL)
                },
            )
        }
        // The handshake (wired or wireless) happens before CarPlayHostActivity is created, so the
        // host's diplay.log does not exist yet. Keep our own connect.log so the controller's
        // debug lines (WIRED-CHAIN etc.) are still captured in the exported diagnostic report.
        connectLog = SessionLogFile(File(filesDir, "logs/connect.log"), "connect-prev").also {
            it.reset("DiPlay connect service log started ${System.currentTimeMillis()}")
            // Let the diagnostic exporter flush our pending lines before reading connect.log.
            if (ActiveSessionLog.current == null) ActiveSessionLog.current = it
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_CANCEL -> {
                preserveSessionService = false
                cancelConnect()
                return START_NOT_STICKY
            }
            ACTION_LINK_LOST -> {
                // Recovery owns DiPlaySessionService. Cancel this transport attempt without
                // stopping the watcher that must notice the next cable insertion.
                preserveSessionService = true
                cancelConnect()
                return START_NOT_STICKY
            }
            else -> beginConnect()
        }
        return START_NOT_STICKY
    }

    private fun beginConnect() {
        if (started) return // idempotent: a connection attempt is already in flight
        // An active session already exists (e.g. DiPlay relaunched after a handoff); just tell DiPlay.
        // A restart can leave a stop action with no live controller (snapshot null); that is not a
        // reusable session, so fall through and build a real controller instead of broadcasting a
        // fake "connected" that leaves the shell with nothing to open.
        if (CarPlayBackgroundSession.hasSession() && CarPlayBackgroundSession.snapshot() != null) {
            succeeded = true
            connecting = false
            runCatching { sendBroadcast(ConnectBroadcasts.connected(this)) }
            stopSelf()
            return
        }
        // A reused instance must not keep the link-lost exception into this new attempt.
        preserveSessionService = false
        if (CarPlayBackgroundSession.isReleasing()) {
            waitForParkedRelease()
            return
        }
        started = true
        if (realSdkInt() >= Build.VERSION_CODES.M) {
            val missingPermission = listOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ).firstOrNull {
                checkPermission(it, android.os.Process.myPid(), android.os.Process.myUid()) !=
                    PackageManager.PERMISSION_GRANTED
            }
            if (missingPermission != null) {
                broadcastFailed(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
                teardown()
                stopSelf()
                return
            }
        }

        val built = runCatching {
            CarPlayConnectStack.build(
                ctx = this,
                listener = forwardingListener,
                onStage = { stage ->
                    runCatching {
                        sendBroadcast(ConnectBroadcasts.progress(this, stage))
                    }
                },
                onFailed = { message ->
                    broadcastFailed(message)
                    teardown()
                    stopSelf()
                },
            )
        }.getOrElse { error ->
            AppLog.w(TAG, "stack build failed", error)
            broadcastFailed(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
            stopSelf()
            return
        }

        controller = built.controller
        val stored = CarPlayBackgroundSession.store(
            built.controller, built.sink,
            built.display.windowWidth, built.display.windowHeight,
            this, built.display,
        ) { completion ->
            // Invoked by CarPlayBackgroundSession.stop() once the host has taken over or the user
            // disconnects from the session notification. Close the controller, then complete.
            runCatching { built.controller.close() }
            // Clear so hasSession() becomes false after cancel/disconnect-while-connecting;
            // host handoff replaces this stopAction via store() before adopting.
            CarPlayBackgroundSession.clear(built.controller)
            completion()
        }
        if (!stored) {
            AppLog.i(TAG, "connect deferred; a parked session is still releasing")
            controller = null
            runCatching { built.controller.close() }
            started = false
            if (CarPlayBackgroundSession.isReleasing()) {
                waitForParkedRelease()
            } else {
                broadcastFailed(getString(R.string.connection_failed))
                teardown()
                stopSelf()
            }
            return
        }

        // Identity install runs off the main thread and can still be in progress when the user
        // taps connect. Starting MFi before that rename finishes reports a missing directory.
        val launch = built.controller
        val generation = connectGeneration.get()
        Thread({
            val authError = runCatching {
                DiPlayBootstrap.ensure(applicationContext, AirPlayPersistence.loadMfiTarget(applicationContext))
            }.exceptionOrNull()
            Handler(Looper.getMainLooper()).post {
                if (generation != connectGeneration.get() || controller !== launch) return@post
                if (authError != null) {
                    AppLog.e(TAG, "CarPlay authentication could not be loaded", authError)
                    broadcastFailed(getString(R.string.setup_error_auth))
                    teardown()
                    stopSelf()
                    return@post
                }
                try {
                    launch.start()
                } catch (error: RuntimeException) {
                    AppLog.w(TAG, "controller start failed", error)
                    broadcastFailed(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
                    teardown()
                    stopSelf()
                }
            }
        }, "diplay-mfi-ready").apply { isDaemon = true }.start()
    }

    /**
     * The previous parked controller can still own USB after its snapshot is gone. Starting
     * another stack in that window fails the new attempt and can wedge the bus. Wait, then
     * retry this connect instead of building a second controller.
     */
    private fun waitForParkedRelease() {
        if (!waitingForParkedRelease.compareAndSet(false, true)) return
        val generation = connectGeneration.get()
        Thread({
            val deadline = System.nanoTime() + PARKED_RELEASE_WAIT_MILLIS * 1_000_000L
            while (
                CarPlayBackgroundSession.isReleasing() &&
                System.nanoTime() < deadline &&
                generation == connectGeneration.get()
            ) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
            val released = !CarPlayBackgroundSession.isReleasing()
            Handler(Looper.getMainLooper()).post {
                waitingForParkedRelease.set(false)
                if (generation != connectGeneration.get()) return@post
                if (!released) {
                    broadcastFailed(getString(R.string.connection_failed))
                    teardown()
                    stopSelf()
                    return@post
                }
                beginConnect()
            }
        }, "diplay-wait-parked-release").apply { isDaemon = true }.start()
    }

    private val forwardingListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            // Worker thread. Never touch views here — broadcasts only.
            if (succeeded) return
            succeeded = true
            connecting = false
            // The shell status reads this flag. The host adopts an already-active session and does
            // not get another onSessionActive, so leaving it false kept the pill on「连接中」.
            CarPlayBackgroundSession.active = true
            runCatching { startService(Intent(this@CarPlayConnectService, DiPlaySessionService::class.java)) }
            runCatching { sendBroadcast(ConnectBroadcasts.connected(this@CarPlayConnectService)) }
            // Keep running: the host adopts the controller next and stops us with stopService().
        }

        override fun onSessionEnded(session: AirPlaySession) {
            // Only fatal for us if it happens before the handshake completed (once the host adopts,
            // its own listener receives these events).
            if (!succeeded) {
                broadcastFailed(getString(R.string.connection_failed))
                teardown()
                stopSelf()
            }
        }

        override fun onTransportError(message: String) {
            if (!succeeded) {
                broadcastFailed(CarPlayConnectText.friendlyStage(this@CarPlayConnectService, message))
                teardown()
                stopSelf()
            }
        }

        override fun onDebugLog(message: String) {
            AppLog.i(TAG, message)
            connectLog?.append(message)
        }
    }

    /** True once the host Activity stored this controller and replaced us as owner. */
    private fun hostAdopted(): Boolean {
        val current = controller ?: return false
        val snap = CarPlayBackgroundSession.snapshot() ?: return false
        return snap.controller === current && !CarPlayBackgroundSession.isOwner(this)
    }

    private fun cancelConnect() {
        // Handshake success is not adoption. Cancel before the host takes the session
        // must still close the wireless stack. After adoption, leave the live session.
        if (hostAdopted()) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return
        }
        teardown()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun teardown() {
        connectGeneration.incrementAndGet()
        val current = controller
        if (current != null) {
            CarPlayBackgroundSession.clear(current)
            runCatching { current.close() }
        }
        controller = null
        // Allow a fresh startConnectFlow to begin after a cancel/failure.
        started = false
        succeeded = false
        if (!preserveSessionService) {
            runCatching { stopService(Intent(this, DiPlaySessionService::class.java)) }
        }
    }

    private fun broadcastFailed(message: String) {
        // 必须在广播前清掉 connecting：否则 DiPlay 收到失败、刷新主页时仍读到 true，
        // 会一直停在「连接中 / 正在连接 iPhone…」（onDestroy 清标志太晚，且主页不会再刷）。
        markIdle()
        runCatching { sendBroadcast(ConnectBroadcasts.failed(this, message)) }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, DiPlayActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CarPlayConnectService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The channel class is loaded by name so Dalvik does not verify it on KitKat.
        val builder = if (realSdkInt() >= Build.VERSION_CODES.O) oreoBuilder() else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_carplay)
            .setContentTitle(getString(R.string.connecting_to_your_iphone))
            .setContentText(getString(R.string.connecting_carplay_please_wait))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, getString(android.R.string.cancel), cancelIntent)
            .build()
    }

    private fun oreoBuilder(): Notification.Builder {
        val type = Class.forName("com.shihab.diplay.OreoConnectNotification")
        return type.getMethod("builder", Context::class.java).invoke(null, this) as Notification.Builder
    }

    override fun onDestroy() {
        // stopService after the host adopts must not close the live controller.
        // A cancel or a service death before adoption still tears the wireless stack down.
        if (!hostAdopted()) teardown()
        try { controlReceiver?.let { unregisterReceiver(it) } } catch (t: Exception) { AppLog.w(TAG, "unregister control receiver failed", t) }
        controlReceiver = null
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        connectLog?.close()
        if (ActiveSessionLog.current === connectLog) ActiveSessionLog.current = null
        connectLog = null
        connecting = false
        running = false
        started = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.shihab.diplay.CONNECT_START"
        const val ACTION_CANCEL = "com.shihab.diplay.CONNECT_CANCEL_SERVICE"
        const val ACTION_LINK_LOST = "com.shihab.diplay.CONNECT_LINK_LOST"
        private const val PARKED_RELEASE_WAIT_MILLIS = 5_000L

        /** Process-global "a connection attempt is in flight" flag, read by the host's onCreate. */
        @Volatile
        var connecting = false
            private set

        /** True while an actual service instance exists, including the post-handshake handoff window. */
        @Volatile
        var running = false
            private set

        /** Clear [connecting] immediately on cancel/failure so home UI does not stay on「连接中」. */
        fun markIdle() {
            connecting = false
        }

        private const val TAG = "CarPlayConnect"
        private const val NOTIF_ID = 4711
        private const val CONNECT_WAKELOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L
    }
}
