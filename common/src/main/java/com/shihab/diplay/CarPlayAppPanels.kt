// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import com.shihab.diplay.ui.DiPlayDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.shihab.diplay.host.R
import kotlin.math.roundToInt

/**
 * App picker over CarPlay. Dense opaque panel (no blur) for KitKat / i.MX6 performance.
 */
object CarPlayAppPanels {

    /** Near-opaque blue-charcoal — matches 75Play glass cards without real blur. */
    private const val PANEL_FILL = 0xF2141C28.toInt()
    private const val PANEL_STROKE = 0x33A8D4FF.toInt()
    private const val SCRIM = 0xB3000000.toInt()
    private const val CLOSE_FILL = 0x28FFFFFF.toInt()

    fun buildAppsPopup(
        context: Context,
        apps: List<CarAppCatalog.App>,
        onLaunch: (CarAppCatalog.App) -> Unit,
        onClose: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val kitKat = Build.VERSION.SDK_INT < 21
        val columns = if (kitKat) 4 else 5
        val iconDp = if (kitKat) 52 else 60
        val iconPx = dp(iconDp)
        val padH = if (kitKat) 20 else 28
        val padV = if (kitKat) 16 else 22
        val corner = if (kitKat) 18 else 24

        val overlay = FrameLayout(context).apply {
            setBackgroundColor(SCRIM)
            isClickable = true
            setOnClickListener { onClose() }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(PANEL_FILL)
                cornerRadius = dp(corner).toFloat()
                setStroke(dp(1), PANEL_STROKE)
            }
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            isClickable = true
            setOnClickListener { }
        }

        // 标题行：左标题 + 右「关闭」文字胶囊（不再用手绘大圆 X）
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleCol.addView(TextView(context).apply {
            text = context.getString(R.string.app_launcher_title)
            setTextColor(0xFFF2F7FF.toInt())
            textSize = if (kitKat) 20f else 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(context).apply {
            text = context.getString(R.string.app_launcher_popup_hint)
            setTextColor(0x99B8C9DC.toInt())
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_launcher_close)
                setTextColor(0xFFEAF4FF.toInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(999).toFloat()
                    setColor(CLOSE_FILL)
                    setStroke(dp(1), 0x33FFFFFF)
                }
                setOnClickListener { onClose() }
                contentDescription = context.getString(R.string.app_launcher_close)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        panel.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        panel.addView(
            View(context).apply { setBackgroundColor(0x18FFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(14)
            },
        )

        if (apps.isEmpty()) {
            panel.addView(
                TextView(context).apply {
                    text = context.getString(R.string.app_launcher_no_apps)
                    setTextColor(0xCCB8C9DC.toInt())
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, dp(40))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        } else {
            val rows = (apps.size + columns - 1) / columns
            val rowH = dp(iconDp + 36)
            val gridH = (rows * rowH + (rows - 1) * dp(8)).coerceAtMost(
                (context.resources.displayMetrics.heightPixels * 0.62f).toInt(),
            )
            val grid = GridView(context).apply {
                numColumns = columns
                horizontalSpacing = dp(8)
                verticalSpacing = dp(8)
                isVerticalScrollBarEnabled = rows > 3
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                cacheColorHint = Color.TRANSPARENT
                if (kitKat) {
                    @Suppress("DEPRECATION")
                    isScrollingCacheEnabled = false
                }
                setSelector(android.R.color.transparent)
                adapter = object : BaseAdapter() {
                    override fun getCount() = apps.size
                    override fun getItem(position: Int) = apps[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val app = apps[position]
                        val tile = convertView ?: oemStyleTile(context, iconDp) { dp(it) }
                        tile.setOnClickListener { onLaunch(app) }
                        tile.setOnLongClickListener {
                            confirmUninstall(context, app)
                            true
                        }
                        (tile.getTag(R.id.launcher_tag_label) as? TextView)?.text = app.label
                        val iconView = tile.getTag(R.id.launcher_tag_icon) as? ImageView
                        iconView?.setImageDrawable(CarAppCatalog.icon(context, app, iconPx))
                        return tile
                    }
                }
            }
            panel.addView(
                grid,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gridH),
            )
        }

        val metrics = context.resources.displayMetrics
        val widthFrac = if (kitKat) 0.78f else 0.72f
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                (metrics.widthPixels * widthFrac).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        return overlay
    }

    /**
     * Order shown apps by [LauncherPrefs.shownOrder]. When the user has never edited the
     * list (order == null) every app is shown; once configured, only the saved packages are
     * shown, in their saved order.
     */
    fun shownApps(context: Context, apps: List<CarAppCatalog.App>): List<CarAppCatalog.App> {
        // 与主页应用列表共用同一份持久化（home_app_list）：先过滤隐藏，再按用户自定义顺序排序，
        // 保证 CarPlay 显示的和主页完全一致。
        val prefs = context.getSharedPreferences("home_app_list", Context.MODE_PRIVATE)
        val hidden = prefs.getString("hidden_packages", "")?.split(",").orEmpty()
            .filter { it.isNotBlank() }.toSet()
        val shown = apps.filterNot { it.packageName in hidden }
        val order = prefs.getString("app_order", "")?.split(",").orEmpty().filter { it.isNotBlank() }
        if (order.isEmpty()) return shown
        val index = order.withIndex().associate { (i, pkg) -> pkg to i }
        return shown.sortedBy { index[it.packageName] ?: Int.MAX_VALUE }
    }

    /** "Edit" screen for the launcher: check which apps to show + drag to reorder. */
    fun buildAppsManagePopup(
        context: Context,
        apps: List<CarAppCatalog.App>,
        onDone: () -> Unit,
        onCancel: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * density).roundToInt() }
        val kitKat = Build.VERSION.SDK_INT < 21
        val pad = if (kitKat) 16 else 28
        val corner = if (kitKat) 16 else 28
        val iconPx = dp(40)

        // Working copies: `ordered` keeps display order, `checked` the packages to show.
        val ordered = apps.toMutableList()
        val checked = LinkedHashSet<String>()
        val saved = LauncherPrefs.shownOrder(context)
        if (saved != null) {
            val byPackage = apps.associateBy { it.packageName }
            ordered.clear()
            ordered.addAll(saved.mapNotNull { byPackage[it] })
            ordered.addAll(apps.filter { it.packageName !in saved })
            checked.addAll(saved)
        } else {
            checked.addAll(apps.map { it.packageName })
        }

        val overlay = FrameLayout(context).apply {
            setBackgroundColor(SCRIM)
            isClickable = true
            setOnClickListener { onCancel() }
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(PANEL_FILL)
                cornerRadius = dp(corner).toFloat()
            }
            setPadding(dp(pad), dp(pad - 4), dp(pad), dp(pad))
            isClickable = true
            setOnClickListener { }
        }

