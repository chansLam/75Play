package com.shihab.diplay.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.connect.ShellNetworkActions
import com.shihab.diplay.host.R
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.ui.settings.GlassSettingsRows

/**
 * 主页：连接与车机应用左右铺满（竖屏则上下）。
 * 状态和时钟在壳层顶栏，这里不再叠一条主操作带。
 */
class FragmentHome : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var tabSnapshot: String? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!isAdded) return
            val snap = connectionSnapshot()
            // 局域网列表自己按系统扫描节奏刷新，状态没变时也不要拆掉连接区。
            if (wifiListLive || snap == tabSnapshot) refreshChrome() else refresh()
            handler.postDelayed(this, 1_000L)
        }
    }
    /** 局域网 Wi‑Fi 列表正在显示。为 true 时 1 秒状态刷新不重建该列表。 */
    internal var wifiListLive = false
    private val host get() = requireActivity() as DiPlayHost

    private var bluetoothDeviceText: TextView? = null
    private lateinit var disconnectBtn: Button
    private lateinit var disconnectDivider: View
    private lateinit var heroBtn: ConnectOrb
    private lateinit var flowView: ConnectFlowView
    private lateinit var heroMain: TextView

    internal var currentTab = "direct"
    private lateinit var tabContent: LinearLayout
    private val tabButtons = mutableMapOf<String, View>()
    internal var lanConnected = false
    /** 正在连 / 刚连上但系统 SSID 尚未就绪时，用此 SSID 立刻刷新「已连接」行。 */
    @Volatile
    internal var pendingLanSsid: String = ""
    private val appGridUi = HomeAppGrid(this, handler)
    private val connectionTabs = HomeConnectionTabs(this)

    private fun dp(v: Int) = GlassColors.dp(requireContext(), v)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val ctx = requireContext()
        val pad = LayoutMetrics.pagePad(ctx)
        val gap = LayoutMetrics.homeColumnGap(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0)
            setPadding(pad, pad, pad, pad)
        }
        val below = LinearLayout(ctx).apply {
            orientation = if (LayoutMetrics.isWide(ctx)) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val modeLp = if (LayoutMetrics.isWide(ctx)) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.homeConnectWeight(ctx))
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        below.addView(buildModeCard(ctx), modeLp)
        below.addView(View(ctx), if (LayoutMetrics.isWide(ctx)) {
            LinearLayout.LayoutParams(gap, 1)
        } else {
            LinearLayout.LayoutParams(1, gap)
        })
        val appsLp = if (LayoutMetrics.isWide(ctx)) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.homeAppsWeight(ctx))
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.homeAppsStripH(ctx))
        }
        below.addView(appGridUi.buildAppListCard(ctx), appsLp)
        root.addView(below, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        return root
    }

    override fun onResume() {
        super.onResume()
        when {
            tabSnapshot == null -> refresh()
            wifiListLive || connectionSnapshot() == tabSnapshot -> refreshChrome()
            else -> refresh()
        }
        // Show/hide 切换不经过 onPause/onResume：被 hide 的页在 Activity 回前台时也会走这里，
        // 只有可见页才需要 1 秒 tick；hidden 页由 onHiddenChanged(false) 接管。
        if (!isHidden) handler.postDelayed(tick, 1000)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            // 页被 hide（切到其它 tab）时 show/hide 路径不触发 onPause：停掉 tick 与 Wi-Fi
            // 扫描监听，避免隐藏页仍每秒刷新、仍每 10 秒请求一次系统扫描。
            handler.removeCallbacks(tick)
            connectionTabs.stopWifiWatchers()
        } else if (isResumed) {
            // removeCallbacks 先于 post，保证 show 来回切换不会叠出多个 tick。
            handler.removeCallbacks(tick)
            refresh()
            handler.postDelayed(tick, 1000)
            connectionTabs.restartAttachedWifiWatchers()
        }
    }

    private fun connectionSnapshot(): String = listOf(
        currentTab,
        host.isWifiEnabled().toString(),
        host.isCarHotspotEnabled().toString(),
        host.currentWifiSsid(),
        host.isCarPlayActive().toString(),
        host.isCarPlayConnecting().toString(),
    ).joinToString("|")

    override fun onPause() {
        handler.removeCallbacks(tick)
        // P1 leak兜底：onPause/onDestroyView 路径不经过 onHiddenChanged(true)，广播接收器
        // 不会注销。stopWifiWatchers 幂等（每个 watcher.stop() 有 started 守卫 + runCatching）。
        connectionTabs.stopWifiWatchers()
        super.onPause()
    }

    override fun onDestroyView() {
        // Fragment 视图销毁时也要停掉扫描监听，避免 ViewModel/视图销毁后 receiver 仍存活。
        connectionTabs.stopWifiWatchers()
        super.onDestroyView()
    }


    /** Show the live connected device on startup (even before a phone was saved), else the picker hint. */
    private fun bluetoothDeviceLabel(): String =
        host.connectedBluetoothDeviceName().ifBlank { host.chooseIphoneTitle() }

    /** 左侧连接舱：标题、主按钮、蓝牙，然后才是连接方式。宽屏在左，竖屏在应用格上面。 */
    private fun buildModeCard(ctx: android.content.Context): View {
        val content = glassCard(ctx)
        GlassSettingsRows.cardTitle(content, ctx.getString(R.string.home_connection_center))
        val heroGap = LayoutMetrics.heroGap(ctx)
        val heroBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val orbSize = LayoutMetrics.connectOrbSize(ctx)
        heroBtn = ConnectOrb(ctx).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                when {
                    host.isCarPlayActive() -> host.openProjection()
                    host.isCarPlayConnecting() -> host.cancelConnectAttempt()
                    else -> host.connect(true)
                }
            }
        }
        heroBlock.addView(heroBtn, LinearLayout.LayoutParams(orbSize, orbSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = heroGap
        })
        flowView = ConnectFlowView(ctx).apply { visibility = View.GONE }
        heroBlock.addView(flowView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.connectFlowH(ctx),
        ).apply { topMargin = heroGap })
        heroMain = label(ctx, "", LayoutMetrics.heroSubSize(ctx), GlassColors.ACCENT).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.INVISIBLE
            minimumHeight = dp(22)
        }
        heroBlock.addView(heroMain, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = heroGap })
        val topHalf = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(heroBlock, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        content.addView(topHalf, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        topHalf.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val reserve = heroMain.height + flowView.height + heroGap * 3 + dp(8)
            val side = (minOf(v.width, v.height - reserve) - dp(4)).coerceAtLeast(dp(96))
            val lp = heroBtn.layoutParams as? LinearLayout.LayoutParams ?: return@addOnLayoutChangeListener
            if (lp.width == side && lp.height == side) return@addOnLayoutChangeListener
            lp.width = side
            lp.height = side
            heroBtn.layoutParams = lp
        }
        val bottomHalf = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val btRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutMetrics.rowMinH(ctx)
            setPadding(0, LayoutMetrics.rowPadV(ctx) / 2, 0, 0)
        }
        val btView = label(ctx, ctx.getString(R.string.home_loading), LayoutMetrics.homeBodyTextSize(ctx), GlassColors.MUTED).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }.also { bluetoothDeviceText = it }
        btRow.addView(btView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btRow.addView(GlassSettingsRows.ghostButton(ctx, ctx.getString(R.string.home_choose_device), cornerRadiusDp = 10) { host.choosePhone() })
        bottomHalf.addView(btRow)
        val modeRadius = LayoutMetrics.modeTabRadiusDp(ctx)
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = true
            clipToPadding = true
            minimumHeight = LayoutMetrics.modeTabMinH(ctx)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(GlassColors.CHIP_IDLE_FILL)
                setStroke(dp(1), GlassColors.GLASS_EDGE)
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
        bottomHalf.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.modeTabMinH(ctx)).apply {
            topMargin = heroGap
            bottomMargin = heroGap
        })

        tabContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        val tabScroll = ScrollView(ctx).apply {
            clipChildren = true
            clipToPadding = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(tabContent, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        bottomHalf.addView(tabScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        disconnectDivider = divider(ctx)
        disconnectDivider.visibility = View.GONE
        bottomHalf.addView(disconnectDivider)

        disconnectBtn = glassButton(ctx, ctx.getString(R.string.home_disconnect), false) { host.disconnectSession() }
        disconnectBtn.visibility = View.GONE
        bottomHalf.addView(disconnectBtn, lpFull(ctx, LayoutMetrics.disconnectBtnH(ctx)))
        content.addView(bottomHalf, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

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
     * 连接方式分段项：选中为青渐变；未选中透明（露出整条轨道底）。
     * tag 持有标题和分段位置，避免中间段出现圆角。
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
            // 焦点高亮：跟随当前选中态重画，失焦还原。rebuildTab 通过 tag 更新 visual.on。
            val visual = ModeTabVisual(label, pos, on = false)
            fun render(target: View, focused: Boolean) {
                target.background = if (focused) modeTabFocusedBg(ctx, visual.on, pos) else modeTabBg(ctx, visual.on, pos)
            }
            render(this, false)
            setOnFocusChangeListener { v, focused -> render(v, focused) }
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tag = visual
        }
    }

    /** 焦点态：同选中/未选中形状，描边加粗为 2dp 青。 */
    private fun modeTabFocusedBg(
        ctx: android.content.Context,
        on: Boolean,
        pos: ModeTabPos,
    ): android.graphics.drawable.GradientDrawable = modeTabBg(ctx, on, pos).apply {
        setStroke(dp(2), GlassColors.ACCENT)
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
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(GlassColors.ACCENT_LIFT, GlassColors.ACCENT_DEEP),
            ).apply {
                setStroke(dp(1), 0x66FFFFFF)
                cornerRadii = radii
            }
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
        val ctx = requireContext()
        ShellNetworkActions.saveHomeConnectionTab(ctx, tab)
        rebuildTab(tab)
        if (autoEnable) {
            ShellNetworkActions.prepareHomeConnectionRadio(
                ctx,
                tab,
                enableWifi = { host.enableWifi() },
                onReady = { view?.post { refresh() } },
            )
        }
    }

    /** Rebuild only the tab UI (selection highlight + content) without saving or auto-enabling. */
    private fun rebuildTab(tab: String) {
        val ctx = requireContext()
        tabButtons.forEach { (key, btn) ->
            val active = key == tab
            @Suppress("UNCHECKED_CAST")
            val visual = btn.tag as? ModeTabVisual ?: return@forEach
            visual.on = active
            visual.label.setTextColor(
                if (active) GlassColors.NAV_SELECTED_TEXT else GlassColors.NAV_UNSELECTED,
            )
            btn.background = if (btn.isFocused) modeTabFocusedBg(ctx, active, visual.pos) else modeTabBg(ctx, active, visual.pos)
        }
        tabContent.removeAllViews()
        tabContent.addView(connectionTabs.build(ctx, tab))
    }

    /** Host 取消/失败/断开后立刻刷新（不必等 1s tick）。 */
    fun refreshFromHost() {
        if (!isAdded || !::heroMain.isInitialized) return
        val snap = connectionSnapshot()
        if (wifiListLive || snap == tabSnapshot) refreshChrome() else refresh()
    }

    private fun refreshChrome() {
        val ctx = requireContext()
        val active = host.isCarPlayActive()
        val connecting = !active && host.isCarPlayConnecting()
        when {
            active -> {
                heroBtn.connected = true
                heroBtn.progress = 1f
                heroBtn.setSpinning(false)
                flowView.showIdle()
                flowView.visibility = View.GONE
                heroMain.text = ""
                heroMain.visibility = View.INVISIBLE
            }
            connecting -> {
                val stage = host.connectStageLabel().ifBlank { ctx.getString(R.string.connecting_to_your_iphone) }
                val step = ConnectFlow.indexOf(stage) { ctx.getString(it) }
                heroBtn.connected = false
                heroBtn.setSpinning(true)
                if (step >= 0) heroBtn.progress = (step + 1f) / ConnectFlow.COUNT
                flowView.visibility = View.VISIBLE
                flowView.showStage(step)
                heroMain.text = stage
                heroMain.setTextColor(GlassColors.ACCENT)
                heroMain.visibility = View.VISIBLE
            }
            else -> {
                heroBtn.connected = false
                heroBtn.progress = 0f
                heroBtn.setSpinning(false)
                flowView.showIdle()
                flowView.visibility = View.GONE
                heroMain.text = ""
                heroMain.visibility = View.INVISIBLE
            }
        }
        bluetoothDeviceText?.text = bluetoothDeviceLabel()
        // 连接中底部按钮即原来弹窗里的取消；已连接才是断开。
        val showDisconnect = if (active || connecting) View.VISIBLE else View.GONE
        disconnectBtn.visibility = showDisconnect
        disconnectBtn.text = ctx.getString(if (connecting) R.string.cancel else R.string.home_disconnect)
        heroBtn.contentDescription = ctx.getString(
            when {
                active -> R.string.open_carplay
                connecting -> R.string.home_connecting
                else -> R.string.home_connect_carplay
            },
        )
        if (::disconnectDivider.isInitialized) disconnectDivider.visibility = showDisconnect
    }

    private fun refresh() {
        refreshChrome()
        rebuildTab(currentTab)
        if (isAdded) tabSnapshot = connectionSnapshot()
    }

    // ---- glass helpers ----
    private fun glassCard(ctx: android.content.Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = true
        setBackgroundResource(GlassColors.cardBg())
        setPadding(
            LayoutMetrics.cardPadH(ctx), LayoutMetrics.cardPadV(ctx),
            LayoutMetrics.cardPadH(ctx), LayoutMetrics.cardPadV(ctx),
        )
    }

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

    // NU-04：cap / ghostButton / rowWrap 已抽到 [GlassSettingsRows]（ghostButton 圆角参数化 14/12）。

    private fun glassButton(ctx: android.content.Context, text: String, primary: Boolean, click: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            textSize = LayoutMetrics.ghostTextSize(ctx)
            setTextColor(if (primary) GlassColors.NAV_SELECTED_TEXT else GlassColors.BTN_SECONDARY_TEXT)
            setBackgroundResource(if (primary) GlassColors.btnPrimary() else GlassColors.btnSecondary())
            gravity = Gravity.CENTER
            val h = LayoutMetrics.disconnectBtnH(ctx)
            minHeight = h
            minimumHeight = h
            setPadding(LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx), LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx))
            setOnClickListener { click() }
            FocusSupport.attachStrokeOverlay(this, cornerRadiusDp = 10)
        }

    /** 连接方式分段项的可视状态：label、形状、是否选中。tag 持有者。 */
    private class ModeTabVisual(val label: TextView, val pos: ModeTabPos, var on: Boolean)
}
