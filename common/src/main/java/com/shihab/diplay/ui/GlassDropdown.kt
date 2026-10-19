package com.shihab.diplay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.shihab.diplay.host.ImmersiveBars

/**
 * Compact glass dropdown (PopupWindow) for 1920×720 head units.
 *
 * - Anchored tightly to the trigger (left-prefer, right-align if clipped).
 * - Flips above the anchor when there is not enough room below.
 * - Non-focusable so opening does not flash the system navigation bar.
 */
object GlassDropdown {

    fun show(
        anchor: View,
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
        syncAnchorText: Boolean = true,
        /** Appended when syncing the anchor label (cap pills use " ▾"). */
        anchorSuffix: String = " ▾",
    ) {
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        lateinit var popup: PopupWindow
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        labels.forEachIndexed { i, lab ->
            val opt = TextView(ctx).apply {
                text = lab
                textSize = LayoutMetrics.settingsCapTextSize(ctx)
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(if (i == selected) GlassColors.ACCENT else GlassColors.TEXT_BODY)
                if (i == selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = true
                minimumHeight = LayoutMetrics.rowMinH(ctx)
                val pv = LayoutMetrics.capPadV(ctx)
                setPadding(LayoutMetrics.capPadH(ctx), pv, LayoutMetrics.capPadH(ctx), pv)
                setOnClickListener {
                    popup.dismiss()
                    if (syncAnchorText) {
                        (anchor as? TextView)?.text = lab + anchorSuffix
                    }
                    if (i != selected) onSelect(i)
                }
            }
            col.addView(opt)
            if (i < labels.lastIndex) {
                col.addView(View(ctx).apply {
                    setBackgroundColor(GlassColors.DIVIDER)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                })
            }
        }
        val scroller = ScrollView(ctx).apply {
            setBackgroundResource(GlassColors.dialogBg())
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val dm = ctx.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val margin = dp(6)
        // 尽量贴着触发控件，避免悬在半空。
        val gap = dp(2)
        val longest = labels.maxOfOrNull { it.length } ?: 0
        // 与设置页 cap 下拉同档：按文案定宽，不跟整行拉伸。
        val widthDp = when {
            longest > 28 -> 260
            longest > 18 -> 220
            else -> 200
        }
        val preferred = dp(widthDp)
        // 仅当触发控件是紧凑 cap（不太宽）时略跟宽，避免比按钮还窄。
        val popupW = if (anchor.width in 1 until preferred) {
            preferred
        } else if (anchor.width in preferred..(preferred + dp(40))) {
            anchor.width
        } else {
            preferred
        }.coerceAtMost(screenW - margin * 2).coerceAtLeast(dp(160))

        scroller.measure(
            View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val contentH = scroller.measuredHeight.coerceAtLeast(1)

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorLeft = loc[0]
        val anchorTop = loc[1]
        val anchorRight = anchorLeft + anchor.width
        val anchorBottom = anchorTop + anchor.height
        val spaceBelow = (screenH - anchorBottom - margin).coerceAtLeast(0)
        val spaceAbove = (anchorTop - margin).coerceAtLeast(0)
        val showAbove = when {
            contentH <= spaceBelow -> false
            contentH <= spaceAbove -> true
            else -> spaceAbove > spaceBelow
        }
        val avail = if (showAbove) spaceAbove else spaceBelow
        val popupH = contentH
            .coerceAtMost(avail)
            .coerceAtMost((screenH * 0.5f).toInt())
            .coerceAtLeast(dp(48))

        // focusable=false：不抢 Activity 焦点，避免车机侧边系统导航栏闪一下。
        popup = PopupWindow(scroller, popupW, popupH, /* focusable = */ false)
        popup.isOutsideTouchable = true
        popup.isTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        scroller.systemUiVisibility = ImmersiveBars.visibilityFlags()

        // 水平：优先左对齐触发控件；右侧不够则右对齐控件右缘；再夹进屏内。
        var x = anchorLeft
        if (x + popupW > screenW - margin) {
            x = anchorRight - popupW
        }
        if (x < margin) x = margin
        if (x + popupW > screenW - margin) x = screenW - margin - popupW

        val y = if (showAbove) {
            (anchorTop - popupH - gap).coerceAtLeast(margin)
        } else {
            (anchorBottom + gap).coerceAtMost(screenH - margin - popupH)
        }

        val act = activityOf(ctx)
        ImmersiveBars.applyToWindow(act?.window, force = true)

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        ImmersiveBars.keepHiddenWhile(act) { popup.isShowing }
        popup.setOnDismissListener {
            ImmersiveBars.applyToWindow(act?.window, force = true)
        }
    }

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return c as? Activity
    }
}
