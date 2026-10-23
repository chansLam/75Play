package com.shihab.diplay.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.host.R
import com.shihab.diplay.network.WifiP2pChannels
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.ui.settings.GlassSettingsRows

/**
 * 主页连接中心三 Tab（Wi‑Fi Direct / 车机热点 / 局域网）的内容区。
 * 由 [FragmentHome] 持有；行为与抽出前一致。
 */
internal class HomeConnectionTabs(
    private val home: FragmentHome,
) {
    private val host get() = home.requireActivity() as DiPlayHost

    /** 已创建的 Wi-Fi 扫描监听：Fragment 被 hide 时统一 stop，重新可见且视图仍附着时 start。 */
    private val wifiWatchers = mutableListOf<Pair<View, WifiListWatcher>>()

    private fun dp(v: Int) = GlassColors.dp(home.requireContext(), v)

    private fun refresh() = home.refreshFromHost()

    fun build(ctx: android.content.Context, tab: String): View = when (tab) {
        "hotspot" -> buildHotspotTab(ctx)
        "lan" -> buildLanTab(ctx)
        else -> buildDirectTab(ctx)
    }

    private fun buildDirectTab(ctx: android.content.Context): View {
        val textSize = LayoutMetrics.homeBodyTextSize(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val wifiOn = host.isWifiEnabled()
        val wifiRow = GlassSettingsRows.rowWrap(ctx)
        wifiRow.addView(
            label(ctx, ctx.getString(R.string.home_car_wifi), textSize, GlassColors.INK).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        wifiRow.addView(GlassSettingsRows.capVal(ctx, if (wifiOn) ctx.getString(R.string.home_enabled) else ctx.getString(R.string.home_disabled), if (wifiOn) GlassColors.SUCCESS else GlassColors.WARNING))
        wifiRow.isClickable = true
        wifiRow.isFocusable = true
        wifiRow.setOnClickListener {
            if (wifiOn) return@setOnClickListener
            Thread({
                host.enableWifi()
                home.view?.post { if (home.isAdded) refresh() }
            }, "diplay-enable-wifi").apply { isDaemon = true }.start()
        }
        FocusSupport.attachRow(wifiRow)
        col.addView(wifiRow)
        val chRow = GlassSettingsRows.rowWrap(ctx)
        chRow.addView(
            label(ctx, ctx.getString(R.string.home_wifi_direct_channel), textSize, GlassColors.INK).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val channelCap = GlassSettingsRows.capVal(ctx, directChannelLabel(ctx), arrow = true)
        chRow.addView(channelCap)
        val openChannel = View.OnClickListener { promptWifiDirectChannel(channelCap) }
        channelCap.setOnClickListener(openChannel)
        chRow.isClickable = true
        chRow.isFocusable = true
        chRow.setOnClickListener(openChannel)
        FocusSupport.attachRow(chRow)
        col.addView(chRow)
        return col
    }

    private fun bandLabel(ctx: android.content.Context, channel: Int): String =
        ctx.getString(if (channel <= 11) R.string.s_2_4_ghz else R.string.s_5_ghz)

    private fun directChannelLabel(ctx: android.content.Context): String {
        val cur = AirPlayPersistence.loadWifiP2pPreferredChannel(ctx)
        return if (cur == WifiP2pChannels.AUTO) ctx.getString(R.string.home_channel_auto)
        else ctx.getString(R.string.home_channel_value, cur, bandLabel(ctx, cur))
    }

    private fun promptWifiDirectChannel(anchor: View) {
        val ctx = anchor.context
        val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
        val cur = AirPlayPersistence.loadWifiP2pPreferredChannel(ctx)
        val labels = choices.map {
            if (it == WifiP2pChannels.AUTO) ctx.getString(R.string.home_channel_auto)
            else ctx.getString(R.string.home_channel_value, it, bandLabel(ctx, it))
        }
        GlassDropdown.show(
            anchor = anchor,
            labels = labels,
            selected = choices.indexOf(cur).coerceAtLeast(0),
            syncAnchorText = false,
            onSelect = { idx ->
                AirPlayPersistence.saveWifiP2pPreferredChannel(ctx, choices[idx])
                refresh()
            },
        )
    }

    private fun buildHotspotTab(ctx: android.content.Context): View {
        val textSize = LayoutMetrics.homeBodyTextSize(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (!host.isCarHotspotEnabled()) {
            col.addView(GlassSettingsRows.ghostButton(ctx, ctx.getString(R.string.home_open_car_hotspot), cornerRadiusDp = 10) {
                Thread({
                    host.openCarHotspot()
                    home.view?.post { if (home.isAdded) refresh() }
                }, "diplay-open-hotspot").apply { isDaemon = true }.start()
            }, lpFull(ctx, ViewGroup.LayoutParams.WRAP_CONTENT))
            return col
        }
        val ssid = host.carHotspotSsid()
        val pass = host.carHotspotPassphrase()
        fun statusLabel(title: String): TextView = label(ctx, title, textSize, GlassColors.INK).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sRow = GlassSettingsRows.rowWrap(ctx)
        sRow.addView(statusLabel(ctx.getString(R.string.hotspot_status)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sRow.addView(GlassSettingsRows.capVal(ctx, ctx.getString(R.string.home_enabled), GlassColors.SUCCESS))
        col.addView(sRow)
        val ssRow = GlassSettingsRows.rowWrap(ctx)
        ssRow.addView(statusLabel(ctx.getString(R.string.hotspot_ssid)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ssRow.addView(GlassSettingsRows.capVal(ctx, ssid.ifBlank { ctx.getString(R.string.home_not_set) }))
        col.addView(ssRow)
        val pwRow = GlassSettingsRows.rowWrap(ctx)
        pwRow.addView(statusLabel(ctx.getString(R.string.hotspot_password)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pwRow.addView(GlassSettingsRows.capVal(ctx, pass.ifBlank { ctx.getString(R.string.home_not_set) }))
        col.addView(pwRow)
        return col
    }

    private fun buildLanTab(ctx: android.content.Context): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val systemSsid = host.currentWifiSsid()
        val cur = when {
            systemSsid.isNotBlank() -> systemSsid
            home.pendingLanSsid.isNotBlank() -> home.pendingLanSsid
            else -> ""
        }
        val connecting = systemSsid.isBlank() && home.pendingLanSsid.isNotBlank()
        if (cur.isNotBlank()) {
            val wifiRow = GlassSettingsRows.rowWrap(ctx)
            wifiRow.addView(
                label(ctx, ctx.getString(R.string.home_connected_wifi), LayoutMetrics.homeBodyTextSize(ctx), GlassColors.INK),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            wifiRow.addView(GlassSettingsRows.capVal(ctx, cur, if (connecting) GlassColors.CAP_TEXT else GlassColors.SUCCESS))
            col.addView(wifiRow)
            col.addView(
                GlassSettingsRows.ghostButton(ctx, ctx.getString(R.string.home_choose_other_wifi), cornerRadiusDp = 10) {
                    showWifiPickerDialog(excludeSsid = systemSsid.ifBlank { cur })
                },
                lpFull(ctx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = LayoutMetrics.heroGap(ctx)
                },
            )
            return col
        }
        col.addView(label(ctx, ctx.getString(R.string.home_car_not_connected_wifi), LayoutMetrics.homeMutedTextSize(ctx), GlassColors.WARNING, true))
        col.addView(buildWifiListScroll(ctx, excludeSsid = ""),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutMetrics.homeWifiListH(ctx),
            ))
        return col
    }

    private fun showWifiPickerDialog(excludeSsid: String) {
        val ctx = home.requireContext()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.addView(
            label(ctx, ctx.getString(R.string.home_scanning), LayoutMetrics.homeMutedTextSize(ctx), GlassColors.TEXT_BODY).apply {
                setPadding(0, dp(4), 0, dp(4))
            },
        )
        val dialog = DiPlayDialog.showCustom(
            ctx,
            ctx.getString(R.string.home_choose_other_wifi),
            list,
            okText = ctx.getString(R.string.cancel),
            fillMaxHeight = true,
        )
        watchWifiList(list, excludeSsid, holdsPage = false) { networks ->
            if (!home.isAdded || !dialog.isShowing) return@watchWifiList
            list.removeAllViews()
            if (networks.isEmpty()) {
                list.addView(
                    label(ctx, ctx.getString(R.string.home_no_wifi_networks), LayoutMetrics.homeMutedTextSize(ctx), GlassColors.TEXT_MUTED).apply {
                        setPadding(0, dp(8), 0, dp(8))
                    },
                )
            } else {
                networks.forEach { n ->
                    list.addView(wifiRow(ctx, n) {
                        dialog.dismiss()
                        onWifiSelected(n)
                    })
                }
            }
        }
    }

    private fun buildWifiListScroll(ctx: android.content.Context, excludeSsid: String): ScrollView {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.addView(
            label(ctx, ctx.getString(R.string.home_scanning), LayoutMetrics.homeMutedTextSize(ctx), GlassColors.TEXT_BODY).apply {
                setPadding(0, dp(8), 0, 0)
            },
        )
        val listScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            addView(list)
        }
        watchWifiList(listScroll, excludeSsid, holdsPage = true) { networks ->
            if (!home.isAdded || home.currentTab != "lan" || home.pendingLanSsid.isNotBlank()) return@watchWifiList
            val scrollY = listScroll.scrollY
            list.removeAllViews()
            if (networks.isEmpty()) {
                list.addView(
                    label(ctx, ctx.getString(R.string.home_no_wifi_networks), LayoutMetrics.homeMutedTextSize(ctx), GlassColors.TEXT_MUTED).apply {
                        setPadding(0, dp(8), 0, 0)
                    },
                )
            } else {
                networks.forEach { n -> list.addView(wifiRow(ctx, n)) }
            }
            listScroll.post { listScroll.scrollTo(0, scrollY) }
        }
        return listScroll
    }

    /**
     * 列表在屏幕上时持续刷新：先画出缓存，扫描结果一到就更新，并按系统设置的 10 秒再扫。
     * [holdsPage] 为 true 时告诉主页不要在状态刷新里拆掉这块列表。
     */
    private fun watchWifiList(
        anchor: View,
        excludeSsid: String,
        holdsPage: Boolean,
        render: (List<WifiEntry>) -> Unit,
    ) {
        val watcher = WifiListWatcher(anchor.context.applicationContext, host) { networks ->
            val shown = networks.filterNot {
                excludeSsid.isNotBlank() && it.ssid.equals(excludeSsid, ignoreCase = true)
            }
            render(shown)
        }
        wifiWatchers.add(anchor to watcher)
        anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (holdsPage) home.wifiListLive = true
                // Fragment 被 hide 期间视图不会 detach；附着时若页仍隐藏则不启动扫描监听。
                if (!home.isHidden) watcher.start()
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (holdsPage) home.wifiListLive = false
                watcher.stop()
            }
        })
        if (anchor.isAttachedToWindow) {
            if (holdsPage) home.wifiListLive = true
            if (!home.isHidden) watcher.start()
        }
    }

    /** Fragment 被 hide：停掉所有扫描监听（stop 幂等，重复调用安全）。 */
    internal fun stopWifiWatchers() {
        wifiWatchers.forEach { (_, watcher) -> watcher.stop() }
    }

    /** 重新可见：仅对当前仍附着在窗口上的视图重启监听（start 幂等，已启动的不会重复注册）。 */
    internal fun restartAttachedWifiWatchers() {
        wifiWatchers.forEach { (anchor, watcher) -> if (anchor.isAttachedToWindow) watcher.start() }
    }

    private fun wifiRow(
        ctx: android.content.Context,
        net: WifiEntry,
        onClick: () -> Unit = { onWifiSelected(net) },
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            FocusSupport.attachRow(this)
        }
        row.addView(label(ctx, net.ssid, LayoutMetrics.homeBodyTextSize(ctx), GlassColors.INK).apply { setPadding(0, 0, dp(8), 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (net.secured) {
            // 锁图标是装饰性提示，不进无障碍遍历（SSID 与「点按连接」文案已自解释）。
            row.addView(label(ctx, "🔒", 13f, GlassColors.TEXT_MUTED).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        row.addView(label(ctx, ctx.getString(R.string.home_tap_to_connect), LayoutMetrics.homeMutedTextSize(ctx), GlassColors.CAP_TEXT))
        return row
    }

    private fun onWifiSelected(net: WifiEntry) {
        val ctx = home.requireContext()
        val savedPass = if (AirPlayPersistence.loadExistingWifiSsid(ctx) == net.ssid)
            AirPlayPersistence.loadExistingWifiPassphrase(ctx) else ""
        if (!net.secured || savedPass.isNotEmpty()) {
            connectLanTo(net.ssid, savedPass)
            return
        }
        DiPlayDialog.showInput(
            ctx, ctx.getString(R.string.home_connect_wifi),
            hint = ctx.getString(R.string.home_wifi_password_hint, net.ssid),
            initial = "",
            assist = net.ssid,
            save = ctx.getString(R.string.home_save_and_connect_wifi),
            validator = { null },
            onSave = { pass -> connectLanTo(net.ssid, pass) },
        )
    }

    private fun connectLanTo(ssid: String, pass: String) {
        val ctx = home.requireContext()
        AirPlayPersistence.saveExistingWifiCredentials(ctx, ssid, pass)
        AirPlayPersistence.saveWirelessHotspotMode(ctx, WirelessHotspotMode.EXISTING_WIFI)
        home.pendingLanSsid = ssid
        home.lanConnected = true
        home.currentTab = "lan"
        refresh()
        host.showGlassToast(ctx.getString(R.string.home_connecting_wifi, ssid), DiPlayToast.Type.INFO)
        Thread {
            val ok = host.connectToWifi(ssid, pass)
            home.view?.post {
                if (!home.isAdded) return@post
                if (ok) {
                    home.pendingLanSsid = ""
                    home.lanConnected = true
                    host.showGlassToast(ctx.getString(R.string.home_connected_wifi) + " · " + ssid, DiPlayToast.Type.SUCCESS)
                    refresh()
                    host.connect(true)
                } else {
                    val now = host.currentWifiSsid()
                    if (now.equals(ssid, ignoreCase = true)) {
                        home.pendingLanSsid = ""
                        home.lanConnected = true
                        refresh()
                        host.connect(true)
                    } else {
                        home.pendingLanSsid = ""
                        home.lanConnected = false
                        // A failed join of this SSID must not wipe the password of the network
                        // that is still up. Clear only the attempt we just saved.
                        if (AirPlayPersistence.loadExistingWifiSsid(ctx).equals(ssid, ignoreCase = true)) {
                            AirPlayPersistence.saveExistingWifiCredentials(ctx, ssid, "")
                        }
                        host.showGlassToast(ctx.getString(R.string.home_connect_wifi_failed, ssid), DiPlayToast.Type.ERROR)
                        refresh()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun label(ctx: android.content.Context, text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            this.textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun lpFull(ctx: android.content.Context, h: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

    // NU-04：cap / ghostButton / rowWrap 已抽到 [GlassSettingsRows]（ghostButton 圆角 14 参数化）。
}
