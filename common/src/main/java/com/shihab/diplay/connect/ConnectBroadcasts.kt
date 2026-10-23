package com.shihab.diplay.connect

import android.content.Context
import android.content.Intent

/**
 * Shared connection-state broadcast contract between [com.shihab.diplay.CarPlayConnectService],
 * the shell, and (legacy) Host listeners. String action values must stay stable.
 */
object ConnectBroadcasts {
    const val ACTION_CONNECTED = "com.shihab.diplay.CONNECTED"
    const val ACTION_CONNECT_FAILED = "com.shihab.diplay.CONNECT_FAILED"
    const val ACTION_CONNECT_PROGRESS = "com.shihab.diplay.CONNECT_PROGRESS"
    const val ACTION_CONNECT_CANCEL = "com.shihab.diplay.CONNECT_CANCEL"

    const val EXTRA_FAILURE_MESSAGE = "failure_message"
    const val EXTRA_CONNECT_STAGE = "connect_stage"

    fun progress(context: Context, stage: String): Intent =
        Intent(ACTION_CONNECT_PROGRESS).setPackage(context.packageName)
            .putExtra(EXTRA_CONNECT_STAGE, stage)

    fun connected(context: Context): Intent =
        Intent(ACTION_CONNECTED).setPackage(context.packageName)

    fun failed(context: Context, message: String): Intent =
        Intent(ACTION_CONNECT_FAILED).setPackage(context.packageName)
            .putExtra(EXTRA_FAILURE_MESSAGE, message)

    fun cancel(context: Context): Intent =
        Intent(ACTION_CONNECT_CANCEL).setPackage(context.packageName)
}
