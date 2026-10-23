// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

/**
 * 方向盘 / 旋钮焦点高亮统一工具（API 19，无 Compose）。
 *
 * 设计约束：
 * - **只开 [View.isFocusable]，不开 focusableInTouchMode**：触摸点击后不留常驻焦点，
 *   避免旋钮停在某个按钮上视觉一直亮。
 * - 背景全部代码生成，昼夜跟随 [GlassColors.isDark]（项目无 values-night 机制，
 *   浅/深由 GlassColors 在代码里切换，不新增 *_focused.xml 两套资源）。
 * - 焦点高亮是"失焦即还原"的背景替换，不改触摸按压逻辑（isPressed 仍由各
 *   OnTouchListener/selector 自行管理，两者状态可共存）。
 *
 * 用法：
 * - 无背景的整行（设置行 / 连接行）：[attachRow]。
 * - 已有固定背景的按钮（弹窗按钮 / 断开钮 / 管理钮）：[attachStrokeOverlay]，
 *   聚焦时在原背景上叠一圈青描边。
 * - 已有 pill/胶囊背景的可点控件：[swap] 传入普通/焦点两套 Drawable。
 */
object FocusSupport {

    /** 焦点描边色：青蓝主色。 */
    fun strokeColor(): Int = GlassColors.ACCENT

    /** 焦点填充色：青蓝柔光（与状态 pill 同档）。 */
    fun fillColor(): Int = GlassColors.ACCENT_SOFT_FILL

    /** 无背景行的焦点高亮底：青柔光填充 + 2dp 青描边。 */
    fun rowFocusDrawable(ctx: Context, cornerRadiusDp: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor())
            setStroke(maxOf(1, GlassColors.dp(ctx, 2)), strokeColor())
            cornerRadius = GlassColors.dp(ctx, cornerRadiusDp).toFloat()
        }

    /**
     * 给原本没有背景的整行 View 加焦点高亮：聚焦时套一层玻璃描边，失焦还原透明。
     * 注意：调用前该 View 必须是 clickable + focusable。
     */
    fun attachRow(view: View, cornerRadiusDp: Int = 10) {
        val focused = rowFocusDrawable(view.context, cornerRadiusDp)
        view.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) focused else null
        }
    }

    /**
     * 已有固定背景的按钮：聚焦时在原背景上叠一圈青描边（透明填充的 GradientDrawable 压在
     * 原背景之上），失焦还原。[cornerRadiusDp] 必须与原背景圆角对齐，否则描边溢出。
     */
    fun attachStrokeOverlay(view: View, cornerRadiusDp: Int = 10, strokeWidthDp: Int = 2) {
        val normal = view.background
        val overlay = GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(maxOf(1, GlassColors.dp(view.context, strokeWidthDp)), strokeColor())
            cornerRadius = GlassColors.dp(view.context, cornerRadiusDp).toFloat()
        }
        val wrapped = LayerDrawable(arrayOf(normal, overlay))
        view.background = normal
        view.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) wrapped else normal
        }
    }

    /**
     * 已有胶囊/按钮背景的控件：聚焦时整体换成 [focused]（同形更亮描边），失焦还原 [normal]。
     * 调用方负责构建两套 Drawable；本函数只负责挂载监听。
     */
    fun swap(view: View, normal: Drawable, focused: Drawable) {
        view.background = normal
        view.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) focused else normal
        }
    }
}
