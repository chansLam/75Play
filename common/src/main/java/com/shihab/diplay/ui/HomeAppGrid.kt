package com.shihab.diplay.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
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
    private var appGrid: AppFlow? = null
    private var appScroll: ScrollView? = null
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
    private var longPressArmed = false
    private val longPressRunnable = object : Runnable {
        override fun run() {
            val v = dragView ?: return
            longPressArmed = true
            v.parent?.requestDisallowInterceptTouchEvent(true)
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun dp(v: Int) = GlassColors.dp(fragment.requireContext(), v)

    fun buildAppListCard(ctx: android.content.Context): LinearLayout {
        val icon = dp(LayoutMetrics.appIconDp(ctx))
        val inset = icon / 2 + icon / 4
        val card = object : LinearLayout(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val widthLimit = boundedSize(widthMeasureSpec, appFrameMaxWidth(ctx))
                val heightFallback = if (LayoutMetrics.isWide(ctx)) Int.MAX_VALUE / 4 else LayoutMetrics.homeAppsStripH(ctx)
                val heightLimit = boundedSize(heightMeasureSpec, heightFallback)
                val innerW = (widthLimit - paddingLeft - paddingRight).coerceAtLeast(0)
                val header = if (childCount > 0) getChildAt(0) else null
                val tick = if (childCount > 1) getChildAt(1) else null
                val scroll = appScroll
                val flow = appGrid
                val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                val innerWidthSpec = MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.AT_MOST)
                header?.measure(innerWidthSpec, unspecified)
                flow?.measure(innerWidthSpec, unspecified)
                val flowW = (flow?.measuredWidth ?: 0).coerceAtLeast(1)
                val contentW = maxOf(header?.measuredWidth ?: 0, flowW)
                val tickH = (tick?.layoutParams?.height ?: 0).coerceAtLeast(0)
                tick?.measure(
                    MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(tickH, MeasureSpec.EXACTLY),
                )
                val width = (contentW + paddingLeft + paddingRight).coerceAtMost(widthLimit)
                var chrome = paddingTop + paddingBottom
                chrome += verticalMargins(header) + (header?.measuredHeight ?: 0)
                chrome += verticalMargins(tick) + (tick?.measuredHeight ?: 0)
                chrome += verticalMargins(scroll)
                val flowH = flow?.measuredHeight ?: 0
                val wanted = chrome + flowH
                val height = wanted.coerceAtMost(heightLimit)
                val scrollH = if (wanted > height) (height - chrome).coerceAtLeast(0) else flowH
                scroll?.measure(
                    MeasureSpec.makeMeasureSpec(flowW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(scrollH, MeasureSpec.EXACTLY),
                )
                setMeasuredDimension(width.coerceAtLeast(suggestedMinimumWidth), height.coerceAtLeast(suggestedMinimumHeight))
            }

            private fun verticalMargins(child: View?): Int {
                val lp = child?.layoutParams as? MarginLayoutParams ?: return 0
                return lp.topMargin + lp.bottomMargin
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = HudPanelDrawable(ctx)
            setPadding(inset, inset, inset, inset)
        }
        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headRow.addView(label(ctx, ctx.getString(R.string.home_head_unit_apps), LayoutMetrics.cardTitleSize(ctx), GlassColors.TEXT_PRIMARY, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            includeFontPadding = true
            setPadding(LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx), LayoutMetrics.ghostPadH(ctx), LayoutMetrics.ghostPadV(ctx))
            setOnClickListener { showManageDialog(ctx) }
        }
        FocusSupport.attachStrokeOverlay(manageBtn, cornerRadiusDp = 16)
        headRow.addView(manageBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8) })
        card.addView(headRow)
        val tick = GlassSettingsRows.titleTick(ctx)
        (tick.layoutParams as? LinearLayout.LayoutParams)?.width = ViewGroup.LayoutParams.MATCH_PARENT
        card.addView(tick)
        val grid = AppFlow(ctx)
        appGrid = grid
        grid.addView(label(ctx, ctx.getString(R.string.home_loading), LayoutMetrics.homeMutedTextSize(ctx), GlassColors.TEXT_BODY).apply {
            setPadding(0, dp(8), 0, 0)
        })
        val scroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = false
            addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        appScroll = scroll
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = LayoutMetrics.cardTitleGap(ctx) })
        reloadAppGrid()
        return card
    }

    /** 宽屏时一行最宽停在原来的应用栏比例上，装不下就换行，应用少就再收。 */
    private fun appFrameMaxWidth(ctx: android.content.Context): Int {
        val screen = ctx.resources.displayMetrics.widthPixels
        if (!LayoutMetrics.isWide(ctx)) return screen
        val apps = LayoutMetrics.homeAppsWeight(ctx)
        val connect = LayoutMetrics.homeConnectWeight(ctx)
        return (screen * apps / (apps + connect)).toInt().coerceAtLeast(dp(120))
    }

    private fun boundedSize(spec: Int, fallback: Int): Int {
        val mode = View.MeasureSpec.getMode(spec)
        val size = View.MeasureSpec.getSize(spec)
        return if (mode == View.MeasureSpec.UNSPECIFIED) fallback else size.coerceAtMost(fallback)
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

    /**
     * 系统应用安装 / 卸载 / 更新后由 [FragmentHome] 调用：先失效枚举缓存（否则旧列表会
     * 被 CACHE_TTL 继续命中），再重新枚举并重绘网格。
     */
    fun refreshAfterPackageChange() {
        CarAppCatalog.invalidate()
        reloadAppGrid()
    }

    private fun renderAppGrid(grid: ViewGroup, apps: List<CarAppCatalog.App>) {
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

    private fun renderAppRows(grid: ViewGroup) {
        grid.removeAllViews()
        val ctx = grid.context
        visibleApps.forEach { app ->
            grid.addView(buildAppTile(ctx, app, grid), tileLp(ctx))
        }
    }

    private fun tileLp(ctx: android.content.Context): ViewGroup.MarginLayoutParams {
        val m = LayoutMetrics.appTileMargin(ctx)
        return ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(m, m, m, m) }
    }

    private fun buildAppTile(ctx: android.content.Context, app: CarAppCatalog.App, grid: ViewGroup): View {
        val iconDp = LayoutMetrics.appIconDp(ctx)
        val iconPx = dp(iconDp)
        val tile = LinearLayout(ctx).apply {
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
            // 触摸在 OnTouchListener 里打开；这里补方向盘确定键（DPAD_CENTER/ENTER → performClick）。
            setOnClickListener { launchApp(app) }
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
            maxWidth = iconPx * 2
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
        return tile
    }

    /** 应用格：点按打开；按住不动松手确认卸载；按住后拖动排序。滑动交给外层 ScrollView。 */
    private fun handleTileTapAndDrag(v: View, e: MotionEvent, app: CarAppCatalog.App, grid: ViewGroup): Boolean {
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
                longPressArmed = false
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
                    if (longPressArmed && moved) {
                        dragging = true
                        hoverIndex = dragIndex
                        v.isPressed = false
                        startDragFloat(v)
                    } else if (moved) {
                        // 未长按就移动：这是滑动。取消长按，且不要 disallow，让 ScrollView 能拦截。
                        v.isPressed = false
                        longPressArmed = false
                        handler.removeCallbacks(longPressRunnable)
                        return true
                    } else {
                        return true
                    }
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
                val armed = longPressArmed
                longPressArmed = false
                val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                val moved = Math.abs(e.rawX - downX) > slop || Math.abs(e.rawY - downY) > slop
                val changed = wasDragging && placeDraggedApp(app)
                endDrag(grid, save = changed)
                // ScrollView 抢走手势时走 CANCEL。这里只认几乎没移动的抬手。
                if (!wasDragging && !moved) {
                    if (armed) confirmUninstall(app) else launchApp(app)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                longPressArmed = false
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

    private fun highlightDrop(grid: ViewGroup, target: Int) {
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
    private fun indexUnderFinger(grid: ViewGroup, rawX: Float, rawY: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        val count = minOf(grid.childCount, visibleApps.size)
        for (index in 0 until count) {
            val cell = grid.getChildAt(index)
            if (cell.width <= 0 || cell.height <= 0) continue
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
        return best
    }

    private fun cellAt(grid: ViewGroup, index: Int): View? {
        if (index !in visibleApps.indices || index >= grid.childCount) return null
        return grid.getChildAt(index)
    }

    private fun endDrag(grid: ViewGroup, save: Boolean) {
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

    /**
     * 等距网格：固定列数（[LayoutMetrics.appColumns]），列宽均分可用宽度，行高取所有格子的统一高度；
     * 每格内部由 tile 自己把图标与文字水平垂直居中。行列严格对齐，不再按内容宽度流式换行。
     */
    private class AppFlow(ctx: android.content.Context) : ViewGroup(ctx) {
        private var columns = LayoutMetrics.appColumns(ctx)
        /** 一格（含左右 margin）的外宽；列步长。 */
        private var stride = 0
        /** tile 自身宽（已扣左右 margin）。 */
        private var cellW = 0
        /** 一行（含上下 margin）的统一外高。 */
        private var rowH = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val m = LayoutMetrics.appTileMargin(context)
            columns = LayoutMetrics.appColumns(context)
            // 网格宽度：有可用宽度就吃满它，列均分才整齐；未约束时按列数估一个舒适宽。
            val gridW = when (widthMode) {
                MeasureSpec.EXACTLY -> widthSize
                MeasureSpec.AT_MOST -> widthSize.coerceAtMost(Int.MAX_VALUE / 4)
                else -> {
                    val icon = LayoutMetrics.appIconDp(context)
                    (columns * (icon + LayoutMetrics.dp(context, 20) + 2 * m)).coerceAtMost(Int.MAX_VALUE / 4)
                }
            }
            stride = gridW / columns
            cellW = (stride - 2 * m).coerceAtLeast(0)
            val wSpec = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
            // 第一遍：高度不约束，量出每格自然高，取最大值作为统一行高。
            var maxRow = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                measureChild(child, wSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val lp = child.layoutParams as MarginLayoutParams
                maxRow = maxOf(maxRow, child.measuredHeight + lp.topMargin + lp.bottomMargin)
            }
            rowH = maxRow
            // 第二遍：把每格高撑到统一行高，格子底与文字基线逐行对齐。
            val hSpec = MeasureSpec.makeMeasureSpec((rowH - 2 * m).coerceAtLeast(0), MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                measureChild(child, wSpec, hSpec)
            }
            val rows = if (childCount == 0) 0 else (childCount + columns - 1) / columns
            val wantH = rows * rowH
            val measuredW = when (widthMode) {
                MeasureSpec.EXACTLY -> widthSize
                else -> gridW
            }
            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
            val measuredH = when (heightMode) {
                MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
                MeasureSpec.AT_MOST -> wantH.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
                else -> wantH
            }
            setMeasuredDimension(measuredW, measuredH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            if (columns <= 0 || rowH <= 0 || stride <= 0) return
            val m = LayoutMetrics.appTileMargin(context)
            var index = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                val col = index % columns
                val row = index / columns
                val left = col * stride + m
                val top = row * rowH + m
                child.layout(left, top, left + cellW, top + rowH - 2 * m)
                index++
            }
        }

        override fun generateLayoutParams(attrs: android.util.AttributeSet?) = MarginLayoutParams(context, attrs)
        override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        override fun generateLayoutParams(p: LayoutParams) = MarginLayoutParams(p)
        override fun checkLayoutParams(p: LayoutParams) = p is MarginLayoutParams
    }
}
