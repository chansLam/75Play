package com.shihab.diplay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shihab.diplay.host.ImmersiveBars
import com.shihab.diplay.host.R

/**
 * Dark-glass toast for the UI refactor. Left accent bar encodes status:
 * success = green, warning = orange, info = blue.
 */
object DiPlayToast {
    fun show(context: Context, message: String, type: Type = Type.INFO) {
        val toast = Toast(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(GlassColors.dialogBg())
            setPadding(
                LayoutMetrics.ghostPadH(context),
                LayoutMetrics.ghostPadV(context),
                LayoutMetrics.ghostPadH(context),
                LayoutMetrics.ghostPadV(context),
            )
        }
        val bar = View(context).apply {
            setBackgroundColor(type.color)
            layoutParams = LinearLayout.LayoutParams(
                GlassColors.dp(context, 4),
                LayoutMetrics.rowMinH(context) / 2,
            )
        }
        val text = TextView(context).apply {
            this.text = message
            setTextColor(GlassColors.TEXT_PRIMARY)
            textSize = LayoutMetrics.homeBodyTextSize(context)
            setPadding(LayoutMetrics.ghostPadH(context), 0, 0, 0)
        }
        row.addView(bar)
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toast.view = row
        toast.duration = Toast.LENGTH_SHORT
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, GlassColors.dp(context, 96))
        toast.show()
        // Toast 是独立窗口，部分车机会顺带露出系统导航栏；补压宿主沉浸式。
        ImmersiveBars.pulse(activityOf(context))
    }

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return c as? Activity
    }

    enum class Type(val color: Int) {
        INFO(GlassColors.ACCENT),
        SUCCESS(GlassColors.SUCCESS),
        WARNING(GlassColors.ALERT),
        ERROR(GlassColors.WARNING),
    }
}
