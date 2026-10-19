package com.shihab.diplay.ui

import java.util.concurrent.TimeUnit
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarAppCatalog
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.host.ImmersiveBars
import com.shihab.diplay.host.R
import com.shihab.diplay.network.WifiP2pChannels
import com.shihab.diplay.orchestration.WirelessHotspotMode

/**
 * FragmentHome: two columns.
 * Left column (top to bottom): a small Bluetooth status card + a scrollable connection card
 * with the three-tab (WiFi direct / car hotspot / LAN) actions.
 * Right column: the head-unit software list in a grid (tap to launch, long-press to uninstall),
 * scrollable when it overflows. Quick settings now live in the Settings page.
 */
class FragmentHome : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { refresh() }
    private val host get() = requireActivity() as DiPlayHost

    private lateinit var statusText: TextView
    private var statusPillBg: android.graphics.drawable.GradientDrawable? = null
    private var bluetoothDeviceText: TextView? = null
    private lateinit var disconnectBtn: Button
    private lateinit var heroBtn: View
    private lateinit var heroMain: TextView
    private lateinit var heroSmall: TextView

    private var currentTab = "direct"
    private lateinit var tabContent: LinearLayout
    private val tabButtons = mutableMapOf<String, View>()
    private var lanConnected = false
    /** 正在连 / 刚连上但系统 SSID 尚未就绪时，用此 SSID 立刻刷新「已连接」行。 */
    @Volatile
    private var pendingLanSsid: String = ""
    /** 车机软件网格；管理弹窗勾选后据此重绘。 */
    private var appGrid: LinearLayout? = null
    // 应用网格拖拽排序状态
    private var visibleApps: List<CarAppCatalog.App> = emptyList()
    private var dragIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressActive = false
    private var dragView: View? = null
    private val longPressRunnable = object : Runnable {
        override fun run() {
            val v = dragView ?: return
            dragging = true
            longPressActive = false
            v.alpha = 0.55f
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    private fun dp(v: Int) = GlassColors.dp(requireContext(), v)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val ctx = requireContext()
        val pad = LayoutMetrics.pagePad(ctx)
        val gap = LayoutMetrics.colGap(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // 复用启动期后台解码的背景图，避免主线程再次解码 1920×1080 大图阻塞主页首帧；
            // 未就绪时回退到 drawable（暗/浅主题）。仅暗色主页复用暗背景缓存。
            val cached = com.shihab.diplay.StartupCache.darkBg
            if (cached != null && GlassColors.isDark) {
                setBackground(android.graphics.drawable.BitmapDrawable(ctx.resources, cached))
            } else {
                setBackgroundResource(GlassColors.bgHome())
            }
            setPadding(pad, pad, pad, pad)
        }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(buildStatusCard(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 车机均分：连接中心与应用格同权，主操作区不再细长。
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.homeConnectWeight(ctx)))
        root.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(gap, 1) })
        root.addView(buildAppListCard(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.homeAppsWeight(ctx)))
        return root
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.postDelayed(tick, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }


    /** Show the live connected device on startup (even before a phone was saved), else the picker hint. */
    private fun bluetoothDeviceLabel(): String =
        host.connectedBluetoothDeviceName().ifBlank { host.chooseIphoneTitle() }

    /** 连接中心：车载大触控；整卡填高，仅模式内容区可滚。 */
    private fun buildStatusCard(ctx: android.content.Context): View {
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
            setBackgroundResource(GlassColors.cardBg())
            setPadding(
                LayoutMetrics.cardPadH(ctx), LayoutMetrics.cardPadV(ctx),
                LayoutMetrics.cardPadH(ctx), LayoutMetrics.cardPadV(ctx),
            )
        }
        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutMetrics.rowMinH(ctx) / 2
        }
        headRow.addView(label(ctx, ctx.getString(R.string.home_connection_center), LayoutMetrics.cardTitleSize(ctx), GlassColors.INK, true))
        headRow.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 1, 1f) })
        statusText = TextView(ctx).apply {
            text = ctx.getString(R.string.home_loading)
            textSize = LayoutMetrics.statusPillTextSize(ctx)
            setTextColor(GlassColors.ACCENT2)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(LayoutMetrics.statusPillPadH(ctx), LayoutMetrics.statusPillPadV(ctx), LayoutMetrics.statusPillPadH(ctx), LayoutMetrics.statusPillPadV(ctx))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusPillBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(GlassColors.ACCENT_SOFT_FILL)
            setStroke(dp(1), GlassColors.ACCENT_SOFT_STROKE)
        }
        statusText.background = statusPillBg
        headRow.addView(statusText)
        content.addView(headRow)

        heroBtn = heroButton(ctx, ctx.getString(R.string.home_connect_carplay), ctx.getString(R.string.home_wireless_projection)) {
            when {
                host.isCarPlayActive() -> host.openProjection()
                host.isCarPlayConnecting() -> { /* 连接弹窗进行中，勿重复拉起 */ }
                else -> host.connect(true)
            }
        }
        val heroGap = LayoutMetrics.heroGap(ctx)
        content.addView(heroBtn, lpFull(ctx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = heroGap; bottomMargin = heroGap
        })

        // 蓝牙设备行：始终大字 +「选择设备」
        val btRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutMetrics.rowMinH(ctx)
            setPadding(0, LayoutMetrics.rowPadV(ctx) / 2, 0, LayoutMetrics.rowPadV(ctx) / 2)
        }
        bluetoothDeviceText = label(ctx, ctx.getString(R.string.home_loading), LayoutMetrics.homeBodyTextSize(ctx), GlassColors.MUTED).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        btRow.addView(bluetoothDeviceText!!, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btRow.addView(ghostButton(ctx, ctx.getString(R.string.home_choose_device)) { host.choosePhone() })
        content.addView(btRow)

        // 一体式分段控件：加高触控
        val modeRadius = LayoutMetrics.modeTabRadiusDp(ctx)
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = true
            clipToPadding = true
            minimumHeight = LayoutMetrics.modeTabMinH(ctx)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(GlassColors.CHIP_IDLE_FILL)
                cornerRadius = dp(modeRadius).toFloat()
            }
        }
        val modeKeys = listOf("direct", "hotspot", "lan")
        val modeLabels = listOf(
            ctx.getString(R.string.home_mode_wifi_direct),
            ctx.getString(R.string.home_mode_car_hotspot),
            ctx.getString(R.string.home_mode_lan),
        )
        modeKeys.forEachIndexed { index, key ->
            if (index > 0) {
                modeRow.addView(View(ctx).apply {
                    setBackgroundColor(GlassColors.DIVIDER)
                }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(10)
                })
            }
            val pos = modeTabPos(index, modeKeys.size)
            val tab = modeTab(ctx, modeLabels[index], pos) { switchTab(key) }
            tabButtons[key] = tab
            modeRow.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        content.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.modeTabMinH(ctx)).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })

        tabContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        val tabScroll = ScrollView(ctx).apply {
            isFillViewport = true
            clipChildren = true
            clipToPadding = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(tabContent, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        content.addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(divider(ctx))

        disconnectBtn = glassButton(ctx, ctx.getString(R.string.home_disconnect), false) { host.disconnectSession() }
        disconnectBtn.visibility = View.GONE
        content.addView(disconnectBtn, lpFull(ctx, LayoutMetrics.disconnectBtnH(ctx)))

        val startTab = when (AirPlayPersistence.loadWirelessHotspotMode(requireContext())) {
            WirelessHotspotMode.MANUAL -> "hotspot"
            WirelessHotspotMode.EXISTING_WIFI -> "lan"
            else -> "direct"
        }
        doSwitchTab(startTab, autoEnable = false)
        return content
    }

    private enum class ModeTabPos { FIRST, MIDDLE, LAST }

    private fun modeTabPos(index: Int, count: Int): ModeTabPos = when (index) {
        0 -> ModeTabPos.FIRST
        count - 1 -> ModeTabPos.LAST
        else -> ModeTabPos.MIDDLE
    }

    /**
     * 连接方式分段项：选中青蓝渐变；未选中透明（露出整条轨道底）。
     * tag = Pair(标题 TextView, ModeTabPos)，避免中间段出现圆角。
     */
    private fun modeTab(ctx: android.content.Context, text: String, pos: ModeTabPos, click: () -> Unit): View {
        val label = TextView(ctx).apply {
            this.text = text
            textSize = LayoutMetrics.modeTabTextSize(ctx)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(GlassColors.NAV_UNSELECTED)
            includeFontPadding = false
            val pv = LayoutMetrics.modeTabPadV(ctx)
            setPadding(dp(4), pv, dp(4), pv)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = LayoutMetrics.modeTabMinH(ctx)
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
            background = modeTabBg(ctx, on = false, pos = pos)
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tag = label to pos
        }
    }

    private fun modeTabBg(
        ctx: android.content.Context,
        on: Boolean,
        pos: ModeTabPos,
    ): android.graphics.drawable.GradientDrawable {
        val r = dp(LayoutMetrics.modeTabRadiusDp(ctx)).toFloat()
        val radii = when (pos) {
            ModeTabPos.FIRST -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            ModeTabPos.LAST -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            ModeTabPos.MIDDLE -> FloatArray(8) { 0f }
        }
        return if (on) {
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(GlassColors.ACCENT_GRADIENT_START, GlassColors.ACCENT_GRADIENT_END),
            ).apply { cornerRadii = radii }
        } else {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadii = radii
            }
        }
    }

    private fun switchTab(tab: String) {
        if (tab == currentTab) return
        // If CarPlay is already connected, warn that switching may drop the session.
        val ctx = requireContext()
        val proceed = { doSwitchTab(tab) }
        if (host.hasCarPlaySession()) {
            DiPlayDialog.showConfirm(
                ctx, ctx.getString(R.string.home_switch_mode_title),
                ctx.getString(R.string.home_switch_mode_message),
                ctx.getString(R.string.home_switch), ctx.getString(R.string.cancel), danger = false, onConfirm = proceed,
            )
        } else {
            proceed()
        }
    }

    private fun doSwitchTab(tab: String, autoEnable: Boolean = true) {
        currentTab = tab
        // Persist the chosen connection mode so it survives a restart. Each tab must set BOTH
        // the hotspot mode AND wireless-enabled, otherwise a stale mode (e.g. MANUAL left over
        // from the hotspot tab) makes "connect" run the wrong transport (see CarPlayController:
        // WIFI_P2P -> WiFi direct, MANUAL -> car hotspot, EXISTING_WIFI -> join a network).
        val ctx = requireContext()
        when (tab) {
            "hotspot" -> {
                AirPlayPersistence.saveWirelessHotspotMode(ctx, WirelessHotspotMode.MANUAL)
                AirPlayPersistence.saveWirelessEnabled(ctx, true)
            }
            "lan" -> {
                AirPlayPersistence.saveWirelessHotspotMode(ctx, WirelessHotspotMode.EXISTING_WIFI)
                AirPlayPersistence.saveWirelessEnabled(ctx, true)
            }
            else -> {
                AirPlayPersistence.saveWirelessHotspotMode(ctx, WirelessHotspotMode.WIFI_P2P)
                AirPlayPersistence.saveWirelessEnabled(ctx, true)
            }
        }
        rebuildTab(tab)
        when (tab) {
            "hotspot" -> if (autoEnable) Thread {
                host.openCarHotspot()
                view?.post { refresh() }
            }.start()
            else -> if (autoEnable) Thread {
                // WiFi-direct / LAN share the radio with the car hotspot: close the AP first,
                // otherwise the hotspot keeps the radio busy and direct reports "open hotspot".
                if (host.isCarHotspotEnabled()) host.closeCarHotspot()
                host.enableWifi()
                view?.post { refresh() }
            }.start()
        }
    }

    /** Rebuild only the tab UI (selection highlight + content) without saving or auto-enabling. */
    private fun rebuildTab(tab: String) {
        val ctx = requireContext()
        tabButtons.forEach { (key, btn) ->
            val active = key == tab
            @Suppress("UNCHECKED_CAST")
            val meta = btn.tag as? Pair<TextView, ModeTabPos>
            val label = meta?.first
            val pos = meta?.second ?: ModeTabPos.MIDDLE
            btn.background = modeTabBg(ctx, on = active, pos = pos)
            label?.setTextColor(
                if (active) GlassColors.NAV_SELECTED_TEXT else GlassColors.NAV_UNSELECTED,
            )
        }
        tabContent.removeAllViews()
        when (tab) {
            "hotspot" -> tabContent.addView(buildHotspotTab(ctx))
            "lan" -> tabContent.addView(buildLanTab(ctx))
            else -> tabContent.addView(buildDirectTab(ctx))
        }
    }

    private fun buildDirectTab(ctx: android.content.Context): View {
        val textSize = LayoutMetrics.homeBodyTextSize(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val wifiOn = host.isWifiEnabled()
        val wifiRow = rowWrap(ctx)
        wifiRow.addView(
            label(ctx, ctx.getString(R.string.home_car_wifi), textSize, GlassColors.INK).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        wifiRow.addView(cap(ctx, if (wifiOn) ctx.getString(R.string.home_enabled) else ctx.getString(R.string.home_disabled), if (wifiOn) GlassColors.SUCCESS else GlassColors.WARNING))
        wifiRow.isClickable = true
        wifiRow.isFocusable = true
        wifiRow.setOnClickListener { if (!wifiOn) { host.enableWifi(); refresh() } }
        col.addView(wifiRow)
        val chRow = rowWrap(ctx)
        chRow.addView(
            label(ctx, ctx.getString(R.string.home_wifi_direct_channel), textSize, GlassColors.INK).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val channelCap = cap(ctx, directChannelLabel(ctx), arrow = true)
        chRow.addView(channelCap)
        // 锚到 cap（与设置页一致），勿用整行，否则下拉会被拉满连接卡宽度。
        val openChannel = View.OnClickListener { promptWifiDirectChannel(channelCap) }
        channelCap.setOnClickListener(openChannel)
        chRow.isClickable = true
        chRow.isFocusable = true
        chRow.setOnClickListener(openChannel)
        col.addView(chRow)
        return col
    }

    private fun directChannelLabel(ctx: android.content.Context): String {
        val cur = AirPlayPersistence.loadWifiP2pPreferredChannel(ctx)
        return if (cur == WifiP2pChannels.AUTO) ctx.getString(R.string.home_channel_auto)
        else ctx.getString(R.string.home_channel_value, cur, if (cur <= 11) "2.4GHz" else "5GHz")
    }

    private fun promptWifiDirectChannel(anchor: View) {
        val ctx = anchor.context
        val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
        val cur = AirPlayPersistence.loadWifiP2pPreferredChannel(ctx)
        val labels = choices.map {
            if (it == WifiP2pChannels.AUTO) ctx.getString(R.string.home_channel_auto)
            else ctx.getString(R.string.home_channel_value, it, if (it <= 11) "2.4GHz" else "5GHz")
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
        val ssid = host.carHotspotSsid()
        val pass = host.carHotspotPassphrase()
        val on = host.isCarHotspotEnabled()
        fun statusLabel(title: String): TextView = label(ctx, title, textSize, GlassColors.INK).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sRow = rowWrap(ctx)
        sRow.addView(statusLabel(ctx.getString(R.string.hotspot_status)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sRow.addView(cap(ctx, if (on) ctx.getString(R.string.home_enabled) else ctx.getString(R.string.home_disabled), if (on) GlassColors.SUCCESS else GlassColors.WARNING))
        col.addView(sRow)
        val ssRow = rowWrap(ctx)
        ssRow.addView(statusLabel(ctx.getString(R.string.hotspot_ssid)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ssRow.addView(cap(ctx, ssid.ifBlank { ctx.getString(R.string.home_not_set) }))
        col.addView(ssRow)
        val pwRow = rowWrap(ctx)
        pwRow.addView(statusLabel(ctx.getString(R.string.hotspot_password)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pwRow.addView(cap(ctx, pass.ifBlank { ctx.getString(R.string.home_not_set) }))
        col.addView(pwRow)
        if (!on) {
            col.addView(ghostButton(ctx, ctx.getString(R.string.home_open_car_hotspot)) { host.openCarHotspot(); refresh() },
                lpFull(ctx, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = LayoutMetrics.heroGap(ctx) })
        }
        return col
    }

    private fun buildLanTab(ctx: android.content.Context): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // pendingLanSsid：密码刚提交、系统 connectionInfo 尚未跟上时仍显示「已连接」目标 SSID。
        val systemSsid = host.currentWifiSsid()
        val cur = when {
            systemSsid.isNotBlank() -> systemSsid
            pendingLanSsid.isNotBlank() -> pendingLanSsid
            else -> ""
        }
        // 车机当前已连 WiFi：把它同步为"现有 WiFi"SSID。否则局域网模式 build CarPlay 连接栈时
        // existingWifiSsid 为空会 require 抛异常，连接栈启动失败并误报"检查权限"。
        if (systemSsid.isNotBlank() && AirPlayPersistence.loadExistingWifiSsid(ctx) != systemSsid) {
            AirPlayPersistence.saveExistingWifiCredentials(
                ctx, systemSsid, AirPlayPersistence.loadExistingWifiPassphrase(ctx),
            )
        }
        val connecting = systemSsid.isBlank() && pendingLanSsid.isNotBlank()
        if (cur.isNotBlank()) {
            val wifiRow = rowWrap(ctx)
            wifiRow.addView(
                label(ctx, ctx.getString(R.string.home_connected_wifi), LayoutMetrics.homeBodyTextSize(ctx), GlassColors.INK),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            // 握手中先用强调色标出目标 SSID；系统确认后再变绿
            wifiRow.addView(cap(ctx, cur, if (connecting) GlassColors.CAP_TEXT else GlassColors.SUCCESS))
            col.addView(wifiRow)
            // 已连接：只保留按钮；点开弹窗扫附近网络，不在页内展开列表。
            col.addView(
                ghostButton(ctx, ctx.getString(R.string.home_choose_other_wifi)) {
                    showWifiPickerDialog(excludeSsid = systemSsid.ifBlank { cur })
                },
                lpFull(ctx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = LayoutMetrics.heroGap(ctx)
                },
            )
            return col
        }
        col.addView(label(ctx, ctx.getString(R.string.home_car_not_connected_wifi), LayoutMetrics.homeMutedTextSize(ctx), GlassColors.WARNING, true))
        // 未连接：页内可滚列表（固定高度 + 禁止父级拦截触控）。
        col.addView(buildWifiListScroll(ctx, excludeSsid = ""),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutMetrics.homeWifiListH(ctx),
            ))
        return col
    }

    /** 弹窗选择附近 WiFi（已连接时点「选择其它 WiFi」）。 */
    private fun showWifiPickerDialog(excludeSsid: String) {
        val ctx = requireContext()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.addView(
            label(ctx, ctx.getString(R.string.home_scanning), 14f, GlassColors.TEXT_BODY).apply {
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
        host.scanWifiNetworks { networks ->
            if (!isAdded || !dialog.isShowing) return@scanWifiNetworks
            list.removeAllViews()
            val others = networks.filterNot {
                excludeSsid.isNotBlank() && it.ssid.equals(excludeSsid, ignoreCase = true)
            }
            if (others.isEmpty()) {
                list.addView(
                    label(ctx, ctx.getString(R.string.home_no_wifi_networks), 14f, GlassColors.TEXT_MUTED).apply {
                        setPadding(0, dp(8), 0, dp(8))
                    },
                )
            } else {
                others.forEach { n ->
                    list.addView(wifiRow(ctx, n) {
                        dialog.dismiss()
                        onWifiSelected(n)
                    })
                }
            }
        }
    }

    /** 未连接时页内附近列表。 */
    private fun buildWifiListScroll(ctx: android.content.Context, excludeSsid: String): ScrollView {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.addView(
            label(ctx, ctx.getString(R.string.home_scanning), 14f, GlassColors.TEXT_BODY).apply {
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
        host.scanWifiNetworks { networks ->
            if (!isAdded || currentTab != "lan" || pendingLanSsid.isNotBlank()) return@scanWifiNetworks
            list.removeAllViews()
            val others = networks.filterNot {
                excludeSsid.isNotBlank() && it.ssid.equals(excludeSsid, ignoreCase = true)
            }
            if (others.isEmpty()) {
                list.addView(
                    label(ctx, ctx.getString(R.string.home_no_wifi_networks), 14f, GlassColors.TEXT_MUTED).apply {
                        setPadding(0, dp(8), 0, 0)
                    },
                )
            } else {
                others.forEach { n -> list.addView(wifiRow(ctx, n)) }
            }
        }
        return listScroll
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
        }
        row.addView(label(ctx, net.ssid, 15f, GlassColors.INK).apply { setPadding(0, 0, dp(8), 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (net.secured) {
            row.addView(label(ctx, "🔒", 13f, GlassColors.TEXT_MUTED))
        }
        row.addView(label(ctx, ctx.getString(R.string.home_tap_to_connect), 12f, GlassColors.CAP_TEXT))
        return row
    }

    private fun onWifiSelected(net: WifiEntry) {
        val ctx = requireContext()
        val savedPass = if (AirPlayPersistence.loadExistingWifiSsid(ctx) == net.ssid)
            AirPlayPersistence.loadExistingWifiPassphrase(ctx) else ""
        // 开放网络，或该 WiFi 已保存过密码 → 直接连接（不弹窗），连上后自动连 CarPlay
        if (!net.secured || savedPass.isNotEmpty()) {
            connectLanTo(net.ssid, savedPass)
            return
        }
        // 加密且未保存 → 弹窗输入密码
        DiPlayDialog.showInput(
            ctx, ctx.getString(R.string.home_connect_wifi),
            hint = ctx.getString(R.string.home_wifi_password_hint, net.ssid),
            initial = "",
            assist = net.ssid,
            save = ctx.getString(R.string.home_save_and_connect_wifi),
            // 空密码=开放网络，允许；非空一律通过（勿用 ifBlank 把密码本身当成错误文案）
            validator = { null },
            onSave = { pass -> connectLanTo(net.ssid, pass) },
        )
    }

    private fun connectLanTo(ssid: String, pass: String) {
        val ctx = requireContext()
        AirPlayPersistence.saveExistingWifiCredentials(ctx, ssid, pass)
        AirPlayPersistence.saveWirelessHotspotMode(ctx, WirelessHotspotMode.EXISTING_WIFI)
        // 立刻切到「正在连接 / 已连接」行；connectToWifi 会阻塞数秒，必须离主线程。
        pendingLanSsid = ssid
        lanConnected = true
        currentTab = "lan"
        refresh()
        host.showGlassToast(ctx.getString(R.string.home_connecting_wifi, ssid), DiPlayToast.Type.INFO)
        Thread {
            val ok = host.connectToWifi(ssid, pass)
            view?.post {
                if (!isAdded) return@post
                if (ok) {
                    pendingLanSsid = ""
                    lanConnected = true
                    host.showGlassToast(ctx.getString(R.string.home_connected_wifi) + " · " + ssid, DiPlayToast.Type.SUCCESS)
                    refresh()
                    // WiFi 已连上 → 直接连接 CarPlay（局域网模式；密码已保存，不会再弹密码框）
                    host.connect(true)
                } else {
                    // 再读一次系统 SSID：部分车机握手成功但轮询窗口已过
                    val now = host.currentWifiSsid()
                    if (now.equals(ssid, ignoreCase = true)) {
                        pendingLanSsid = ""
                        lanConnected = true
                        refresh()
                        host.connect(true)
                    } else {
                        pendingLanSsid = ""
                        lanConnected = false
                        // 清掉失败目标的密码，下次点同一网络会重新弹输入框；若仍挂在原 WiFi 则凭证改回当前 SSID。
                        val still = host.currentWifiSsid()
                        if (still.isNotBlank() && !still.equals(ssid, ignoreCase = true)) {
                            AirPlayPersistence.saveExistingWifiCredentials(ctx, still, "")
                        } else {
                            AirPlayPersistence.saveExistingWifiCredentials(ctx, ssid, "")
                        }
                        host.showGlassToast(ctx.getString(R.string.home_connect_wifi_failed, ssid), DiPlayToast.Type.ERROR)
                        refresh()
                    }
                }
            }
        }.start()
    }

    private fun buildAppListCard(ctx: android.content.Context): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.cardBg())
            setPadding(
                LayoutMetrics.cardPadH(ctx), LayoutMetrics.cardPadV(ctx),
                LayoutMetrics.cardPadH(ctx), LayoutMetrics.cardPadV(ctx),
            )
        }
        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutMetrics.rowMinH(ctx)
        }
        headRow.addView(label(ctx, ctx.getString(R.string.home_head_unit_apps), LayoutMetrics.cardTitleSize(ctx), GlassColors.TEXT_PRIMARY, true))
        headRow.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 1, 1f) })
        val manageBtn = Button(ctx).apply {
            text = ctx.getString(R.string.home_manage)
            textSize = LayoutMetrics.manageBtnTextSize(ctx)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(GlassColors.BTN_SECONDARY_TEXT)
            setBackgroundResource(GlassColors.btnSecondary())
            minWidth = LayoutMetrics.manageBtnMinW(ctx)
            minHeight = LayoutMetrics.manageBtnMinH(ctx)
            // KitKat Button 默认全大写/额外 padding 会挤字；清掉。
            includeFontPadding = true
            setPadding(LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx), LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx))
            setOnClickListener { showManageDialog(ctx) }
        }
        headRow.addView(manageBtn)
        card.addView(headRow)
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        appGrid = grid
        grid.addView(label(ctx, ctx.getString(R.string.home_loading), LayoutMetrics.homeMutedTextSize(ctx), GlassColors.TEXT_BODY).apply {
            setPadding(0, dp(8), 0, 0)
        })
        val scroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(grid)
        }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = LayoutMetrics.cardTitleGap(ctx)
        })
        reloadAppGrid()
        return card
    }

    /** 重新枚举并按当前隐藏集合重绘右侧应用网格。 */
    private fun reloadAppGrid() {
        val grid = appGrid ?: return
        val ctx = context ?: return
        Thread {
            val apps = CarAppCatalog.enumerate(ctx)
            // Fragment 可能已在 post 前 detach（用户切页/离开），此时 renderAppGrid 里 dp()→requireContext() 会抛
            // "not attached to a context" 闪退，故 post 回来先检查 isAdded。
            handler.post { if (isAdded) renderAppGrid(grid, apps) }
        }.start()
    }

    private fun renderAppGrid(grid: LinearLayout, apps: List<CarAppCatalog.App>) {
        grid.removeAllViews()
        val ctx = grid.context
        // 隐藏项过滤 + 自定义排序（用户拖动保存的顺序优先，未排序的按原字母序）
        visibleApps = sortApps(apps.filterNot { isHidden(it.packageName) })
        if (visibleApps.isEmpty()) {
            grid.addView(label(ctx, ctx.getString(R.string.app_launcher_no_apps), 14f, GlassColors.TEXT_MUTED).apply { setPadding(0, dp(10), 0, 0) })
            return
        }
        renderAppRows(grid)
    }

    private fun appColumns(ctx: android.content.Context): Int = LayoutMetrics.appColumns(ctx)

    private fun renderAppRows(grid: LinearLayout) {
        grid.removeAllViews()
        val ctx = grid.context
        val columns = appColumns(ctx)
        visibleApps.chunked(columns).forEach { rowApps ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            rowApps.forEach { app ->
                row.addView(buildAppTile(ctx, app, grid), tileLp(ctx))
            }
            // 末行不足时补空白格，避免唯一项 weight=1 撑满整行。
            repeat(columns - rowApps.size) {
                row.addView(View(ctx), tileLp(ctx))
            }
            grid.addView(row)
        }
    }

    private fun tileLp(ctx: android.content.Context): LinearLayout.LayoutParams {
        val m = LayoutMetrics.appTileMargin(ctx)
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(m, m, m, m)
        }
    }

    private fun buildAppTile(ctx: android.content.Context, app: CarAppCatalog.App, grid: LinearLayout): View {
        val iconDp = LayoutMetrics.appIconDp(ctx)
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(GlassColors.appTileBg())
            setPadding(
                LayoutMetrics.appTilePadH(ctx),
                LayoutMetrics.appTilePadT(ctx),
                LayoutMetrics.appTilePadH(ctx),
                LayoutMetrics.appTilePadB(ctx),
            )
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, e -> handleTileTapAndDrag(v, e, app, grid) }
        }
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(iconDp), dp(iconDp))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        val drawable = CarAppCatalog.icon(ctx, app, dp(iconDp - 4))
        if (drawable != null) icon.setImageDrawable(drawable)
        tile.addView(icon)
        tile.addView(TextView(ctx).apply {
            text = app.label
            textSize = LayoutMetrics.appLabelSize(ctx)
            setTextColor(GlassColors.INK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
        return tile
    }

    /** 应用格交互：短按=打开菜单（启动/隐藏/卸载）；长按或按住移动=拖动排序换位，松手保存顺序。 */
    private fun handleTileTapAndDrag(v: View, e: MotionEvent, app: CarAppCatalog.App, grid: LinearLayout): Boolean {
        val idx = visibleApps.indexOf(app)
        if (idx < 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragIndex = idx
                downX = e.rawX
                downY = e.rawY
                dragging = false
                longPressActive = true
                dragView = v
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop
                    // 按住移动超阈值即进入拖拽（无需等长按结束）
                    if (Math.abs(e.rawX - downX) > slop || Math.abs(e.rawY - downY) > slop) {
                        handler.removeCallbacks(longPressRunnable)
                        longPressActive = false
                        dragging = true
                        v.alpha = 0.55f
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    } else return true
                }
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val tileW = v.width.coerceAtLeast(1)
                val tileH = v.height.coerceAtLeast(1)
                val colStep = Math.round(dx / tileW.toFloat())
                val rowStep = Math.round(dy / tileH.toFloat())
                val cols = appColumns(requireContext())
                val target = idx + colStep + rowStep * cols
                if (target != idx && target in visibleApps.indices) {
                    val list = visibleApps.toMutableList()
                    val tmp = list[idx]
                    list[idx] = list[target]
                    list[target] = tmp
                    visibleApps = list
                    downX = e.rawX
                    downY = e.rawY
                    v.alpha = 0.55f
                    renderAppRows(grid)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                v.alpha = 1f
                val wasDragging = dragging
                dragging = false
                longPressActive = false
                dragIndex = -1
                dragView = null
                if (wasDragging) {
                    saveOrder(visibleApps.map { it.packageName })
                    return true
                }
                // 短按 → 打开菜单（启动/隐藏/卸载）
                showAppMenu(app, v)
                return true
            }
        }
        return true
    }

    private fun launchApp(app: CarAppCatalog.App) {
        val ctx = context ?: activity?.applicationContext ?: return
        if (app.packageName == "com.android.settings") {
            // 工程码 * #518200# * 优先：用户确认在 CS75 上能打开 OEM 设置。失败再走组件/OEM/root 兜底。
            runCatching {
                ctx.sendBroadcast(
                    Intent("android.provider.Telephony.SECRET_CODE", Uri.parse("android_secret_code://518200")),
                )
            }
            if (launchAndroidSettings(ctx)) return
            // 工程码拨号保底。
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:*#518200#*")))
            }
            return
        }
        // 外跳系统应用会露出导航栏；返回时由 Activity onResume / installAutoHide 收回。
        runCatching { ctx.startActivity(app.launch) }
    }

    /** Open the system Settings app. Mirrors DiPlayActivity.openSystem's fallback chain: component
     * launch (incl. OEM settings that stripped ROMs keep), then root `am start` per component. */
    private fun launchAndroidSettings(ctx: android.content.Context): Boolean {
        // Component fallback list (same as the pre-refactor openSystem): standard + OEM settings
        // packages that stripped head-unit ROMs (flyaudio / ww) keep when ACTION_SETTINGS is gone.
        val components = listOf(
            ComponentName("com.android.settings", "com.android.settings.Settings"),
            ComponentName("com.android.settings", "com.android.settings.SettingsActivity"),
            ComponentName("com.flyaudio.settings", "com.flyaudio.settings.activity.MainActivity"),
            ComponentName("com.ww.settings", "com.ww.settings.MainActivity"),
        )
        for (component in components) {
            if (runCatching {
                ctx.startActivity(
                    Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess) return true
        }
        // Root: start each component as uid 0.
        for (component in components) {
            val ok = runCatching {
                val p = ProcessBuilder("su", "-c", "am start -n ${component.flattenToShortString()}")
                    .redirectErrorStream(true).start()
                val done = p.waitFor(3000, TimeUnit.MILLISECONDS)
                val out = if (done) p.inputStream.bufferedReader().use { it.readText() } else ""
                if (!done) p.destroy()
                done && out.contains("Starting")
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    // ---- 应用列表：隐藏 / 排序 / 添加 ----
    private fun appPrefs(): android.content.SharedPreferences =
        requireContext().getSharedPreferences("home_app_list", android.content.Context.MODE_PRIVATE)

    private fun hiddenSet(): Set<String> =
        appPrefs().getString("hidden_packages", "")?.split(",").orEmpty().filter { it.isNotBlank() }.toSet()

    private fun isHidden(pkg: String): Boolean = pkg in hiddenSet()

    private fun setHidden(pkg: String, hidden: Boolean) {
        val set = hiddenSet().toMutableSet()
        if (hidden) set.add(pkg) else set.remove(pkg)
        // commit：立刻可读，避免紧接着的 reloadAppGrid 仍读到旧隐藏集。
        appPrefs().edit().putString("hidden_packages", set.joinToString(",")).commit()
    }

    /** 自定义排序优先（用户拖动保存的顺序），未排序的按原字母序跟在后面。 */
    private fun sortApps(apps: List<CarAppCatalog.App>): List<CarAppCatalog.App> {
        val order = appPrefs().getString("app_order", "")?.split(",").orEmpty().filter { it.isNotBlank() }
        if (order.isEmpty()) return apps
        val index = order.withIndex().associate { (i, pkg) -> pkg to i }
        return apps.sortedBy { index[it.packageName] ?: Int.MAX_VALUE }
    }

    private fun saveOrder(pkgs: List<String>) {
        appPrefs().edit().putString("app_order", pkgs.joinToString(",")).apply()
    }

    /** 单击应用 → 应用菜单（启动 / 卸载）。不抢焦点，避免车机右侧系统导航栏常驻不消失。 */
    private fun showAppMenu(app: CarAppCatalog.App, anchor: View? = null) {
        val act = activity ?: return
        val ctx = anchor?.context ?: act
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.dialogBg())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        lateinit var popup: PopupWindow
        fun menuItem(label: String, danger: Boolean, click: () -> Unit) {
            col.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(if (danger) GlassColors.WARNING else GlassColors.INK)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { popup.dismiss(); click() }
            })
        }
        menuItem(ctx.getString(R.string.home_launch), false) { launchApp(app) }
        col.addView(View(ctx).apply {
            setBackgroundColor(GlassColors.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        menuItem(ctx.getString(R.string.home_uninstall), true) { confirmUninstall(app) }

        val popupW = dp(148)
        col.measure(
            View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupH = col.measuredHeight.coerceAtLeast(dp(80))
        // focusable=false：不抢 Activity 焦点，系统导航栏不会被唤出后常驻。
        popup = PopupWindow(col, popupW, popupH, /* focusable = */ false)
        popup.isOutsideTouchable = true
        popup.isTouchable = true
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        col.systemUiVisibility = ImmersiveBars.visibilityFlags()

        ImmersiveBars.applyToWindow(act.window, force = true)

        val dm = ctx.resources.displayMetrics
        val margin = dp(8)
        // 本车机系统导航栏在右侧；预留宽度避免菜单被黑条裁切。
        val navInset = run {
            val id = android.content.res.Resources.getSystem()
                .getIdentifier("navigation_bar_width", "dimen", "android")
            val px = if (id > 0) android.content.res.Resources.getSystem().getDimensionPixelSize(id) else 0
            if (px > 0) px else dm.widthPixels / 12
        }
        val maxX = dm.widthPixels - navInset - margin - popupW
        if (anchor != null) {
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            // 贴近触发格：优先左对齐；右侧不够则右对齐；竖直优先正下方。
            var x = loc[0]
            if (x + popupW > maxX) x = loc[0] + anchor.width - popupW
            var y = loc[1] + anchor.height + dp(2)
            if (y + popupH > dm.heightPixels - margin) y = loc[1] - popupH - dp(2)
            x = x.coerceIn(margin, maxX.coerceAtLeast(margin))
            y = y.coerceIn(margin, (dm.heightPixels - margin - popupH).coerceAtLeast(margin))
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        } else {
            popup.showAtLocation(act.window.decorView, Gravity.CENTER, 0, 0)
        }
        ImmersiveBars.keepHiddenWhile(act) { popup.isShowing }
        popup.setOnDismissListener {
            ImmersiveBars.applyToWindow(act.window, force = true)
        }
    }

    /** 加号 → 从系统可启动应用中选择一个加入列表（取消隐藏并追加到排序尾部）。 */

    /** 右上角"管理"→ 多选管理弹窗：勾选=显示、取消=隐藏，底部系统/非系统分类复选联动。 */
    private fun showManageDialog(ctx: android.content.Context) {
        Thread {
            val allApps = CarAppCatalog.enumerate(ctx, forceRefresh = true)
            view?.post {
                if (!isAdded) return@post
                DiPlayDialog.showManageApps(
                    ctx, allApps,
                    isShown = { app -> !isHidden(app.packageName) },
                    isSystem = { app -> CarAppCatalog.isSystemApp(ctx, app.packageName) },
                    // 勾选即时写 prefs 并重绘网格；旧逻辑只调 refresh() 不碰 appGrid，界面看起来「没更新」。
                    onSetShown = { app, shown -> setHidden(app.packageName, !shown); reloadAppGrid() },
                    onDismiss = { reloadAppGrid() },
                )
            }
        }.start()
    }

    private fun confirmUninstall(app: CarAppCatalog.App) {
        val ctx = requireContext()
        runCatching {
            DiPlayDialog.showConfirm(
                ctx, ctx.getString(R.string.home_uninstall_title, app.label), ctx.getString(R.string.home_uninstall_message, app.label),
                ctx.getString(R.string.home_uninstall), ctx.getString(R.string.cancel), danger = true,
                onConfirm = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
    }

    /** Host 取消/失败/断开后立刻刷新（不必等 1s tick）。 */
    fun refreshFromHost() {
        if (!isAdded || !::statusText.isInitialized) return
        refresh()
    }

    private fun refresh() {
        val ctx = requireContext()
        val active = host.isCarPlayActive()
        val connecting = !active && host.isCarPlayConnecting()
        val pillBg = statusPillBg
        when {
            active -> {
                statusText.text = ctx.getString(R.string.bluetooth_device_connected)
                pillBg?.setColor(0x1F22C55E.toInt())
                pillBg?.setStroke(dp(1), 0x4022C55E.toInt())
                statusText.setTextColor(GlassColors.SUCCESS)
                heroMain.text = ctx.getString(R.string.open_carplay)
                heroSmall.text = ctx.getString(R.string.home_enter_projection)
            }
            connecting -> {
                statusText.text = ctx.getString(R.string.home_connecting)
                pillBg?.setColor(0x1FF59E0B.toInt())
                pillBg?.setStroke(dp(1), 0x40F59E0B.toInt())
                statusText.setTextColor(GlassColors.WARNING)
                heroMain.text = ctx.getString(R.string.connecting_to_your_iphone)
                heroSmall.text = ctx.getString(R.string.home_wireless_projection)
            }
            else -> {
                statusText.text = ctx.getString(R.string.home_not_connected)
                pillBg?.setColor(GlassColors.ACCENT_SOFT_FILL)
                pillBg?.setStroke(dp(1), GlassColors.ACCENT_SOFT_STROKE)
                statusText.setTextColor(GlassColors.ACCENT2)
                heroMain.text = ctx.getString(R.string.home_connect_carplay)
                heroSmall.text = ctx.getString(R.string.home_wireless_projection)
            }
        }
        bluetoothDeviceText?.text = bluetoothDeviceLabel()
        // 连接中也显示断开，点按走 cancelConnectAttempt。
        disconnectBtn.visibility = if (active || connecting) View.VISIBLE else View.GONE
        rebuildTab(currentTab)
    }

    // ---- glass helpers ----
    private fun label(ctx: android.content.Context, text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            this.textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun space(ctx: android.content.Context, h: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun divider(ctx: android.content.Context): View = View(ctx).apply {
        setBackgroundColor(GlassColors.DIVIDER)
        val g = LayoutMetrics.dividerGap(ctx)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = g; bottomMargin = g
        }
    }

    private fun lpFull(ctx: android.content.Context, h: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

    /** 主页主按钮：大触控青蓝渐变 + 播放三角 + 主文/副文。 */
    private fun heroButton(ctx: android.content.Context, main: String, small: String, click: () -> Unit): View {
        val pad = LayoutMetrics.heroPad(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    GlassColors.ACCENT_GRADIENT_START,
                    GlassColors.ACCENT,
                    GlassColors.ACCENT_GRADIENT_END,
                ),
            ).apply { cornerRadius = dp(LayoutMetrics.heroRadiusDp(ctx)).toFloat() }
        }
        container.addView(TextView(ctx).apply {
            text = "▶"
            textSize = LayoutMetrics.heroPlaySize(ctx)
            setTextColor(GlassColors.NAV_SELECTED_TEXT)
            setPadding(0, 0, dp(12), 0)
        })
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        heroMain = TextView(ctx).apply {
            text = main
            textSize = LayoutMetrics.heroMainSize(ctx)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(GlassColors.NAV_SELECTED_TEXT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        heroSmall = TextView(ctx).apply {
            text = small
            textSize = LayoutMetrics.heroSubSize(ctx)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor((GlassColors.NAV_SELECTED_TEXT and 0x00FFFFFF) or 0xB3000000.toInt())
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
            visibility = if (LayoutMetrics.showHeroSubtitle(ctx)) View.VISIBLE else View.GONE
        }
        textCol.addView(heroMain)
        textCol.addView(heroSmall)
        container.addView(textCol)
        return container
    }

    /** 值标签 cap：青蓝 pill。arrow=true 追加 ▾（可下拉项）。 */
    private fun cap(ctx: android.content.Context, text: String, color: Int = GlassColors.CAP_TEXT, arrow: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text + if (arrow) " ▾" else ""
            textSize = LayoutMetrics.settingsCapTextSize(ctx)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = true
            minimumHeight = LayoutMetrics.capMinH(ctx)
            val ph = LayoutMetrics.capPadH(ctx)
            val pv = LayoutMetrics.capPadV(ctx)
            setPadding(ph, pv, ph, pv)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(GlassColors.ACCENT_CAP_FILL)
                setStroke(dp(1), GlassColors.ACCENT_SOFT_STROKE)
                cornerRadius = dp(999).toFloat()
            }
        }

    /** 次级按钮 ghostbtn：抬升底 + 青描边，在玻璃卡片上可辨为可点。 */
    private fun ghostButton(ctx: android.content.Context, text: String, click: () -> Unit): View =
        TextView(ctx).apply {
            this.text = text
            textSize = LayoutMetrics.ghostTextSize(ctx)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(GlassColors.INK)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = true
            minimumHeight = LayoutMetrics.capMinH(ctx)
            setPadding(LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx), LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
            background = GlassColors.ghostDrawable(ctx, cornerRadiusDp = 14)
        }

    /** 设置行 row：左侧 label + 右侧控件。 */
    private fun rowWrap(ctx: android.content.Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutMetrics.rowMinH(ctx)
            val pv = LayoutMetrics.rowPadV(ctx)
            setPadding(0, pv, 0, pv)
        }

    private fun glassButton(ctx: android.content.Context, text: String, primary: Boolean, click: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            textSize = LayoutMetrics.ghostTextSize(ctx)
            setTextColor(if (primary) GlassColors.NAV_SELECTED_TEXT else GlassColors.BTN_SECONDARY_TEXT)
            setBackgroundResource(if (primary) GlassColors.btnPrimary() else GlassColors.btnSecondary())
            gravity = Gravity.CENTER
            minHeight = LayoutMetrics.disconnectBtnH(ctx)
            setOnClickListener { click() }
        }
}
