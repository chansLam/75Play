package com.shihab.diplay.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import com.shihab.diplay.CarAppCatalog
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.OemSettingsIntents
import com.shihab.diplay.compat.waitForMillis
import com.shihab.diplay.host.ImmersiveBars
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.settings.GlassSettingsRows

/**
 * 主页右侧车机应用网格：枚举 / 隐藏·排序 prefs / 拖拽换位 / 管理弹窗 / 启动与卸载。
 * 由 [FragmentHome] 持有；行为与抽出前一致。
 */
internal object HomeAppMenu {
    private var window: PopupWindow? = null

    fun track(popup: PopupWindow) {
        val previous = window
        window = popup
        if (previous !== popup) previous?.dismiss()
    }

    fun clear(popup: PopupWindow) {
        if (window === popup) window = null
    }

    fun dismiss(): Boolean {
        val popup = window ?: return false
        if (!popup.isShowing) {
            window = null
            return false
        }
        popup.dismiss()
        return true
    }
}

internal class HomeAppGrid(
    private val fragment: FragmentHome,
    private val handler: Handler,
) {
    /** 车机软件网格；管理弹窗勾选后据此重绘。 */
    private var appGrid: LinearLayout? = null
    // 应用网格拖拽排序状态
    private var visibleApps: List<CarAppCatalog.App> = emptyList()
    private var dragIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var dragView: View? = null
    private val dragFloat = HomeAppDragFloat()
    /** 拖动过程中格子保持原样，只记录手指下的落点，松手再插入。中途重绘会拆掉触摸目标，手势被取消。 */
    private var hoverIndex = -1
    private var dropHighlight: View? = null
    private val longPressRunnable = object : Runnable {
        override fun run() {
            val v = dragView ?: return
            dragging = true
            hoverIndex = dragIndex
            v.isPressed = false
            v.parent?.requestDisallowInterceptTouchEvent(true)
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startDragFloat(v)
        }
    }

    private fun dp(v: Int) = GlassColors.dp(fragment.requireContext(), v)

    fun buildAppListCard(ctx: android.content.Context): LinearLayout {
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
        headRow.addView(label(ctx, ctx.getString(R.string.home_head_unit_apps), LayoutMetrics.cardTitleSize(ctx), GlassColors.TEXT_PRIMARY, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val manageBtn = Button(ctx).apply {
            text = ctx.getString(R.string.home_manage)
            textSize = LayoutMetrics.manageBtnTextSize(ctx)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(GlassColors.BTN_SECONDARY_TEXT)
            setBackgroundResource(GlassColors.btnSecondary())
            minWidth = LayoutMetrics.manageBtnMinW(ctx)
            minHeight = LayoutMetrics.manageBtnMinH(ctx)
            minimumHeight = LayoutMetrics.manageBtnMinH(ctx)
            // KitKat Button 默认全大写/额外 padding 会挤字；清掉。
            includeFontPadding = true
            setPadding(LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx), LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx))
            setOnClickListener { showManageDialog(ctx) }
        }
        FocusSupport.attachStrokeOverlay(manageBtn, cornerRadiusDp = 10)
        headRow.addView(manageBtn)
        card.addView(headRow)
        card.addView(GlassSettingsRows.titleTick(ctx))
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
        val ctx = fragment.context ?: return
        Thread {
            val apps = CarAppCatalog.enumerate(ctx)
            // Fragment 可能已在 post 前 detach（用户切页/离开），此时 renderAppGrid 里 dp()→requireContext() 会抛
            // "not attached to a context" 闪退，故 post 回来先检查 isAdded。
            handler.post { if (fragment.isAdded) renderAppGrid(grid, apps) }
        }.apply { isDaemon = true }.start()
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
        val tile = SquareTile(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
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
            // 触摸短按在 OnTouchListener 里走 showAppMenu；这里补 OnClickListener 让
            // 方向盘确定键（DPAD_CENTER/ENTER → performClick）也能打开应用菜单。
            setOnClickListener { showAppMenu(app, this) }
            contentDescription = app.label
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
            setTextColor(GlassColors.TEXT_BODY)
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

    /** 应用格交互：几乎没移动的短按才打开菜单；长按后图标浮起跟随手指，松手才插入落点。滑动交给外层 ScrollView。 */
    private fun handleTileTapAndDrag(v: View, e: MotionEvent, app: CarAppCatalog.App, grid: LinearLayout): Boolean {
        val idx = visibleApps.indexOf(app)
        if (idx < 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragIndex = idx
                downX = e.rawX
                downY = e.rawY
                lastRawX = e.rawX
                lastRawY = e.rawY
                dragging = false
                dragView = v
                v.isPressed = true
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawX = e.rawX
                lastRawY = e.rawY
                val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                val moved = Math.abs(e.rawX - downX) > slop || Math.abs(e.rawY - downY) > slop
                if (!dragging) {
                    // 未长按就移动：这是滑动。取消长按，且不要 disallow，让 ScrollView 能拦截。
                    if (moved) {
                        v.isPressed = false
                        handler.removeCallbacks(longPressRunnable)
                    }
                    return true
                }
                moveDragFloat(e.rawX, e.rawY)
                // 落点跟浮起的图标，不跟被图标挡住的指尖。格子先不动，避免拆掉当前触摸目标。
                val target = indexUnderFinger(grid, e.rawX, e.rawY - dp(28))
                if (target >= 0) {
                    hoverIndex = target
                    highlightDrop(grid, target)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                handler.removeCallbacks(longPressRunnable)
                val wasDragging = dragging
                val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                val moved = Math.abs(e.rawX - downX) > slop || Math.abs(e.rawY - downY) > slop
                val changed = wasDragging && placeDraggedApp(app)
                endDrag(grid, save = changed)
                // ScrollView 抢走手势时走 CANCEL。这里只认几乎没移动的抬手。
                if (!wasDragging && !moved) showAppMenu(app, v)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                handler.removeCallbacks(longPressRunnable)
                endDrag(grid, save = false)
                return true
            }
        }
        return true
    }

    private fun startDragFloat(source: View) =
        dragFloat.show(source, lastRawX, lastRawY, dp(28))

    private fun moveDragFloat(rawX: Float, rawY: Float) =
        dragFloat.move(rawX, rawY, dp(28))

    private fun dismissDragFloat() = dragFloat.dismiss()

    private fun placeDraggedApp(app: CarAppCatalog.App): Boolean {
        val from = visibleApps.indexOf(app)
        val to = hoverIndex
        if (from !in visibleApps.indices || to !in visibleApps.indices || from == to) return false
        val list = visibleApps.toMutableList()
        val moved = list[from]
        list[from] = list[to]
        list[to] = moved
        visibleApps = list
        return true
    }

    private fun highlightDrop(grid: LinearLayout, target: Int) {
        val cell = if (target == dragIndex) null else cellAt(grid, target)
        if (cell === dropHighlight) return
        dropHighlight?.alpha = 1f
        if (cell != null && cell !== dragView) cell.alpha = 0.45f
        dropHighlight = cell
    }

    private fun clearDropHighlight() {
        dropHighlight?.alpha = 1f
        dropHighlight = null
    }

    /** 用已布局格子的中心找落点。刚重绘、宽高还是 0 时不换位。 */
    private fun indexUnderFinger(grid: LinearLayout, rawX: Float, rawY: Float): Int {
        var index = 0
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as? LinearLayout ?: continue
            for (c in 0 until row.childCount) {
                if (index >= visibleApps.size) return best
                val cell = row.getChildAt(c)
                if (cell.width > 0 && cell.height > 0) {
                    val loc = IntArray(2)
                    cell.getLocationOnScreen(loc)
                    val dx = rawX - (loc[0] + cell.width / 2f)
                    val dy = rawY - (loc[1] + cell.height / 2f)
                    val dist = dx * dx + dy * dy
                    val reachX = cell.width * 0.7f
                    val reachY = cell.height * 0.7f
                    if (dist < bestDist && dx * dx <= reachX * reachX && dy * dy <= reachY * reachY) {
                        bestDist = dist
                        best = index
                    }
                }
                index++
            }
        }
        return best
    }

    private fun cellAt(grid: LinearLayout, index: Int): View? {
        var cursor = 0
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as? LinearLayout ?: continue
            for (c in 0 until row.childCount) {
                if (cursor == index) return row.getChildAt(c)
                cursor++
            }
        }
        return null
    }

    private fun endDrag(grid: LinearLayout, save: Boolean) {
        dismissDragFloat()
        clearDropHighlight()
        val source = dragView
        val wasDragging = dragging
        dragging = false
        hoverIndex = -1
        dragIndex = -1
        dragView = null
        if (!wasDragging) return
        if (!save) {
            source?.alpha = 1f
            return
        }
        val order = visibleApps.map { it.packageName }
        grid.post {
            if (!fragment.isAdded) return@post
            renderAppRows(grid)
            saveOrder(order)
        }
    }

    private fun launchApp(app: CarAppCatalog.App) {
        val ctx = fragment.context ?: fragment.activity?.applicationContext ?: return
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
        val components = OemSettingsIntents.candidates
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
                val done = p.waitForMillis(3000L)
                val out = if (done) p.inputStream.bufferedReader().use { it.readText() } else ""
                if (!done) p.destroy()
                done && out.contains("Starting")
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    // ---- 应用列表：隐藏 / 排序 / 添加 ----
    private fun hiddenSet(): Set<String> = DiPlayPreferences.homeAppHidden(fragment.requireContext())

    private fun isHidden(pkg: String): Boolean = pkg in hiddenSet()

    private fun setHidden(pkg: String, hidden: Boolean) {
        val set = hiddenSet().toMutableSet()
        if (hidden) set.add(pkg) else set.remove(pkg)
        DiPlayPreferences.saveHomeAppHidden(fragment.requireContext(), set)
    }

    /** 自定义排序优先（用户拖动保存的顺序），未排序的按原字母序跟在后面。 */
    private fun sortApps(apps: List<CarAppCatalog.App>): List<CarAppCatalog.App> {
        val order = DiPlayPreferences.homeAppOrder(fragment.requireContext())
        if (order.isEmpty()) return apps
        val index = order.withIndex().associate { (i, pkg) -> pkg to i }
        return apps.sortedBy { index[it.packageName] ?: Int.MAX_VALUE }
    }

    private fun saveOrder(pkgs: List<String>) {
        DiPlayPreferences.saveHomeAppOrder(fragment.requireContext(), pkgs)
    }

    /** 单击应用 → 应用菜单（启动 / 卸载）。不抢焦点，避免车机右侧系统导航栏常驻不消失。 */
    private fun showAppMenu(app: CarAppCatalog.App, anchor: View? = null) {
        val act = fragment.activity ?: return
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
        HomeAppMenu.track(popup)
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
            HomeAppMenu.clear(popup)
            ImmersiveBars.applyToWindow(act.window, force = true)
        }
    }

    /** 右上角"管理"→ 多选管理弹窗：勾选=显示、取消=隐藏，底部系统/非系统分类复选联动。 */
    private fun showManageDialog(ctx: android.content.Context) {
        Thread {
            val allApps = CarAppCatalog.enumerate(ctx, forceRefresh = true)
            fragment.view?.post {
                if (!fragment.isAdded) return@post
                DiPlayDialog.showManageApps(
                    ctx, allApps,
                    isShown = { app -> !isHidden(app.packageName) },
                    isSystem = { app -> CarAppCatalog.isSystemApp(ctx, app.packageName) },
                    // 勾选即时写 prefs 并重绘网格；旧逻辑只调 refresh() 不碰 appGrid，界面看起来「没更新」。
                    onSetShown = { app, shown -> setHidden(app.packageName, !shown); reloadAppGrid() },
                    onDismiss = { reloadAppGrid() },
                )
            }
        }.apply { isDaemon = true }.start()
    }

    private fun confirmUninstall(app: CarAppCatalog.App) {
        val ctx = fragment.requireContext()
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

    private fun label(ctx: android.content.Context, text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            this.textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    /** 列宽由权重算出，高度跟宽度，应用格是正方形。 */
    private class SquareTile(ctx: android.content.Context) : LinearLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val side = measuredWidth
            if (side > 0 && measuredHeight != side) {
                val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
                super.onMeasure(exact, exact)
            }
        }
    }
}
