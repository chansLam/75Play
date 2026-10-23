package com.shihab.diplay.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayHost
import com.shihab.diplay.ui.FocusSupport
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.GlassSwitch
import com.shihab.diplay.ui.LayoutMetrics

/**
 * Shared glass settings row/cap helpers for shell Fragments (Setting / About).
 * Keeps visual language aligned with [GlassColors], [LayoutMetrics], [GlassSwitch].
 */
object GlassSettingsRows {

    fun dp(context: Context, value: Int): Int = GlassColors.dp(context, value)

    fun label(ctx: Context, text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            this.textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun cardTitle(card: LinearLayout, title: String) {
        val ctx = card.context
        card.addView(label(ctx, title, LayoutMetrics.cardTitleSize(ctx), GlassColors.TEXT_PRIMARY, true))
        card.addView(titleTick(ctx))
        card.addView(space(ctx, LayoutMetrics.cardTitleGap(ctx)))
    }

    /** 栏目标题下的短青条：圆角渐变，不用直角色块。 */
    fun titleTick(ctx: Context): View {
        val density = ctx.resources.displayMetrics.density
        return View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(GlassColors.ACCENT_LIFT, GlassColors.ACCENT_DEEP),
            ).apply {
                cornerRadius = density * 2f
            }
            layoutParams = LinearLayout.LayoutParams(
                (density * 36).toInt(),
                (density * 3).toInt(),
            ).apply { topMargin = dp(ctx, if (LayoutMetrics.isCockpit(ctx)) 4 else 8) }
        }
    }

    /** [hPx] is already pixels (e.g. [LayoutMetrics.cardTitleGap]). */
    fun space(ctx: Context, hPx: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, hPx) }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(GlassColors.DIVIDER)
        val g = LayoutMetrics.dividerGap(ctx)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = g
            bottomMargin = g
        }
    }

    fun settingRow(ctx: Context, title: String, right: View): LinearLayout {
        val short = LayoutMetrics.isShort(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pv = LayoutMetrics.rowPadV(ctx)
            setPadding(0, pv, 0, pv)
            minimumHeight = LayoutMetrics.rowMinH(ctx)
        }
        row.addView(
            label(ctx, title, LayoutMetrics.settingsRowTextSize(ctx), GlassColors.TEXT_BODY).apply {
                includeFontPadding = false
                maxLines = if (LayoutMetrics.isCockpit(ctx) || !short) 2 else 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(right)
        // 车机：点标题等同点右侧控件，命中区是整行而不是右侧小胶囊。
        if (right.isClickable) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { right.performClick() }
            // 行级单一焦点目标：右侧胶囊不单独抢焦点（行 performClick 仍能打开下拉）。
            right.isFocusable = false
            FocusSupport.attachRow(row)
        }
        return row
    }

    /** Clickable value label `.pick`: cap style + ▾, opens a dropdown. */
    fun capPick(ctx: Context, text: String, click: (TextView) -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text + " ▾"
            textSize = LayoutMetrics.settingsCapTextSize(ctx)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GlassColors.CAP_TEXT)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // Keep font padding so CJK baselines are not clipped (e.g. safe-area「全屏」).
            includeFontPadding = true
            minimumHeight = LayoutMetrics.capMinH(ctx)
            val ph = LayoutMetrics.capPadH(ctx)
            val pv = LayoutMetrics.capPadV(ctx)
            setPadding(ph, pv, ph, pv)
            isClickable = true
            isFocusable = true
            setOnClickListener { click(this) }
            FocusSupport.swap(this, pillDrawable(ctx, focused = false), pillDrawable(ctx, focused = true))
        }

    /** Static value label `.cap` (not clickable). [arrow] appends ▾ for dropdown affordance. */
    fun capVal(ctx: Context, text: String, color: Int = GlassColors.CAP_TEXT, arrow: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text + if (arrow) " ▾" else ""
            textSize = LayoutMetrics.settingsCapTextSize(ctx)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = true
            minimumHeight = LayoutMetrics.capMinH(ctx)
            val ph = LayoutMetrics.capPadH(ctx)
            val pv = LayoutMetrics.capPadV(ctx)
            setPadding(ph, pv, ph, pv)
            background = capFill(ctx, GlassColors.ACCENT_SOFT_STROKE, 1)
        }

    /** Secondary `.ghostbtn` at head-unit scale. 圆角 10dp。 */
    fun ghostButton(ctx: Context, text: String, cornerRadiusDp: Int = 10, click: () -> Unit): View =
        TextView(ctx).apply {
            this.text = text
            textSize = LayoutMetrics.ghostTextSize(ctx)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GlassColors.INK)
            gravity = Gravity.CENTER
            // 单行。KitKat 上多层背景会让短中文按字折行。
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = true
            minimumHeight = LayoutMetrics.capMinH(ctx)
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
            FocusSupport.swap(
                this,
                GlassColors.ghostDrawable(ctx, cornerRadiusDp),
                GlassColors.ghostDrawable(ctx, cornerRadiusDp, GlassColors.ACCENT),
            )
            setPadding(
                LayoutMetrics.ghostPadH(ctx),
                LayoutMetrics.ghostPadV(ctx),
                LayoutMetrics.ghostPadH(ctx),
                LayoutMetrics.ghostPadV(ctx),
            )
        }

    fun glassSwitchRow(ctx: Context, title: String, value: Boolean, save: (Boolean) -> Unit): View =
        glassSwitchRow(ctx, title, value) { _, on -> save(on) }

    fun glassSwitchRow(
        ctx: Context,
        title: String,
        value: Boolean,
        save: (GlassSwitch, Boolean) -> Unit,
    ): View {
        val short = LayoutMetrics.isShort(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pv = LayoutMetrics.rowPadV(ctx)
            setPadding(0, pv, 0, pv)
            minimumHeight = LayoutMetrics.rowMinH(ctx)
        }
        row.addView(
            label(ctx, title, LayoutMetrics.settingsRowTextSize(ctx), GlassColors.TEXT_BODY).apply {
                includeFontPadding = false
                maxLines = if (LayoutMetrics.isCockpit(ctx) || !short) 2 else 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, dp(ctx, if (short) 6 else 8), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val sw = GlassSwitch(ctx, compact = false).apply {
            setChecked(value, animate = false)
            onCheckedChangeListener = { switch, on -> save(switch, on) }
        }
        row.addView(sw)
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { sw.performClick() }
        // 开关不单独占焦点：整行是唯一焦点目标。
        sw.isFocusable = false
        FocusSupport.attachRow(row)
        return row
    }

    /**
     * Switch that requires reconnect: Cancel restores the previous visual state without saving.
     */
    fun reconnectSwitchRow(
        ctx: Context,
        host: DiPlayHost,
        title: String,
        value: Boolean,
        onConfirm: (Boolean) -> Unit,
    ): View = glassSwitchRow(ctx, title, value) { sw, on ->
        confirmReconnect(
            host,
            ctx,
            onCancel = { revertSwitch(sw, !on) },
        ) { onConfirm(on) }
    }

    /** Confirm dialog with the shared [R.string.reconnect_required_message] copy. */
    fun confirmReconnect(
        host: DiPlayHost,
        ctx: Context,
        onCancel: (() -> Unit)? = null,
        onApply: () -> Unit,
    ) {
        host.confirmReconnectChange(
            ctx.getString(R.string.reconnect_required_message),
            onCancel = onCancel,
            onApply = onApply,
        )
    }

    fun revertSwitch(sw: GlassSwitch, value: Boolean) {
        val listener = sw.onCheckedChangeListener
        sw.onCheckedChangeListener = null
        sw.setChecked(value, animate = true)
        sw.onCheckedChangeListener = listener
    }

    /** 连接页/设置行通用的整行容器：横排、垂直居中、统一行高与上下内边距。 */
    fun rowWrap(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutMetrics.rowMinH(ctx)
            val pv = LayoutMetrics.rowPadV(ctx)
            setPadding(0, pv, 0, pv)
        }

    /** 胶囊底：上沿更亮，普通态 1dp 柔光描边；焦点态 2dp 青描边。 */
    private fun pillDrawable(ctx: Context, focused: Boolean): GradientDrawable =
        capFill(ctx, if (focused) GlassColors.ACCENT else GlassColors.ACCENT_SOFT_STROKE, if (focused) 2 else 1)

    private fun capFill(ctx: Context, stroke: Int, strokeDp: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(GlassColors.ACCENT_SOFT_FILL, GlassColors.ACCENT_CAP_FILL),
        ).apply {
            setStroke(dp(ctx, strokeDp), stroke)
            cornerRadius = dp(ctx, 10).toFloat()
        }
}
