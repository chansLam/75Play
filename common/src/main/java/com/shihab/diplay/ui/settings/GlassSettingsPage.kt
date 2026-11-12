package com.shihab.diplay.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.HudPanelDrawable
import com.shihab.diplay.ui.LayoutMetrics
import com.shihab.diplay.ui.SpindleDivider
import com.shihab.diplay.ui.theme.ShellSpacing

/** Full-width settings workbench: every section stays visible without a secondary navigation rail. */
object GlassSettingsPage {
    data class Section(
        val title: String,
        val build: (LinearLayout) -> Unit,
    )

    fun create(ctx: Context, sections: List<Section>): View {
        require(sections.isNotEmpty())
        val wide = LayoutMetrics.isWide(ctx)
        val gap = LayoutMetrics.colGap(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = HudPanelDrawable(ctx)
            val padH = LayoutMetrics.cardPadH(ctx)
            val padV = LayoutMetrics.cardPadV(ctx)
            setPadding(padH, padV, padH, padV)
        }

        fun section(section: Section): View {
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            column.addView(TextView(ctx).apply {
                text = section.title
                textSize = LayoutMetrics.cardTitleSize(ctx)
                setTextColor(GlassColors.TEXT_PRIMARY)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            column.addView(GlassSettingsRows.titleTick(ctx))

            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                section.build(this)
                addView(View(ctx), LinearLayout.LayoutParams(1, LayoutMetrics.cardPadV(ctx)))
            }
            if (wide) {
                column.addView(ScrollView(ctx).apply {
                    clipToPadding = false
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    isFillViewport = true
                    addView(body)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply { topMargin = LayoutMetrics.cardTitleGap(ctx) })
            } else {
                column.addView(body, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = LayoutMetrics.cardTitleGap(ctx) })
            }
            return column
        }

        if (wide) {
            sections.forEachIndexed { index, item ->
                if (index > 0) {
                    root.addView(SpindleDivider(ctx).apply { vertical = true }, LinearLayout.LayoutParams(
                        gap,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ))
                }
                root.addView(section(item), LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f,
                ))
            }
        } else {
            val stack = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            sections.forEachIndexed { index, item ->
                if (index > 0) {
                    stack.addView(SpindleDivider(ctx), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        GlassColors.dp(ctx, ShellSpacing.LARGE),
                    ).apply {
                        topMargin = GlassColors.dp(ctx, ShellSpacing.LARGE)
                        bottomMargin = GlassColors.dp(ctx, ShellSpacing.LARGE)
                    })
                }
                stack.addView(section(item), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            root.addView(ScrollView(ctx).apply {
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(stack)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
        return root
    }
}
