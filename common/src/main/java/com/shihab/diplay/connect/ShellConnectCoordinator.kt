package com.shihab.diplay.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.shihab.diplay.CarPlayConnectService
import com.shihab.diplay.host.CarPlayBackgroundSession
import com.shihab.diplay.host.R

/**
 * Owns the shell-side connect flow: service start/cancel and connect broadcasts.
 * Progress is reported through [stageLabel] for the home orb; there is no progress dialog.
 * [DiPlayActivity] wires lifecycle and [onConnected]/[onFailed]/[onRefreshHome] callbacks.
 */
class ShellConnectCoordinator(
    private val context: Context,
    private val onConnected: () -> Unit,
    private val onFailed: (String) -> Unit,
    private val onRefreshHome: () -> Unit,
) {
    /** Latest stage line while a connect attempt is running. */
    var stageLabel: String? = null
        private set
    private var connecting = false
    private var serviceStarted = false
    private var connectStateReceiver: BroadcastReceiver? = null

    fun isConnecting(): Boolean = connecting || CarPlayConnectService.connecting

    /** Home orb shows「连接中」while the previous session is still releasing the radio. */
    fun prepare() {
        connecting = true
        if (stageLabel.isNullOrBlank()) {
            stageLabel = context.getString(R.string.connecting_to_your_iphone)
        }
        registerReceiver()
        onRefreshHome()
    }

    fun start() {
        prepare()
        if (serviceStarted) return
        serviceStarted = true
        val service = Intent(context, CarPlayConnectService::class.java)
            .setAction(CarPlayConnectService.ACTION_START)
        runCatching { context.startService(service) }.onFailure {
            serviceStarted = false
            connecting = false
            stageLabel = null
            onRefreshHome()
            onFailed(context.getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
    }

    fun cancel() {
        connecting = false
        serviceStarted = false
        stageLabel = null
        CarPlayConnectService.markIdle()
        CarPlayBackgroundSession.forceReset()
        unregisterReceiver()
        runCatching {
            context.startService(
                Intent(context, CarPlayConnectService::class.java)
                    .setAction(CarPlayConnectService.ACTION_CANCEL),
            )
        }
        runCatching { context.sendBroadcast(ConnectBroadcasts.cancel(context)) }
        runCatching { context.stopService(Intent(context, CarPlayConnectService::class.java)) }
        onRefreshHome()
    }

    fun release() {
        connecting = false
        serviceStarted = false
        stageLabel = null
        unregisterReceiver()
    }

    private fun registerReceiver() {
        unregisterReceiver()
        val filter = IntentFilter().apply {
            addAction(ConnectBroadcasts.ACTION_CONNECTED)
            addAction(ConnectBroadcasts.ACTION_CONNECT_FAILED)
            addAction(ConnectBroadcasts.ACTION_CONNECT_PROGRESS)
        }
        connectStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ConnectBroadcasts.ACTION_CONNECT_PROGRESS -> {
                        val stage = intent.getStringExtra(ConnectBroadcasts.EXTRA_CONNECT_STAGE)
                        if (!stage.isNullOrBlank()) {
                            stageLabel = stage
                            onRefreshHome()
                        }
                    }
                    ConnectBroadcasts.ACTION_CONNECTED -> {
                        connecting = false
                        serviceStarted = false
                        stageLabel = null
                        unregisterReceiver()
                        onRefreshHome()
                        onConnected()
                    }
                    ConnectBroadcasts.ACTION_CONNECT_FAILED -> {
                        val msg = intent.getStringExtra(ConnectBroadcasts.EXTRA_FAILURE_MESSAGE)
                            ?: context.getString(R.string.connection_failed)
                        connecting = false
                        serviceStarted = false
                        stageLabel = null
                        CarPlayConnectService.markIdle()
                        CarPlayBackgroundSession.forceReset()
                        unregisterReceiver()
                        onRefreshHome()
                        onFailed(msg)
                    }
                }
            }
        }
        runCatching { context.registerReceiver(connectStateReceiver, filter) }
    }

    private fun unregisterReceiver() {
        connectStateReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        connectStateReceiver = null
    }
}
