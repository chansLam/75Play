// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Drag float above the app grid. List order stays in HomeAppGrid. */
internal class HomeAppDragFloat {
    private var popup: PopupWindow? = null
    private var width = 0
    private var height = 0

    /**
     * 用一份独立的图标和标题做浮层，不把正在显示的格子 draw 进位图。
     * 硬件加速下直接 draw 会弄坏渲染节点，下一帧遍历子 View 时崩溃。
     */
    fun show(source: View, rawX: Float, rawY: Float, liftPx: Int) {
        dismiss()
        if (source !is ViewGroup) return
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return
        val ctx = source.context
        val floatTile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(GlassColors.appTileBg())
            setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, source.paddingBottom)
        }
        for (i in 0 until source.childCount) {
            when (val child = source.getChildAt(i)) {
                is ImageView -> {
                    val copy = child.drawable?.constantState?.newDrawable()?.mutate()
                    floatTile.addView(ImageView(ctx).apply {
                        setImageDrawable(copy)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(child.width.coerceAtLeast(1), child.height.coerceAtLeast(1)))
                }
                is TextView -> {
                    floatTile.addView(TextView(ctx).apply {
                        text = child.text
                        setTextColor(child.currentTextColor)
                        textSize = child.textSize / ctx.resources.displayMetrics.scaledDensity
                        typeface = child.typeface
                        gravity = Gravity.CENTER_HORIZONTAL
                        maxLines = 1
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                }
            }
        }
        val window = PopupWindow(floatTile, w, h, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false
            isTouchable = false
            isFocusable = false
        }
        width = w
        height = h
        this.popup = window
        val (x, y) = floatXY(rawX, rawY, liftPx)
        val token = source.rootView
        if (token.windowToken != null) {
            runCatching { window.showAtLocation(token, Gravity.NO_GRAVITY, x, y) }
        }
        source.alpha = 0.22f
    }

    fun move(rawX: Float, rawY: Float, liftPx: Int) {
        val window = this.popup ?: return
        val (x, y) = floatXY(rawX, rawY, liftPx)
        window.update(x, y, width, height)
    }

    /** 图标中心略高于指尖，避免手指挡住。 */
    private fun floatXY(rawX: Float, rawY: Float, liftPx: Int): Pair<Int, Int> {
        val x = (rawX - width / 2f).toInt()
        val y = (rawY - height / 2f - liftPx).toInt()
        return x to y
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
        width = 0
        height = 0
    }

}
