// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * 车机应用安装 / 卸载 / 更新时刷新主页应用列表。
 *
 * 起因：应用网格只在 Fragment 构建和「管理」弹窗时枚举，装 / 卸 APK 后列表一直停留在旧状态。
 * 这里按 AOSP 主屏同一节奏监听包变更广播，收到后让宿主失效枚举缓存并重绘网格。
 *
 * 注册 / 注销幂等，跟随 Fragment 可见性（onResume / onHiddenChanged / onPause）走，避免隐藏页
 * 或销毁后 receiver 仍存活。
 */
internal class AppInstallWatcher(
    private val app: Context,
    private val onPackageChanged: () -> Unit,
) {
    private var started = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!started) return
            val action = intent.action ?: return
            when (action) {
                // 单个包安装 / 替换 / 卸载；REPLACED 覆盖「升级安装」。
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED,
                // 外部存储（SD 卡）上应用挂载 / 卸载导致整批可达性变化。
                Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE,
                Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE,
                -> onPackageChanged()
            }
        }
    }

    /** Attach path: idempotent — already-started calls no-op, so hide/show reattach is safe. */
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // KitKat: data scheme "package" 才能收到具体包事件的过滤；外部应用事件用不含 scheme 的 filter。
            addDataScheme("package")
        }
        app.registerReceiver(receiver, filter)
        // 外部存储广播不携带 package scheme，单独注册一份不带 data 的 filter。
        runCatching {
            val ext = IntentFilter().apply {
                addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
                addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
            }
            app.registerReceiver(receiver, ext)
        }
    }

    /** Detach path: idempotent — safe to call on Fragment hide even if the view never attached. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { app.unregisterReceiver(receiver) }
    }
}
