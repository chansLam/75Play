// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.shell

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.FragmentAbout
import com.shihab.diplay.ui.FragmentHome
import com.shihab.diplay.ui.FragmentSetting
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.LayoutMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 壳层左侧窄导航、顶栏和 Fragment 容器。
 * 宽屏左轨和顶栏共用一条底边，标志与页名齐平。选中是玻璃胶囊。
 * 顶栏右侧分成连接状态、无线电、日期时钟三组。竖屏目的地在底栏，选中同样是胶囊。
 * 页 id 仍由 [ShellNavigator] 持有。三页各留一个 Fragment，切换时 show/hide，避免拆掉滚动和扫描列表。
 */
class ShellChrome(
    private val activity: FragmentActivity,
    private val versionLabel: () -> String,
    private val dp: (Int) -> Int,
) {
    data class NavEntry(val row: LinearLayout, val label: TextView, val icon: ImageView)

    private var status: TextView? = null
    private var statusBackground: GradientDrawable? = null

    private var fragmentContainer: FrameLayout? = null
    private val pages = linkedMapOf<String, Fragment>()
    private var shown: String? = null
    private val navEntries = mutableMapOf<String, NavEntry>()
    private var statusTitle: TextView? = null
    private var statusClock: TextView? = null
    private var statusDate: TextView? = null
    private var statusDot: GradientDrawable? = null
    private var signalIcon: ImageView? = null
    private var wifiIcon: ImageView? = null
    private var hotspotIcon: ImageView? = null
    private var bluetoothIcon: ImageView? = null
    private val signalGlyph = ShellStatusIconDrawable(ShellStatusIconDrawable.Kind.SIGNAL)
    private val wifiGlyph = ShellStatusIconDrawable(ShellStatusIconDrawable.Kind.WIFI)
    private val hotspotGlyph = ShellStatusIconDrawable(ShellStatusIconDrawable.Kind.HOTSPOT)
    private val bluetoothGlyph = ShellStatusIconDrawable(ShellStatusIconDrawable.Kind.BLUETOOTH)
    private var currentPage: () -> String = { ShellNavigator.HOME }
    private var requestPage: (String) -> Unit = {}

    fun install(currentPage: () -> String, requestPage: (String) -> Unit) {
        this.currentPage = currentPage
        this.requestPage = requestPage
        discardPages()
        navEntries.clear()
        val wide = LayoutMetrics.isWide(activity)
        val root = LinearLayout(activity).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.bgHome())
        }
        val container = FrameLayout(activity)
        container.id = R.id.fragment_container
        fragmentContainer = container
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(buildStatusBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.statusBarH(activity),
        ))
        content.addView(container, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        if (wide) {
            root.addView(buildLeftNav(), LinearLayout.LayoutParams(
                LayoutMetrics.navWidth(activity), ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            root.addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            root.addView(buildBottomNav(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutMetrics.navEntryMinH(activity),
            ))
        }
        activity.setContentView(root)
    }

    fun updateClock() {
        val now = Date()
        statusClock?.text = android.text.format.DateFormat.getTimeFormat(activity).format(now)
        statusDate?.text = SimpleDateFormat("M/d EEE", Locale.getDefault()).format(now)
    }

    /** 顶栏连接胶囊：字、底、描边和前面的圆点一起换。壳还没装上时忽略。 */
    fun applyStatus(label: String, color: Int, fill: Int, stroke: Int) {
        val chip = status ?: return
        chip.text = label
        chip.setTextColor(color)
        statusBackground?.setColor(fill)
        statusBackground?.setStroke(maxOf(1, dp(1)), stroke)
        statusDot?.setColor(color)
    }

    /** 顶栏右侧的信号 / Wi-Fi / 蓝牙。没有电话模块时信号位收起；热点没开时不占位。 */
    fun updateRadios(snapshot: ShellStatusSnapshot) {
        val signal = signalIcon ?: return
        val wifi = wifiIcon ?: return
        val hotspot = hotspotIcon ?: return
        val bluetooth = bluetoothIcon ?: return
        signal.visibility = if (snapshot.cellVisible) View.VISIBLE else View.GONE
        signalGlyph.show(
            if (snapshot.cellInService) ShellStatusIconDrawable.Phase.ON else ShellStatusIconDrawable.Phase.OFF,
            snapshot.cellBars,
        )
        signal.contentDescription = if (snapshot.cellInService && snapshot.cellBars > 0) {
            activity.getString(R.string.shell_status_signal, snapshot.cellBars)
        } else {
            activity.getString(R.string.shell_status_signal_none)
        }
        wifiGlyph.show(
            when {
                !snapshot.wifiOn -> ShellStatusIconDrawable.Phase.OFF
                snapshot.wifiConnected -> ShellStatusIconDrawable.Phase.LINKED
                else -> ShellStatusIconDrawable.Phase.IDLE
            },
            snapshot.wifiBars,
        )
        wifi.contentDescription = when {
            !snapshot.wifiOn -> activity.getString(R.string.shell_status_wifi_off)
            snapshot.wifiConnected && snapshot.wifiBars > 0 ->
                activity.getString(R.string.shell_status_wifi_level, snapshot.wifiBars)
            else -> activity.getString(R.string.shell_status_wifi_on)
        }
        hotspot.visibility = if (snapshot.hotspotOn) View.VISIBLE else View.GONE
        hotspotGlyph.show(ShellStatusIconDrawable.Phase.ON)
        hotspot.contentDescription = activity.getString(R.string.shell_status_hotspot)
        bluetoothGlyph.show(
            when (snapshot.bluetooth) {
                ShellBluetooth.CONNECTED -> ShellStatusIconDrawable.Phase.LINKED
                ShellBluetooth.ON -> ShellStatusIconDrawable.Phase.ON
                ShellBluetooth.OFF -> ShellStatusIconDrawable.Phase.OFF
            },
        )
        bluetooth.contentDescription = when (snapshot.bluetooth) {
            ShellBluetooth.CONNECTED -> activity.getString(R.string.shell_status_bluetooth_connected)
            ShellBluetooth.ON -> activity.getString(R.string.shell_status_bluetooth_on)
            ShellBluetooth.OFF -> activity.getString(R.string.shell_status_bluetooth_off)
        }
        var first = true
        listOf(signal, wifi, hotspot, bluetooth).forEach { icon ->
            val lp = icon.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            val start = if (icon.visibility == View.VISIBLE && !first) dp(7) else 0
            if (icon.visibility == View.VISIBLE) first = false
            if (lp.marginStart != start) {
                lp.marginStart = start
                icon.layoutParams = lp
            }
        }
    }

    /**
     * 密度、方向或主题重建后丢掉缓存，下一页按新尺寸再画。
     *
     * 必须调用本方法（或直接 recreate() 整个 Activity——FragmentManager 随之销毁，缓存自然失效）
     * 的入口：
     *  - [android.app.Activity.onConfigurationChanged]（旋转 / 系统密度变化）；
     *  - [android.app.Activity.onMultiWindowModeChanged]（分屏切换）；
     *  - 回前台后发现 ROM 把密度改回去了（onResume / onWindowFocusChanged 里 enforceInterfaceSize）；
     *  - 主题 / 深色模式切换走 Activity 重建时无需单独调用（recreate 即等价）。
     * 语言切换目前走 [android.app.Activity.recreate]，同样无需单独调用。
     * 注意：本方法只清缓存，不改当前页（shown）导航状态，调用方随后要再 showPage(page)。
     */
    fun discardPages() {
        val fm = activity.supportFragmentManager
        fm.executePendingTransactions()
        val tx = fm.beginTransaction()
        listOf(ShellNavigator.HOME, ShellNavigator.SETTINGS, ShellNavigator.ABOUT).forEach { tag ->
            fm.findFragmentByTag(tag)?.let { if (it.isAdded) tx.remove(it) }
        }
        pages.clear()
        shown = null
        commit(tx)
    }

    /** 只丢掉一页，让它按当前偏好重新绑定。其它页的滚动保留。 */
    fun discardPage(target: String) {
        val key = pageKey(target)
        val fm = activity.supportFragmentManager
        fm.executePendingTransactions()
        val existing = fm.findFragmentByTag(key)
        pages.remove(key)
        if (shown == key) shown = null
        if (existing != null && existing.isAdded) {
            commit(fm.beginTransaction().remove(existing))
        }
    }

    fun applyPage(target: String) {
        val key = pageKey(target)
        val container = fragmentContainer ?: return
        val fm = activity.supportFragmentManager
        val fragment = pages.getOrPut(key) { newPage(key) }
        if (shown == key && fragment.isAdded && !fragment.isHidden) {
            updateNavHighlight(key)
            if (fragment is FragmentHome && fragment.isResumed) fragment.refreshFromHost()
            return
        }
        val tx = fm.beginTransaction()
        pages.forEach { (pageKey, existing) ->
            if (pageKey != key && existing.isAdded) tx.hide(existing)
        }
        if (fragment.isAdded) tx.show(fragment) else tx.add(container.id, fragment, key)
        commit(tx)
        shown = key
        updateNavHighlight(key)
    }

    private fun newPage(key: String): Fragment = when (key) {
        ShellNavigator.HOME -> FragmentHome()
        ShellNavigator.ABOUT -> FragmentAbout()
        else -> FragmentSetting()
    }

    private fun pageKey(target: String): String = when (target) {
        ShellNavigator.HOME, ShellNavigator.ABOUT -> target
        else -> ShellNavigator.SETTINGS
    }

    private fun commit(tx: FragmentTransaction) {
        if (tx.isEmpty) return
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) tx.commitAllowingStateLoss() else tx.commitNow()
    }

    /** 顶栏用完整栏目名；左侧轨只放短标签，避免 112dp 轨里挤长句。 */
    private fun pageTitle(target: String): String = when (target) {
        ShellNavigator.HOME -> activity.getString(R.string.diplay_nav_home)
        ShellNavigator.ABOUT -> activity.getString(R.string.diplay_nav_vehicle_system)
        else -> activity.getString(R.string.diplay_nav_display_audio)
    }

    /** 顶栏：页名在左，右侧是连接胶囊、无线电胶囊、日期和时钟。目的地在左轨或竖屏底栏。 */
    private fun buildStatusBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.dockBg())
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val ph = LayoutMetrics.pagePad(activity)
            setPadding(ph, 0, ph, 0)
        }
        statusTitle = TextView(activity).apply {
            setTextColor(GlassColors.TEXT_PRIMARY)
            textSize = LayoutMetrics.headerTitleSize(activity)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        row.addView(statusTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(12)
        })
        statusBackground = rounded(GlassColors.GLASS_CHIP, GlassColors.GLASS_RIM, dp(10))
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(GlassColors.TEXT_MUTED)
            val s = dp(6)
            setSize(s, s)
        }
        statusDot = dot
        status = TextView(activity).apply {
            text = activity.getString(R.string.home_not_connected)
            textSize = LayoutMetrics.statusPillTextSize(activity)
            setTextColor(GlassColors.TEXT_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            maxWidth = dp(280)
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(26)
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            setPadding(
                LayoutMetrics.statusPillPadH(activity),
                LayoutMetrics.statusPillPadV(activity),
                LayoutMetrics.statusPillPadH(activity),
                LayoutMetrics.statusPillPadV(activity),
            )
            background = statusBackground
        }
        row.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) })
        row.addView(radioCluster(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(12) })
        row.addView(clockCluster())
        bar.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        bar.addView(edgeView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        return bar
    }

    /** 日期和时钟靠在一起，中间一根短线，时钟是这一组里最醒目的字。 */
    private fun clockCluster(): View {
        val cluster = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDate = TextView(activity).apply {
            setTextColor(GlassColors.TEXT_MUTED)
            textSize = LayoutMetrics.statusDateSize(activity)
            maxLines = 1
            includeFontPadding = false
        }
        cluster.addView(statusDate)
        cluster.addView(View(activity).apply {
            setBackgroundColor(GlassColors.DIM)
        }, LinearLayout.LayoutParams(maxOf(1, dp(1)), dp(12)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
            gravity = Gravity.CENTER_VERTICAL
        })
        statusClock = TextView(activity).apply {
            setTextColor(GlassColors.TEXT_PRIMARY)
            textSize = LayoutMetrics.statusClockSize(activity)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        cluster.addView(statusClock)
        return cluster
    }

    /** 信号、Wi-Fi、热点、蓝牙收进一枚胶囊，高度跟顶栏走，不另起一行。 */
    private fun radioCluster(): View {
        val cluster = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val py = dp(4)
            minimumHeight = dp(26)
            setPadding(dp(8), py, dp(8), py)
            background = rounded(GlassColors.GLASS_CHIP, GlassColors.GLASS_RIM, dp(10))
        }
        val size = dp(15)
        fun slot(glyph: ShellStatusIconDrawable, description: String, visible: Boolean): ImageView =
            ImageView(activity).apply {
                setImageDrawable(glyph)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = description
                isFocusable = false
                isClickable = false
                visibility = if (visible) View.VISIBLE else View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        signalIcon = slot(signalGlyph, activity.getString(R.string.shell_status_signal_none), visible = false)
        wifiIcon = slot(wifiGlyph, activity.getString(R.string.shell_status_wifi_off), visible = true)
        hotspotIcon = slot(hotspotGlyph, activity.getString(R.string.shell_status_hotspot), visible = false)
        bluetoothIcon = slot(bluetoothGlyph, activity.getString(R.string.shell_status_bluetooth_off), visible = true)
        listOf(signalIcon, wifiIcon, hotspotIcon, bluetoothIcon).forEach { icon ->
            cluster.addView(icon, LinearLayout.LayoutParams(size, size))
        }
        return cluster
    }

    /** 左侧窄轨。顶上一格和顶栏同高，标志与页名齐平，底下是目的地和版本。 */
    private fun buildLeftNav(): View {
        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.dockBg())
        }
        nav.addView(railMark(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.statusBarH(activity),
        ))
        val destinations = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(4)
            setPadding(pad, dp(6), pad, pad)
        }
        addDestinations(destinations, rail = true)
        nav.addView(destinations, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        nav.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
        nav.addView(TextView(activity).apply {
            text = versionLabel()
            setTextColor(GlassColors.DIM)
            textSize = LayoutMetrics.navVersionSize(activity)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(4), 0, dp(4), LayoutMetrics.pagePad(activity))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val rail = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        rail.addView(nav, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        rail.addView(edgeView(), LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
        return rail
    }

    /** 和顶栏同高的标志格，底边和顶栏底边连成一条。 */
    private fun railMark(): View {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val logo = LayoutMetrics.navLogoSize(activity)
        val stage = FrameLayout(activity)
        stage.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_carplay)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = activity.getString(R.string.app_name) + " " + versionLabel()
        }, FrameLayout.LayoutParams(logo, logo, Gravity.CENTER))
        header.addView(stage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        header.addView(edgeView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        return header
    }

    /** 竖屏底部三入口。选中项是一枚胶囊。 */
    private fun buildBottomNav(): View {
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.dockBg())
        }
        shell.addView(edgeView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addDestinations(nav, rail = false)
        shell.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return shell
    }

    private fun addDestinations(nav: LinearLayout, rail: Boolean) {
        navEntry(nav, "home", activity.getString(R.string.diplay_nav_home), activity.getString(R.string.diplay_nav_home), R.drawable.ic_dp_overview, rail)
        navEntry(nav, "settings", activity.getString(R.string.settings), activity.getString(R.string.diplay_nav_display_audio), R.drawable.ic_dp_settings, rail)
        navEntry(nav, "about", activity.getString(R.string.settings_vehicle), activity.getString(R.string.diplay_nav_vehicle_system), R.drawable.ic_dp_vehicle, rail)
    }

    /**
     * 图标在上、短标签在下。
     * [rail] 为 true 时高度只包住内容；否则是竖屏底栏项，三项均分。
     * [shortLabel] 用于展示；[fullLabel] 写入 contentDescription。
     */
    private fun navEntry(nav: LinearLayout, target: String, shortLabel: String, fullLabel: String, icon: Int, rail: Boolean) {
        val iconSize = LayoutMetrics.navIconSize(activity)
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = fullLabel
            if (rail) minimumHeight = LayoutMetrics.navEntryMinH(activity)
            setOnClickListener { if (currentPage() != target) requestPage(target) }
            setOnFocusChangeListener { _, _ -> renderNavRow(target) }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.post { renderNavRow(target) }
                }
                false
            }
        }
        val itemIcon = ImageView(activity).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(GlassColors.NAV_UNSELECTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(activity).apply {
            text = shortLabel
            textSize = LayoutMetrics.navLabelSize(activity)
            setTextColor(GlassColors.NAV_UNSELECTED)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(4), dp(2), dp(4), 0)
        }
        shell.addView(itemIcon, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        shell.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        nav.addView(
            shell,
            if (rail) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            },
        )
        navEntries[target] = NavEntry(shell, label, itemIcon)
    }

    private fun updateNavHighlight(selected: String) {
        statusTitle?.text = pageTitle(selected)
        navEntries.forEach { (target, entry) ->
            val on = target == selected
            val tint = if (on) GlassColors.NAV_SELECTED_TEXT else GlassColors.NAV_UNSELECTED
            entry.label.setTextColor(tint)
            entry.label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            entry.icon.setColorFilter(tint)
            renderNavRow(target)
        }
    }

    /** 焦点、按下或换页后重算背景：选中青胶囊，方向盘焦点加粗描边，按下是一块浅玻璃。 */
    private fun renderNavRow(target: String) {
        val entry = navEntries[target] ?: return
        val on = target == shown
        val focused = entry.row.isFocused
        entry.row.background = when {
            on && focused -> accentChip(0xCCFFFFFF.toInt(), 2)
            on -> accentChip(0x66FFFFFF, 1)
            focused -> navChip(GlassColors.ACCENT_SOFT_FILL, GlassColors.ACCENT, 2)
            entry.row.isPressed -> navChip(GlassColors.GLASS_CHIP, GlassColors.GLASS_RIM, 1)
            else -> null
        }
    }

    /** 选中导航：上亮下深的青胶囊，和主按钮同一套渐变。 */
    private fun accentChip(stroke: Int, strokeDp: Int): InsetDrawable {
        val inset = dp(3)
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(GlassColors.ACCENT_LIFT, GlassColors.ACCENT_DEEP),
        ).apply {
            cornerRadius = dp(10).toFloat()
            setStroke(maxOf(1, dp(strokeDp)), stroke)
        }
        return InsetDrawable(bg, inset, inset, inset, inset)
    }

    private fun navChip(fill: Int, stroke: Int, strokeDp: Int): InsetDrawable {
        val inset = dp(3)
        return InsetDrawable(rounded(fill, stroke, dp(10), strokeDp), inset, inset, inset, inset)
    }

    private fun rounded(fill: Int, stroke: Int, radiusPx: Int, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusPx.toFloat()
            setColor(fill)
            setStroke(maxOf(1, dp(strokeDp)), stroke)
        }

    private fun edgeView(): View = View(activity).apply { setBackgroundColor(GlassColors.GLASS_EDGE) }
}
