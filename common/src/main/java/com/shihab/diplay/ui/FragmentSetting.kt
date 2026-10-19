package com.shihab.diplay.ui

import android.os.Bundle
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
import com.shihab.diplay.AmbientLightThreshold
import com.shihab.diplay.CarPlayDock
import com.shihab.diplay.CarPlayNightMode
import com.shihab.diplay.InterfaceSize
import com.shihab.diplay.airplay.CarPlaySize
import com.shihab.diplay.localizedLabel
import com.shihab.diplay.host.R

/**
 * FragmentSetting: two-column grid (GridLayoutManager spanCount=2).
 * Ordinary cards span 1 column; heavy cards span 2. Items already shown on the
 * home page (auto connect / boot autostart / fullscreen / location / night mode /
 * right-hand drive / floating ball / audio focus) are NOT repeated here; this page
 * keeps only their advanced sub-parameters.
 */
class FragmentSetting : Fragment() {

    private val host get() = requireActivity() as DiPlayHost

    private fun dp(v: Int) = GlassColors.dp(requireContext(), v)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val ctx = requireContext()
        val pad = LayoutMetrics.pagePad(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.bgHome())
            setPadding(pad, pad, pad, pad)
        }
        // 与主页 / 车辆页一致：无页头，侧栏已标明「设置」；三列直接铺满。
        val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun gap() = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LayoutMetrics.colGap(ctx), 1) }
        val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        cols.addView(columnCard(ctx) { buildDisplayCard(it) }, colLp)
        cols.addView(gap())
        cols.addView(columnCard(ctx) { buildVideoAdvancedCard(it) }, colLp)
        cols.addView(gap())
        cols.addView(columnCard(ctx) { buildAudioCard(it) }, colLp)
        root.addView(cols, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    /** 单列玻璃卡片（垂直 ScrollView 兜底）；底边多留空隙防末行裁切。 */
    private fun columnCard(ctx: android.content.Context, build: (LinearLayout) -> Unit): ScrollView {
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        build(card)
        // 末行 cap 不被圆角/底边裁掉。
        card.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(1, LayoutMetrics.cardPadV(ctx))
        })
        return ScrollView(ctx).apply {
            setBackgroundResource(GlassColors.cardBg())
            val padH = LayoutMetrics.cardPadH(ctx)
            val padV = LayoutMetrics.cardPadV(ctx)
            setPadding(padH, padV, padH, padV)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(card)
        }
    }

    // ---------- glass widget helpers ----------
    private fun label(ctx: android.content.Context, text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            this.textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun cardTitle(card: LinearLayout, title: String) {
        val ctx = card.context
        card.addView(label(ctx, title, LayoutMetrics.cardTitleSize(ctx), GlassColors.TEXT_PRIMARY, true))
        card.addView(space(ctx, LayoutMetrics.cardTitleGap(ctx)))
    }

    /** [hPx] 已是像素（如 LayoutMetrics.cardTitleGap）。 */
    private fun space(ctx: android.content.Context, hPx: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, hPx) }

    private fun lpWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun glassButton(ctx: android.content.Context, text: String, primary: Boolean = false, click: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            textSize = LayoutMetrics.ghostTextSize(ctx)
            setTextColor(if (primary) GlassColors.NAV_SELECTED_TEXT else GlassColors.BTN_SECONDARY_TEXT)
            setBackgroundResource(if (primary) GlassColors.btnPrimary() else GlassColors.btnSecondary())
            gravity = Gravity.CENTER
            minHeight = LayoutMetrics.disconnectBtnH(ctx)
            setPadding(0, 0, 0, 0)
            setOnClickListener { click() }
            layoutParams = lpWrap().apply { bottomMargin = LayoutMetrics.cardTitleGap(ctx) }
        }

    /** 玻璃下拉：紧凑宽高 + 打开期间压制系统导航栏。 */
    private fun showDropdown(anchor: View, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        GlassDropdown.show(anchor, labels, selected, onSelect)
    }

    private fun nightModeLabel(ctx: android.content.Context, mode: CarPlayNightMode): String = when (mode) {
        CarPlayNightMode.SYSTEM -> ctx.getString(R.string.setting_night_follow_system)
        CarPlayNightMode.AMBIENT -> ctx.getString(R.string.setting_night_ambient)
        CarPlayNightMode.DAY -> ctx.getString(R.string.setting_night_light)
        CarPlayNightMode.NIGHT -> ctx.getString(R.string.setting_night_dark)
        CarPlayNightMode.SCHEDULE -> ctx.getString(R.string.setting_night_schedule)
    }

    // ---------- cards ----------
    private fun buildAudioCard(card: LinearLayout) {
        val ctx = card.context
        cardTitle(card, ctx.getString(R.string.audio))
        val buffers = listOf(
            ctx.getString(R.string.setting_buffer_300ms_default),
            "500ms",
            ctx.getString(R.string.setting_buffer_1000ms_stable),
        )
        val buf = AirPlayPersistence.loadMediaBufferMillis(ctx)
        val bufIdx = when (buf) { 500 -> 1; 1000 -> 2; else -> 0 }
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_music_buffer),
            capPick(ctx, buffers[bufIdx]) { cap ->
                showDropdown(cap, buffers, bufIdx) { idx ->
                    AirPlayPersistence.saveMediaBufferMillis(ctx, listOf(300, 500, 1000)[idx])
                }
            }))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_media_channel),
            capPick(ctx, ctx.getString(R.string.setting_channel_value, AirPlayPersistence.loadMediaAudioChannel(ctx))) { cap ->
                showChannelPicker(cap, ctx.getString(R.string.setting_media_channel), AirPlayPersistence.loadMediaAudioChannel(ctx)) { v -> AirPlayPersistence.saveMediaAudioChannel(ctx, v) }
            }))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_nav_channel),
            capPick(ctx, ctx.getString(R.string.setting_channel_value, AirPlayPersistence.loadNavigationAudioChannel(ctx))) { cap ->
                showChannelPicker(cap, ctx.getString(R.string.setting_nav_channel), AirPlayPersistence.loadNavigationAudioChannel(ctx)) { v -> AirPlayPersistence.saveNavigationAudioChannel(ctx, v) }
            }))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_call_channel),
            capPick(ctx, ctx.getString(R.string.setting_channel_value, AirPlayPersistence.loadCallAudioChannel(ctx))) { cap ->
                showChannelPicker(cap, ctx.getString(R.string.setting_call_channel), AirPlayPersistence.loadCallAudioChannel(ctx)) { v -> AirPlayPersistence.saveCallAudioChannel(ctx, v) }
            }))
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.advanced_audio_channel_mapping), AirPlayPersistence.loadAdvancedAudioChannelMapping(ctx)) { on ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                AirPlayPersistence.saveAdvancedAudioChannelMapping(ctx, on); host.reconnectIfRunning()
            }
        })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_main_buffered_audio), AirPlayPersistence.loadMainBufferedAudio(ctx)) { on ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                AirPlayPersistence.saveMainBufferedAudio(ctx, on); host.reconnectIfRunning()
            }
        })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_audio_focus_control), AirPlayPersistence.loadAudioFocusEnabled(ctx)) { AirPlayPersistence.saveAudioFocusEnabled(ctx, it) })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_call_echo_cancellation), AirPlayPersistence.loadCallEchoCancellation(ctx)) { on ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                AirPlayPersistence.saveCallEchoCancellation(ctx, on); host.reconnectIfRunning()
            }
        })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_call_voice_filter), AirPlayPersistence.loadCallVoiceFilter(ctx)) { on ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                AirPlayPersistence.saveCallVoiceFilter(ctx, on); host.reconnectIfRunning()
            }
        })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_call_speakerphone), AirPlayPersistence.loadCallSpeakerphone(ctx)) { AirPlayPersistence.saveCallSpeakerphone(ctx, it) })
    }

    /** 左列：日常显示（尺寸/缩放/帧率/主题/系统栏/安全区）。 */
    private fun buildDisplayCard(card: LinearLayout) {
        val ctx = card.context
        cardTitle(card, ctx.getString(R.string.settings_display))
        val curSize = CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(ctx))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_display_size), capPick(ctx, curSize.localizedLabel(ctx)) { cap -> showCarPlaySizePicker(cap) }))
        val scalePercent = AirPlayPersistence.loadDisplayScalePercent(ctx)
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_display_scale), capPick(ctx, "$scalePercent%") { cap -> showScalePicker(cap) }))
        val fps = listOf(
            ctx.getString(R.string.setting_fps_min_savings),
            ctx.getString(R.string.setting_fps_more_savings),
            ctx.getString(R.string.setting_fps_balanced),
            ctx.getString(R.string.setting_fps_smooth),
        )
        val fpsCur = AirPlayPersistence.loadFps(ctx)
        val fpsIdx = when (fpsCur) { 15 -> 0; 45 -> 2; 60 -> 3; else -> 1 }
        card.addView(settingRow(ctx, ctx.getString(R.string.frame_rate), capPick(ctx, fps[fpsIdx]) { cap ->
            showDropdown(cap, fps, fpsIdx) { idx ->
                host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                    AirPlayPersistence.saveFps(ctx, listOf(15, 30, 45, 60)[idx])
                    host.reconnectIfRunning()
                }
            }
        }))
        val nightModes = listOf(
            CarPlayNightMode.SYSTEM, CarPlayNightMode.AMBIENT, CarPlayNightMode.DAY,
            CarPlayNightMode.NIGHT, CarPlayNightMode.SCHEDULE,
        )
        val curMode = AirPlayPersistence.loadCarPlayNightMode(ctx)
        val curModeIdx = nightModes.indexOf(curMode).let { if (it < 0) 0 else it }
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_theme_mode), capPick(ctx, nightModeLabel(ctx, curMode)) { cap ->
            showDropdown(cap, nightModes.map { nightModeLabel(ctx, it) }, curModeIdx) { idx ->
                AirPlayPersistence.saveCarPlayNightMode(ctx, nightModes[idx])
                host.rebuildTheme()
            }
        }))
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_hide_top_bar), AirPlayPersistence.loadHideTopBar(ctx)) { AirPlayPersistence.saveHideTopBar(ctx, it) })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_hide_bottom_bar), AirPlayPersistence.loadHideBottomBar(ctx)) { AirPlayPersistence.saveHideBottomBar(ctx, it) })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_root_hide_navbar), AirPlayPersistence.loadRootHideNavbar(ctx)) { host.setRootHideNavbar(it) })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_adapt_pip_resolution), AirPlayPersistence.loadAdaptPipResolution(ctx)) { AirPlayPersistence.saveAdaptPipResolution(ctx, it) })
        card.addView(settingRow(ctx, ctx.getString(R.string.settings_interface_size), capPick(ctx, InterfaceSize.displayName(ctx, InterfaceSize.preference(ctx))) { cap -> showInterfaceSizePicker(cap) }))
        card.addView(settingRow(ctx, ctx.getString(R.string.safe_area), capPick(ctx, safeAreaSummary(ctx)) { cap -> showSafeAreaEditor(cap) }))
    }

    /** 中列：视频编解码 / Dock / 环境光等进阶项。 */
    private fun buildVideoAdvancedCard(card: LinearLayout) {
        val ctx = card.context
        cardTitle(card, ctx.getString(R.string.settings_advanced))
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_efficient_video_hevc), AirPlayPersistence.loadHevcEnabled(ctx)) { on ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                AirPlayPersistence.saveHevcEnabled(ctx, on); host.reconnectIfRunning()
            }
        })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_smooth_video), AirPlayPersistence.loadSmoothVideo(ctx)) { on ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                AirPlayPersistence.saveSmoothVideo(ctx, on); host.reconnectIfRunning()
            }
        })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_direct_surface_view), AirPlayPersistence.loadDirectSurfaceView(ctx)) { on ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                AirPlayPersistence.saveDirectSurfaceView(ctx, on); host.reconnectIfRunning()
            }
        })
        card.addView(divider(ctx))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_ambient_light_threshold), capPick(ctx, "${AirPlayPersistence.loadAmbientLightThreshold(ctx).lux} lux") { promptAmbientLightThreshold(ctx) }))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_ambient_delay), capPick(ctx, ctx.getString(R.string.setting_seconds_value, AirPlayPersistence.loadAmbientDelaySeconds(ctx))) { cap -> showAmbientDelayPicker(cap) }))
        card.addView(settingRow(ctx, ctx.getString(R.string.carplay_dock), capPick(ctx, dockLabel(ctx, CarPlayDock.load(ctx))) { cap -> showDockPicker(cap) }))
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_left_system_dock_inset), AirPlayPersistence.loadLeftSystemDockInset(ctx)) { AirPlayPersistence.saveLeftSystemDockInset(ctx, it) })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.draw_outside_safe_area), AirPlayPersistence.loadSafeAreaDrawOutside(ctx)) { AirPlayPersistence.saveSafeAreaDrawOutside(ctx, it) })
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_picture_adjust), capPick(ctx, ctx.getString(R.string.open)) {
            val i = android.content.Intent(requireActivity(), com.shihab.diplay.CarPlayHostActivity::class.java)
            i.putExtra("picture_controls", true)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            requireActivity().startActivity(i)
        }))
    }

    private fun promptAmbientLightThreshold(ctx: android.content.Context) {
        val cur = AirPlayPersistence.loadAmbientLightThreshold(ctx).lux
        DiPlayDialog.showInput(
            ctx,
            ctx.getString(R.string.setting_ambient_light_threshold),
            "${AmbientLightThreshold.MIN_LUX}-${AmbientLightThreshold.MAX_LUX} lux",
            cur.toString(),
            save = ctx.getString(R.string.set),
            cancel = ctx.getString(R.string.cancel),
            validator = { v ->
                v.toIntOrNull()?.let {
                    if (AmbientLightThreshold.isValid(it)) null
                    else ctx.getString(R.string.setting_threshold_range_error, AmbientLightThreshold.MIN_LUX, AmbientLightThreshold.MAX_LUX)
                } ?: ctx.getString(R.string.setting_input_enter_number)
            },
        ) { v -> AirPlayPersistence.saveAmbientLightThreshold(ctx, AmbientLightThreshold(v.toInt())) }
    }

    private fun showAmbientDelayPicker(anchor: View) {
        val ctx = anchor.context
        val secondsValues = (0..60 step 2).toList()
        val options = secondsValues.map { ctx.getString(R.string.setting_seconds_value, it) }
        val cur = AirPlayPersistence.loadAmbientDelaySeconds(ctx).coerceIn(0, 60)
        showDropdown(anchor, options, cur / 2) { idx ->
            AirPlayPersistence.saveAmbientDelaySeconds(ctx, secondsValues[idx])
        }
    }

    private fun showScalePicker(anchor: View) {
        val ctx = anchor.context
        val scales = listOf(50, 75, 100, 125, 150)
        val cur = AirPlayPersistence.loadDisplayScalePercent(ctx)
        showDropdown(anchor, scales.map { "$it%" }, scales.indexOf(cur).coerceAtLeast(0)) { idx ->
            host.confirmReconnectChange(anchor.context.getString(R.string.reconnect_required_message)) {
                AirPlayPersistence.saveDisplayScalePercent(ctx, scales[idx])
                host.reconnectIfRunning()
            }
        }
    }

    private fun dockLabel(ctx: android.content.Context, dock: CarPlayDock): String = when (dock) {
        CarPlayDock.AUTOMATIC -> ctx.getString(R.string.carplay_dock_automatic)
        CarPlayDock.DRIVER_SIDE -> ctx.getString(R.string.carplay_dock_driver_side)
        CarPlayDock.BOTTOM -> ctx.getString(R.string.carplay_dock_bottom)
    }

    private fun showDockPicker(anchor: View) {
        val ctx = anchor.context
        val docks = CarPlayDock.entries
        showDropdown(anchor, docks.map { dockLabel(ctx, it) }, docks.indexOf(CarPlayDock.load(ctx)).coerceAtLeast(0)) { idx ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                CarPlayDock.save(ctx, docks[idx])
                host.reconnectIfRunning()
            }
        }
    }

    private fun showInterfaceSizePicker(anchor: View) {
        val ctx = anchor.context
        val choices = InterfaceSize.CHOICES
        val labels = choices.map { InterfaceSize.displayName(ctx, it) }
        val cur = InterfaceSize.preference(ctx)
        showDropdown(anchor, labels, choices.indexOf(cur).coerceAtLeast(0)) { idx ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                InterfaceSize.save(ctx, choices[idx])
                host.reconnectIfRunning()
            }
        }
    }

    private fun showCarPlaySizePicker(anchor: View) {
        val ctx = anchor.context
        val sizes = CarPlaySize.entries
        val current = CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(ctx))
        showDropdown(anchor, sizes.map { it.localizedLabel(ctx) }, sizes.indexOf(current).coerceAtLeast(0)) { idx ->
            host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                AirPlayPersistence.saveWidthPhysicalMm(ctx, sizes[idx].widthMillimeters)
                host.reconnectIfRunning()
            }
        }
    }

    private fun activitySizePx(): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
    }

    private fun safeAreaOrFull(ctx: android.content.Context, w: Int, h: Int): com.shihab.diplay.airplay.SafeAreaRect =
        AirPlayPersistence.loadSafeAreaRect(ctx, w, h)
            ?: com.shihab.diplay.airplay.AirPlaySafeArea.full(w, h)

    private fun safeAreaSummary(ctx: android.content.Context): String {
        val (w, h) = activitySizePx()
        val rect = AirPlayPersistence.loadSafeAreaRect(ctx, w, h) ?: return ctx.getString(R.string.safe_area_full)
        return "${rect.width}×${rect.height}"
    }

    private fun showSafeAreaEditor(anchor: View) {
        val ctx = anchor.context
        val (w, h) = activitySizePx()
        // 未自定义时用全屏矩形作编辑底稿；禁止 SafeAreaRect(0,0,0,0)（会抛非空矩形异常）。
        val currentRect = safeAreaOrFull(ctx, w, h)
        val edges = listOf(
            ctx.getString(R.string.setting_edge_left, currentRect.left),
            ctx.getString(R.string.setting_edge_top, currentRect.top),
            ctx.getString(R.string.setting_edge_right, currentRect.right),
            ctx.getString(R.string.setting_edge_bottom, currentRect.bottom),
        )
        showDropdown(anchor, edges, 0) { idx ->
            val cur = safeAreaOrFull(ctx, w, h)
            val current = listOf(cur.left, cur.top, cur.right, cur.bottom)[idx]
            val maxForAxis = if (idx == 0 || idx == 2) w else h
            DiPlayDialog.showInput(
                ctx,
                edges[idx],
                "0-$maxForAxis px",
                current.toString(),
                save = ctx.getString(R.string.set),
                cancel = ctx.getString(R.string.cancel),
                validator = { v ->
                    v.toIntOrNull()?.let {
                        if (it in 0..maxForAxis) null
                        else ctx.getString(R.string.setting_safe_area_range_error, maxForAxis)
                    } ?: ctx.getString(R.string.setting_input_enter_number)
                },
            ) { v ->
                host.confirmReconnectChange(ctx.getString(R.string.reconnect_required_message)) {
                    val r = safeAreaOrFull(ctx, w, h)
                    val raw = v.toInt()
                    val next = when (idx) {
                        0 -> r.copy(left = raw.coerceIn(0, r.right - 1))
                        1 -> r.copy(top = raw.coerceIn(0, r.bottom - 1))
                        2 -> r.copy(right = raw.coerceIn(r.left + 1, w))
                        else -> r.copy(bottom = raw.coerceIn(r.top + 1, h))
                    }
                    AirPlayPersistence.saveSafeAreaRect(ctx, w, h, next)
                    (anchor as? TextView)?.text = safeAreaSummary(ctx) + " ▾"
                    host.reconnectIfRunning()
                }
            }
        }
    }

    // ---------- dialog helpers ----------
    private fun showChannelPicker(anchor: View, title: String, current: Int, save: (Int) -> Unit) {
        val ctx = anchor.context
        val labels = (0..10).map { ctx.getString(R.string.setting_channel_value, it) }
        showDropdown(anchor, labels, current.coerceIn(0, 10)) { save(it) }
    }

    // ---------- row/cap helpers（对齐提示词 §3.2/§3.3） ----------
    private fun settingRow(ctx: android.content.Context, title: String, right: View): LinearLayout {
        val short = LayoutMetrics.isShort(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pv = LayoutMetrics.rowPadV(ctx)
            setPadding(0, pv, 0, pv)
            minimumHeight = LayoutMetrics.rowMinH(ctx)
        }
        row.addView(
            label(ctx, title, LayoutMetrics.settingsRowTextSize(ctx), GlassColors.TEXT_BODY).apply {
                includeFontPadding = false
                maxLines = if (short) 1 else 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(right)
        return row
    }

    /** 可下拉值标签 .pick：cap 样式 + ▾，点击弹下拉。 */
    private fun capPick(ctx: android.content.Context, text: String, click: (TextView) -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text + " ▾"
            textSize = LayoutMetrics.settingsCapTextSize(ctx)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(GlassColors.CAP_TEXT)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 保留 font padding，避免 CJK 字底被裁（安全区域「全屏」等）。
            includeFontPadding = true
            minimumHeight = LayoutMetrics.capMinH(ctx)
            val ph = LayoutMetrics.capPadH(ctx)
            val pv = LayoutMetrics.capPadV(ctx)
            setPadding(ph, pv, ph, pv)
            isClickable = true
            isFocusable = true
            setOnClickListener { click(this) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(GlassColors.ACCENT_CAP_FILL)
                setStroke(dp(1), GlassColors.ACCENT_SOFT_STROKE)
                cornerRadius = dp(999).toFloat()
            }
        }

    /** 静态值标签 .cap（不可点，如 Root 权限已获取）。 */

    private fun divider(ctx: android.content.Context): View = View(ctx).apply {
        setBackgroundColor(GlassColors.DIVIDER)
        val g = LayoutMetrics.dividerGap(ctx)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = g; bottomMargin = g
        }
    }

    private fun glassSwitchRow(ctx: android.content.Context, title: String, value: Boolean, save: (Boolean) -> Unit): View {
        val short = LayoutMetrics.isShort(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pv = LayoutMetrics.rowPadV(ctx)
            setPadding(0, pv, 0, pv)
            minimumHeight = LayoutMetrics.rowMinH(ctx)
        }
        row.addView(
            label(ctx, title, LayoutMetrics.settingsRowTextSize(ctx), GlassColors.TEXT_BODY).apply {
                includeFontPadding = false
                maxLines = if (short) 1 else 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, dp(if (short) 6 else 8), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val sw = GlassSwitch(ctx, compact = false).apply {
            setChecked(value, animate = false)
            onCheckedChangeListener = { _, on -> save(on) }
        }
        row.addView(sw)
        return row
    }
}
