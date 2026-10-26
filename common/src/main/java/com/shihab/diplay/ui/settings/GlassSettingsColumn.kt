package com.shihab.diplay.ui.settings

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.HudPanelDrawable
import com.shihab.diplay.ui.LayoutMetrics
import com.shihab.diplay.ui.SpindleDivider

/**
 * Shared glass column card for Setting / About. Wide screens use one lead column and a right stack.
 */
object GlassSettingsColumn {

    /** Single glass column; bottom padding avoids clipping the last cap row. */
    fun columnCard(ctx: Context, build: (LinearLayout) -> Unit): ScrollView {
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        build(card)
        card.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(1, LayoutMetrics.cardPadV(ctx))
        })
        return ScrollView(ctx).apply {
            background = HudPanelDrawable(ctx)
            val padH = LayoutMetrics.cardPadH(ctx)
            val padV = LayoutMetrics.cardPadV(ctx)
            setPadding(padH, padV, padH, padV)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(card)
        }
    }

    /**
     * 车机三列：标题钉在列顶，只有设置行滚动。
     * 720px 高的横屏上，列名不能跟着内容滚出视野。
     */
    fun titledColumn(ctx: Context, title: String, build: (LinearLayout) -> Unit): LinearLayout {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = HudPanelDrawable(ctx)
            val padH = LayoutMetrics.cardPadH(ctx)
            val padV = LayoutMetrics.cardPadV(ctx)
            setPadding(padH, padV, padH, 0)
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        GlassSettingsRows.cardTitle(header, title)
        outer.addView(header)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        build(body)
        body.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(1, LayoutMetrics.cardPadV(ctx))
        })
        outer.addView(ScrollView(ctx).apply {
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(body)
        }, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return outer
    }

    /**
     * 宽屏：左侧主面板占满高度，右侧按权重上下叠放。
     * 窄屏改成同一顺序的纵向三块。
     */
    fun stage(
        ctx: Context,
        lead: Pair<String, (LinearLayout) -> Unit>,
        stack: List<Pair<String, (LinearLayout) -> Unit>>,
        stackWeights: List<Float>,
    ): LinearLayout {
        // 1920×720 把三列铺满整宽。右侧再上下叠会把每列高度砍半，720px 里看不完。
        if (!LayoutMetrics.isWide(ctx) || LayoutMetrics.isCockpit(ctx)) {
            return columns(ctx, listOf(lead) + stack)
        }
        val gapPx = LayoutMetrics.colGap(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            titledColumn(ctx, lead.first, lead.second),
            LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1.2f),
        )
        val side = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        stack.forEachIndexed { index, (title, build) ->
            if (index > 0) {
                side.addView(SpindleDivider(ctx), LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, gapPx,
                ))
            }
            side.addView(
                titledColumn(ctx, title, build),
                LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    stackWeights.getOrElse(index) { 1f },
                ),
            )
        }
        root.addView(SpindleDivider(ctx).apply { vertical = true }, LinearLayout.LayoutParams(
            gapPx,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(
            side,
            LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        return root
    }

    /**
     * 窄屏（竖屏平板）上下叠放，每块自己滚动。
     */
    fun columns(ctx: Context, sections: List<Pair<String, (LinearLayout) -> Unit>>): LinearLayout {
        val wide = LayoutMetrics.isWide(ctx)
        val cols = LinearLayout(ctx).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val gapPx = LayoutMetrics.colGap(ctx)
        sections.forEachIndexed { index, (title, build) ->
            if (index > 0) {
                cols.addView(SpindleDivider(ctx).apply { vertical = wide }, if (wide) {
                    LinearLayout.LayoutParams(gapPx, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                } else {
                    LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, gapPx)
                })
            }
            val lp = if (wide) {
                LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            cols.addView(titledColumn(ctx, title, build), lp)
        }
        return cols
    }
}