        val header = FrameLayout(context)
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_launcher_edit_title)
                setTextColor(Color.WHITE)
                textSize = if (kitKat) 22f else 26f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START,
            ),
        )
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_launcher_done)
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(0x44FFFFFF)
                }
                setOnClickListener {
                    LauncherPrefs.saveOrder(
                        context,
                        ordered.filter { it.packageName in checked }.map { it.packageName },
                    )
                    onDone()
                }
            },
            FrameLayout.LayoutParams(dp(64), dp(40), Gravity.CENTER_VERTICAL or Gravity.END),
        )
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        panel.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_launcher_manage_hint)
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 13f
                gravity = Gravity.START
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) },
        )

        if (apps.isEmpty()) {
            panel.addView(
                TextView(context).apply {
                    text = context.getString(R.string.app_launcher_no_apps)
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 16f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        } else {
            val systemPackages = ordered
                .filter { CarAppCatalog.isSystemApp(context, it.packageName) }
                .map { it.packageName }
                .toSet()
            val userPackages = ordered.map { it.packageName }.filter { it !in systemPackages }.toSet()

            lateinit var list: ListView
            lateinit var adapter: BaseAdapter
            lateinit var selectAllBox: CheckBox
            lateinit var systemBox: CheckBox
            lateinit var userBox: CheckBox
            var syncingBulk = false
            var dragFrom = -1

            fun syncBulkBoxes() {
                syncingBulk = true
                selectAllBox.isChecked = ordered.isNotEmpty() && ordered.all { it.packageName in checked }
                systemBox.isChecked = systemPackages.isNotEmpty() && systemPackages.all { it in checked }
                userBox.isChecked = userPackages.isNotEmpty() && userPackages.all { it in checked }
                selectAllBox.isEnabled = ordered.isNotEmpty()
                systemBox.isEnabled = systemPackages.isNotEmpty()
                userBox.isEnabled = userPackages.isNotEmpty()
                syncingBulk = false
            }

            fun filterCheckBox(labelRes: Int): CheckBox = CheckBox(context).apply {
                text = context.getString(labelRes)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 0, dp(8), 0)
            }

            val bulkRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                selectAllBox = filterCheckBox(R.string.app_launcher_select_all)
                systemBox = filterCheckBox(R.string.app_launcher_system_apps)
                userBox = filterCheckBox(R.string.app_launcher_user_apps)
                addView(selectAllBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(systemBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(userBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            panel.addView(
                bulkRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(4) },
            )

            selectAllBox.setOnCheckedChangeListener { _, isChecked ->
                if (syncingBulk) return@setOnCheckedChangeListener
                if (isChecked) checked.addAll(ordered.map { it.packageName }) else checked.clear()
                adapter.notifyDataSetChanged()
                syncBulkBoxes()
            }
            systemBox.setOnCheckedChangeListener { _, isChecked ->
                if (syncingBulk) return@setOnCheckedChangeListener
                if (isChecked) checked.addAll(systemPackages) else checked.removeAll(systemPackages)
                adapter.notifyDataSetChanged()
                syncBulkBoxes()
            }
            userBox.setOnCheckedChangeListener { _, isChecked ->
                if (syncingBulk) return@setOnCheckedChangeListener
                if (isChecked) checked.addAll(userPackages) else checked.removeAll(userPackages)
                adapter.notifyDataSetChanged()
                syncBulkBoxes()
            }

            adapter = object : BaseAdapter() {
                override fun getCount() = ordered.size
                override fun getItem(position: Int) = ordered[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val app = ordered[position]
                    val row = convertView ?: buildManageRow(context, dp)
                    val handle = row.getTag(R.id.manage_tag_handle) as TextView
                    val check = row.getTag(R.id.manage_tag_check) as CheckBox
                    val icon = row.getTag(R.id.manage_tag_icon) as ImageView
                    val label = row.getTag(R.id.manage_tag_label) as TextView
                    row.alpha = if (position == dragFrom) 0.45f else 1f
                    // KitKat-safe drag: startDrag from the handle + ListView OnDragListener.
                    handle.setOnTouchListener { _, event ->
                        if (event.actionMasked != android.view.MotionEvent.ACTION_DOWN) return@setOnTouchListener false
                        dragFrom = position
                        @Suppress("DEPRECATION")
                        row.startDrag(
                            ClipData.newPlainText("app", app.packageName),
                            View.DragShadowBuilder(row),
                            position,
                            0,
                        )
                        adapter.notifyDataSetChanged()
                        true
                    }
                    check.setOnCheckedChangeListener(null)
                    check.isChecked = checked.contains(app.packageName)
                    check.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) checked.add(app.packageName) else checked.remove(app.packageName)
                        syncBulkBoxes()
                    }
                    icon.setImageDrawable(CarAppCatalog.icon(context, app, iconPx))
                    label.text = app.label
                    return row
                }
            }
            syncBulkBoxes()
            list = ListView(context).apply {
                divider = null
                cacheColorHint = Color.TRANSPARENT
                isVerticalScrollBarEnabled = true
                @Suppress("DEPRECATION")
                if (kitKat) isScrollingCacheEnabled = false
                this.adapter = adapter
                setOnDragListener { _, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_LOCATION -> {
                            val target = pointToPosition(event.x.toInt(), event.y.toInt())
                            if (dragFrom >= 0 &&
                                target != ListView.INVALID_POSITION &&
                                target != dragFrom &&
                                target in ordered.indices
                            ) {
                                val item = ordered.removeAt(dragFrom)
                                ordered.add(target, item)
                                dragFrom = target
                                adapter.notifyDataSetChanged()
                            }
                            val edge = dp(48)
                            when {
                                event.y < edge -> smoothScrollBy(-dp(16), 50)
                                height - event.y < edge -> smoothScrollBy(dp(16), 50)
                            }
                            true
                        }
                        DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED -> {
                            dragFrom = -1
                            adapter.notifyDataSetChanged()
                            true
                        }
                        DragEvent.ACTION_DRAG_STARTED -> true
                        else -> true
                    }
                }
            }
            panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val metrics = context.resources.displayMetrics
        val widthFrac = if (kitKat) 0.88f else 0.92f
        val heightFrac = if (kitKat) 0.82f else 0.88f
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                (metrics.widthPixels * widthFrac).toInt(),
                (metrics.heightPixels * heightFrac).toInt(),
                Gravity.CENTER,
            ),
        )
        return overlay
    }

    /** Manage row: [drag handle][checkbox][icon][label]; controls cached on the row via tags. */
    private fun buildManageRow(context: Context, dp: (Int) -> Int): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4), 0, dp(4))

        val handle = TextView(context).apply {
            text = "☰"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.app_launcher_drag_handle)
        }
        row.addView(handle, LinearLayout.LayoutParams(dp(44), dp(48)))
        val check = CheckBox(context).apply { isClickable = true }
        row.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) },
        )

        // Tags MUST be on the row — getView looks them up from convertView/row.
        row.setTag(R.id.manage_tag_handle, handle)
        row.setTag(R.id.manage_tag_check, check)
        row.setTag(R.id.manage_tag_icon, icon)
        row.setTag(R.id.manage_tag_label, label)
        return row
    }

    /** Long-press on an app tile: confirm, then hand off to the system uninstall screen. */
    private fun confirmUninstall(context: Context, app: CarAppCatalog.App) {
        runCatching {
            DiPlayDialog.showConfirm(
                context,
                context.getString(R.string.panel_uninstall_title, app.label),
                context.getString(R.string.panel_uninstall_message, app.label),
                context.getString(R.string.panel_uninstall), context.getString(R.string.cancel), danger = true,
                onConfirm = {
                    runCatching {
                        val delete = Intent(
                            Intent.ACTION_DELETE,
                            Uri.parse("package:${app.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(delete)
                    }
                },
            )
        }
    }

    private fun oemStyleTile(
        context: Context,
        iconDp: Int,
        dp: (Int) -> Int,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(4), dp(2), dp(2))
        setBackgroundColor(Color.TRANSPARENT)
        // 无底板、无描边：直接放圆角图标，避免再套一圈白边。
        val iconSize = dp(iconDp)
        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val labelView = TextView(context).apply {
            setTextColor(0xE6EAF4FF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        addView(
            labelView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setTag(R.id.launcher_tag_icon, iconView)
        setTag(R.id.launcher_tag_label, labelView)
    }
}
