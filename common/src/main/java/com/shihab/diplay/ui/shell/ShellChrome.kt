// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.shell

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.shihab.diplay.ui.HudPanelDrawable
import com.shihab.diplay.ui.HudNavDrawable
import com.shihab.diplay.ui.LayoutMetrics
import com.shihab.diplay.ui.SpindleDivider
import com.shihab.diplay.ui.theme.ShellShape
import com.shihab.diplay.ui.theme.ShellSpacing
import com.shihab.diplay.ui.theme.ShellStroke
import com.shihab.diplay.wallpaper.ShellWallpaper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 壳层左侧窄导航、顶栏和 Fragment 容器。
 * 宽屏左轨和页面之间、顶栏和内容之间使用同一层级分隔线。
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

    private var rootView: LinearLayout? = null
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
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.bgHome())
        }
        rootView = root
        val container = FrameLayout(activity)
        container.id = R.id.fragment_container
        fragmentContainer = container
        if (wide) {
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(GlassColors.dockBg())
            }
            header.addView(railMark(), LinearLayout.LayoutParams(
                LayoutMetrics.navWidth(activity), ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            header.addView(buildStatusBar(embedded = true), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
            ))
            root.addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.statusBarH(activity),
            ))
            val body = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                val inset = LayoutMetrics.pagePad(activity)
                setPadding(inset, 0, inset, inset)
            }
            body.addView(buildLeftNav(withMark = false), LinearLayout.LayoutParams(
                LayoutMetrics.navWidth(activity), ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            body.addView(View(activity), LinearLayout.LayoutParams(
                LayoutMetrics.colGap(activity), ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            body.addView(container, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            root.addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
        } else {
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            content.addView(buildStatusBar(embedded = false), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.statusBarH(activity),
            ))
            content.addView(SpindleDivider(activity), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ShellSpacing.LARGE),
            ))
            content.addView(container, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            root.addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            root.addView(buildBottomNav(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutMetrics.navEntryMinH(activity),
            ))
        }
        activity.setContentView(root)
        applyWallpaper()
    }

    fun applyWallpaper() {
        val root = rootView ?: return
        ShellWallpaper.apply(root)
    }

    fun updateClock() {
        val now = Date()
        statusClock?.text = android.text.format.DateFormat.getTimeFormat(activity).format(now)
        statusDate?.text = SimpleDateFormat("M/d EEE", Locale.getDefault()).format(now)
    }

    /** 顶栏连接胶囊：字、底、描边和前面的圆点一起换。壳还没装上时忽略。 */
    fun applyStatus(label: String, color: Int, fill: Int, @Suppress("UNUSED_PARAMETER") stroke: Int) {
        val chip = status ?: return
        chip.text = label
        chip.setTextColor(color)
        statusBackground?.setColor(fill)
        statusBackground?.setStroke(maxOf(1, dp(ShellStroke.HAIRLINE)), GlassColors.GLASS_RIM)
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
            val start = if (icon.visibility == View.VISIBLE && !first) dp(ShellSpacing.SMALL) else 0
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
     *  - 回前台后语言被系统改回去（onResume / onWindowFocusChanged 里 enforceAppLocale）；
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

    /** 顶栏用完整栏目名；竖屏只用导航短标签，给连接状态和时钟留位置。 */
    private fun pageTitle(target: String): String {
        if (!LayoutMetrics.isWide(activity)) {
            return when (target) {
                ShellNavigator.HOME -> activity.getString(R.string.shell_nav_dashboard)
                ShellNavigator.ABOUT -> activity.getString(R.string.shell_nav_vehicle_device)
                else -> activity.getString(R.string.shell_nav_control_center)
            }
        }
        return when (target) {
            ShellNavigator.HOME -> activity.getString(R.string.shell_nav_dashboard)
            ShellNavigator.ABOUT -> activity.getString(R.string.shell_nav_vehicle_device)
            else -> activity.getString(R.string.shell_nav_control_center)
        }
    }

    /**
     * 顶栏：页名在左，右侧是连接胶囊、无线电胶囊、日期和时钟。
     * 宽屏时嵌进整条顶栏，和左侧标志共用同一块底，不再另铺一层。
     */
    private fun buildStatusBar(embedded: Boolean): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (!embedded) setBackgroundResource(GlassColors.dockBg())
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val ph = LayoutMetrics.pagePad(activity)
            setPadding(ph, 0, ph, 0)
        }
        val wide = LayoutMetrics.isWide(activity)
        statusTitle = TextView(activity).apply {
            setTextColor(GlassColors.TEXT_PRIMARY)
            textSize = LayoutMetrics.headerTitleSize(activity)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        row.addView(statusTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(if (wide) 12 else 8)
        })
        statusBackground = rounded(
            GlassColors.GLASS_CHIP,
            GlassColors.GLASS_RIM,
            dp(ShellShape.SMALL),
            ShellStroke.HAIRLINE,
        )
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(GlassColors.TEXT_MUTED)
            val s = dp(ShellSpacing.SMALL)
            setSize(s, s)
        }
        statusDot = dot
        status = TextView(activity).apply {
            text = activity.getString(R.string.home_not_connected)
            textSize = LayoutMetrics.statusPillTextSize(activity)
            setTextColor(GlassColors.TEXT_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            maxWidth = dp(if (wide) 280 else 148)
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ShellSpacing.DOUBLE_EXTRA_LARGE)
            compoundDrawablePadding = dp(ShellSpacing.SMALL)
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
        ).apply { marginEnd = dp(ShellSpacing.SMALL) })
        row.addView(radioCluster(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(ShellSpacing.MEDIUM) })
        row.addView(clockCluster(showDate = wide))
        bar.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return bar
    }

    /** 日期和时钟靠在一起，中间一根短线，时钟是这一组里最醒目的字。 */
    private fun clockCluster(showDate: Boolean): View {
        val cluster = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDate = TextView(activity).apply {
            setTextColor(GlassColors.TEXT_MUTED)
            textSize = LayoutMetrics.statusDateSize(activity)
            maxLines = 1
            includeFontPadding = false
            visibility = if (showDate) View.VISIBLE else View.GONE
        }
        cluster.addView(statusDate)
        cluster.addView(View(activity).apply {
            setBackgroundColor(GlassColors.DIM)
            visibility = if (showDate) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(maxOf(1, dp(ShellStroke.HAIRLINE)), dp(ShellSpacing.MEDIUM)).apply {
            marginStart = if (showDate) dp(ShellSpacing.MEDIUM) else 0
            marginEnd = if (showDate) dp(ShellSpacing.MEDIUM) else 0
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
            val py = dp(ShellSpacing.EXTRA_SMALL)
            minimumHeight = dp(ShellSpacing.DOUBLE_EXTRA_LARGE)
            setPadding(dp(ShellSpacing.SMALL), py, dp(ShellSpacing.SMALL), py)
            background = rounded(
                GlassColors.GLASS_CHIP,
                GlassColors.GLASS_RIM,
                dp(ShellShape.SMALL),
                ShellStroke.HAIRLINE,
            )
        }
        val size = dp(ShellSpacing.LARGE)
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

    /**
     * 左侧窄轨。宽屏时标志已经放进整条顶栏，轨道从顶栏底边接上。
     * 竖屏不走这里。
     */
    private fun buildLeftNav(withMark: Boolean): View {
        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = HudPanelDrawable(activity)
        }
        if (withMark) {
            nav.addView(railMark(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, LayoutMetrics.statusBarH(activity),
            ))
        }
        val destinations = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ShellSpacing.EXTRA_SMALL)
            setPadding(pad, dp(ShellSpacing.SMALL), pad, pad)
        }
        addDestinations(destinations, rail = true)
        // 三个入口平分导航栏的可用高度：原先挤在顶部、下面靠一段空白撑开，显得又紧又空。
        nav.addView(destinations, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        nav.addView(TextView(activity).apply {
            text = versionLabel()
            setTextColor(GlassColors.DIM)
            textSize = LayoutMetrics.navVersionSize(activity)
            gravity = Gravity.CENTER
            maxLines = 1
            // 版本号原先只留 pagePad 的底距，在宽屏上几乎贴到屏幕底边。
            setPadding(
                dp(ShellSpacing.EXTRA_SMALL),
                dp(ShellSpacing.SMALL),
                dp(ShellSpacing.EXTRA_SMALL),
                LayoutMetrics.pagePad(activity) + dp(ShellSpacing.SMALL),
            )
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return nav
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
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return header
    }

    /** 竖屏底部三入口。选中项是一枚胶囊。 */
    private fun buildBottomNav(): View {
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.dockBg())
        }
        shell.addView(SpindleDivider(activity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ShellSpacing.MEDIUM),
        ))
        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addDestinations(nav, rail = false)
        shell.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return shell
    }

    private fun addDestinations(nav: LinearLayout, rail: Boolean) {
        navEntry(nav, "home", activity.getString(R.string.shell_nav_dashboard), activity.getString(R.string.shell_nav_dashboard), R.drawable.ic_dp_overview, rail)
        navEntry(nav, "settings", activity.getString(R.string.shell_nav_control_center), activity.getString(R.string.shell_nav_control_center), R.drawable.ic_dp_settings, rail)
        navEntry(nav, "about", activity.getString(R.string.shell_nav_vehicle_device), activity.getString(R.string.shell_nav_vehicle_device), R.drawable.ic_dp_vehicle, rail)
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
            maxLines = if (rail) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(ShellSpacing.EXTRA_SMALL), dp(ShellSpacing.EXTRA_SMALL), dp(ShellSpacing.EXTRA_SMALL), 0)
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
                // 竖排入口等分导航栏高度，图标与标签整体居中。
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
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
            entry.label.setTextColor(if (on) GlassColors.NAV_SELECTED_CONTAINER_TEXT else GlassColors.NAV_UNSELECTED)
            entry.label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            entry.icon.setColorFilter(if (on) GlassColors.NAV_SELECTED_CONTAINER_TEXT else GlassColors.NAV_UNSELECTED)
            renderNavRow(target)
        }
    }

    /** Recompute tonal selected, focus, and press states after navigation changes. */
    private fun renderNavRow(target: String) {
        val entry = navEntries[target] ?: return
        val on = target == shown
        val focused = entry.row.isFocused
        entry.row.background = when {
            on -> HudNavDrawable(activity, active = true)
            focused || entry.row.isPressed -> HudNavDrawable(activity, active = false)
            else -> null
        }
    }

    private fun rounded(fill: Int, stroke: Int, radiusPx: Int, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setCornerRadius(radiusPx.toFloat())
            setColor(fill)
            if (strokeDp > 0) setStroke(maxOf(1, dp(strokeDp)), stroke)
        }

}
