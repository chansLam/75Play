package com.shihab.diplay.ui

import android.os.Build
import android.os.Bundle
import android.os.StatFs
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
import com.shihab.diplay.AppLocale
import com.shihab.diplay.DiPlayBluetooth
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.WheelRootInjector
import com.shihab.diplay.host.R

/**
 * FragmentAbout（车辆）：车载三列玻璃卡 —— 车辆 | 系统 | 诊断与硬件。
 * 与设置页同宽三列，触控与字号走 [LayoutMetrics] 车机尺度。
 */
class FragmentAbout : Fragment() {

    private val host get() = requireActivity() as DiPlayHost
    private var versionTaps = 0
    private var lastTap = 0L

    private fun dp(v: Int) = GlassColors.dp(requireContext(), v)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val ctx = requireContext()
        val pad = LayoutMetrics.pagePad(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.bgHome())
            setPadding(pad, pad, pad, pad)
        }
        val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun gap() = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LayoutMetrics.colGap(ctx), 1) }
        val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        cols.addView(columnCard(ctx) { buildVehicleColumn(it) }, colLp)
        cols.addView(gap())
        cols.addView(columnCard(ctx) { buildSystemColumn(it) }, colLp)
        cols.addView(gap())
        cols.addView(columnCard(ctx) { buildHardwareColumn(it) }, colLp)
        root.addView(cols, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    /** 单列玻璃卡片；底边留白防末行裁切。 */
    private fun columnCard(ctx: android.content.Context, build: (LinearLayout) -> Unit): ScrollView {
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        build(card)
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

    /** 左列：驾驶相关（手势 / 方向盘 / 悬浮球 / 开机与自动连接）。 */
    private fun buildVehicleColumn(card: LinearLayout) {
        val ctx = card.context
        cardTitle(card, ctx.getString(R.string.about_title_vehicle))

        val fingers = listOf(
            ctx.getString(R.string.about_gesture_fingers, 2),
            ctx.getString(R.string.about_gesture_fingers, 3),
            ctx.getString(R.string.about_gesture_fingers, 4),
        )
        val cur = AirPlayPersistence.loadSettingsGestureFingers(ctx).coerceIn(2, 4)
        card.addView(settingRow(ctx, ctx.getString(R.string.about_carplay_gesture),
            capPick(ctx, ctx.getString(R.string.about_gesture_fingers, cur)) { cap ->
                showDropdown(cap, fingers, cur - 2) { idx ->
                    AirPlayPersistence.saveSettingsGestureFingers(ctx, idx + 2)
                    cap.text = fingers[idx] + " ▾"
                }
            }))
        card.addView(settingRow(ctx, ctx.getString(R.string.about_wheel_keys_config), ghostButton(ctx, ctx.getString(R.string.about_configure)) { host.openWheelKeysSettings() }))
        card.addView(glassSwitchRow(
            ctx,
            ctx.getString(R.string.settings_floating_ball),
            AirPlayPersistence.loadFloatingBallEnabled(ctx),
        ) { on -> host.setFloatingBallEnabled(on) })
        card.addView(divider(ctx))
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.about_auto_start_carplay), AirPlayPersistence.loadAutoStartOnBoot(ctx)) { on ->
            AirPlayPersistence.saveAutoStartOnBoot(ctx, on)
        })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.about_auto_connect_on_start), DiPlayPreferences.autoConnect(ctx)) { on ->
            DiPlayPreferences.saveAutoConnect(ctx, on)
        })
    }

    /** 中列：语言 / 更新 / Root 方向盘。 */
    private fun buildSystemColumn(card: LinearLayout) {
        val ctx = card.context
        cardTitle(card, ctx.getString(R.string.about_title_system))

        card.addView(settingRow(ctx, ctx.getString(R.string.language_app_language), capPick(ctx, appLanguageLabel(ctx, AppLocale.preference(ctx))) { cap ->
            showDropdown(cap, AppLocale.ALL.map { appLanguageLabel(ctx, it) },
                AppLocale.ALL.indexOf(AppLocale.preference(ctx)).coerceAtLeast(0)) { idx ->
                AppLocale.apply(requireActivity(), AppLocale.ALL[idx])
            }
        }))
        card.addView(settingRow(ctx, ctx.getString(R.string.about_check_for_updates), ghostButton(ctx, ctx.getString(R.string.about_check)) { host.updateCheckNow() }))
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.about_auto_update), host.autoUpdateEnabled()) { host.setAutoUpdateEnabled(it) })

        if (WheelRootInjector.hasRoot()) {
            card.addView(divider(ctx))
            card.addView(settingRow(ctx, ctx.getString(R.string.about_learn_siri_key), ghostButton(ctx, ctx.getString(R.string.about_learn_button)) {
                DiPlayDialog.showInfo(ctx, ctx.getString(R.string.about_learn_siri_key),
                    ctx.getString(R.string.about_learn_siri_hint))
                WheelRootInjector.learn(ctx) { result ->
                    requireActivity().runOnUiThread {
                        val learned = WheelRootInjector.learned(ctx)
                        val injectSuffix = if (learned != null) ctx.getString(R.string.about_root_inject_enabled)
                            else ctx.getString(R.string.about_root_inject_retry)
                        DiPlayDialog.showInfo(ctx, ctx.getString(R.string.about_wheel_root_inject),
                            ctx.getString(R.string.about_root_inject_result, result, injectSuffix))
                    }
                }
            }))
            card.addView(glassSwitchRow(ctx, ctx.getString(R.string.about_wheel_siri_switch), WheelRootInjector.isEnabled(ctx)) { on ->
                if (on && WheelRootInjector.learned(ctx) == null) {
                    DiPlayDialog.showInfo(ctx, ctx.getString(R.string.about_need_learn_title),
                        ctx.getString(R.string.about_need_learn_hint))
                    return@glassSwitchRow
                }
                WheelRootInjector.setEnabled(ctx, on)
            })
        }
    }

    // ---------- 右列：诊断与硬件 ----------
    private fun buildHardwareColumn(card: LinearLayout) {
        val ctx = card.context
        cardTitle(card, ctx.getString(R.string.about_title_hardware))
        // —— 提示词 §4.3 右列：导出/查看/保存位置 ——
        card.addView(settingRow(ctx, ctx.getString(R.string.about_export_report), ghostButton(ctx, ctx.getString(R.string.about_export)) { host.exportDiagnosticsNow() }))
        card.addView(settingRow(ctx, ctx.getString(R.string.about_view_report), ghostButton(ctx, ctx.getString(R.string.about_view)) { host.exportDiagnosticsNow() }))
        card.addView(settingRow(ctx, ctx.getString(R.string.choose_save_location), capPick(ctx, ctx.getString(R.string.about_default)) { host.chooseReportDestination() }))
        card.addView(divider(ctx))

        // 版本（cap 样式 + 5 连击进入开发者终端）
        val versionCap = capVal(ctx, ctx.getString(R.string.about_version_cap, host.versionString()), GlassColors.CAP_TEXT).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTap > 2000) versionTaps = 0
                lastTap = now
                versionTaps++
                if (versionTaps >= 5) {
                    versionTaps = 0
                    host.openDevTerminal()
                }
            }
        }
        card.addView(settingRow(ctx, ctx.getString(R.string.about_version), versionCap))
        card.addView(settingRow(ctx, ctx.getString(R.string.about_open_source_license), ghostButton(ctx, ctx.getString(R.string.about_view)) {
            DiPlayDialog.showLongText(ctx, ctx.getString(R.string.about_open_source_license),
                ctx.getString(R.string.about_license_text))
        }))
        card.addView(label(ctx, ctx.getString(R.string.about_dev_tip), 13f, GlassColors.TEXT_MUTED).apply { setPadding(0, dp(2), 0, 0) })
        card.addView(divider(ctx))

        // 设备信息（全部 cap）
        addInfoRowCap(card, ctx, ctx.getString(R.string.about_device_model), safe(ctx, Build.MODEL))
        addInfoRowCap(card, ctx, ctx.getString(R.string.about_hardware_platform), safe(ctx, Build.HARDWARE))
        addInfoRowCap(card, ctx, ctx.getString(R.string.about_system_version), "${safe(ctx, Build.VERSION.RELEASE)} (API ${Build.VERSION.SDK_INT})")
        addInfoRowCap(card, ctx, ctx.getString(R.string.about_bluetooth_mac), DiPlayBluetooth.localAddress(requireActivity()) ?: ctx.getString(R.string.about_unknown))
        addInfoRowCap(card, ctx, ctx.getString(R.string.about_internal_storage), freeStorage())
        addInfoRowCap(card, ctx, ctx.getString(R.string.about_build_time), "—")
    }

    private fun addInfoRowCap(card: LinearLayout, ctx: android.content.Context, key: String, value: String) {
        card.addView(settingRow(ctx, key, capVal(ctx, value.ifBlank { ctx.getString(R.string.about_unknown) }, GlassColors.TEXT_BODY)))
    }

    // ---------- helpers ----------
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

    private fun divider(ctx: android.content.Context): View =
        View(ctx).apply {
            setBackgroundColor(if (GlassColors.isDark) 0x12FFFFFF.toInt() else 0x140B2233.toInt())
            val g = LayoutMetrics.dividerGap(ctx)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = g
                bottomMargin = g
            }
        }

    private fun lpWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)


    // ---------- row/cap/ghost helpers（对齐提示词 §3.2/§3.3/§3.5） ----------
    private fun appLanguageLabel(ctx: android.content.Context, code: String): String = when (code) {
        AppLocale.SYSTEM -> ctx.getString(R.string.language_system_default)
        AppLocale.ENGLISH -> "English"
        AppLocale.SIMPLIFIED_CHINESE -> ctx.getString(R.string.about_lang_simplified)
        AppLocale.TRADITIONAL_CHINESE -> ctx.getString(R.string.about_lang_traditional)
        AppLocale.ARABIC -> "العربية"
        AppLocale.RUSSIAN -> "Русский"
        AppLocale.SPANISH -> "Español"
        AppLocale.UKRAINIAN -> "Українська"
        else -> ctx.getString(R.string.about_lang_simplified)
    }

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

    /** 静态值标签 .cap（不可点）。 */
    private fun capVal(ctx: android.content.Context, text: String, color: Int = GlassColors.CAP_TEXT): TextView =
        TextView(ctx).apply {
            this.text = text
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

    /** 次级按钮 .ghostbtn：车机尺度 padding / 字号。 */
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
            background = GlassColors.ghostDrawable(ctx, cornerRadiusDp = 12)
        }

    /** 玻璃下拉：紧凑宽高 + 打开期间压制系统导航栏。 */
    private fun showDropdown(anchor: View, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        GlassDropdown.show(anchor, labels, selected, onSelect, syncAnchorText = false)
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


    private fun freeStorage(): String = runCatching {
        val stat = StatFs(requireActivity().filesDir.path)
        val bytes = stat.availableBytes
        String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }.getOrDefault(requireContext().getString(R.string.about_unknown))

    private fun safe(ctx: android.content.Context, value: String): String = if (value.isBlank()) ctx.getString(R.string.about_unknown) else value


}
