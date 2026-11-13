package com.shihab.diplay.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarAppCatalog
import com.shihab.diplay.host.GenericSwcAction
import com.shihab.diplay.host.GenericSwcCode
import com.shihab.diplay.host.GenericSwcLearn
import com.shihab.diplay.host.GenericSwcMap
import com.shihab.diplay.host.HeadUnitKeyEvents
import com.shihab.diplay.host.ImmersiveBars
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.theme.ShellMotion
import com.shihab.diplay.ui.theme.ShellShape
import com.shihab.diplay.ui.theme.ShellType
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tonal dialog toolkit for the DiPlay UI using API 19 View APIs only.
 * Window is always fullscreen + overlay; the filled panel is centered inside so the
 * head-unit side navigation bar is never uncovered by a partial dialog window.
 * Panel width follows [LayoutMetrics.DialogSize] (Material-aligned 320 / 448 / 560dp caps).
 */
object DiPlayDialog {

    private val openDialogs = ArrayList<java.lang.ref.WeakReference<Dialog>>()

    /** Back closes the top dialog instead of navigating the page underneath it. */
    fun consumeBack(): Boolean {
        if (com.shihab.diplay.host.GenericSwcLearn.disarm()) return true
        val dialog = openDialogs.mapNotNull { it.get() }.lastOrNull { it.isShowing } ?: return false
        dialog.cancel()
        return true
    }

    private fun track(dialog: Dialog) {
        openDialogs.add(java.lang.ref.WeakReference(dialog))
    }

    private fun themed(context: Context): Dialog {
        // Fullscreen translucent: partial Theme_Black windows leave gaps where ROM shows system nav.
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return c as? Activity
    }

    /** 弹窗最大高度统一对齐主内容区，宽度见 [LayoutMetrics.dialogWidthPx]。 */
    private fun panelMaxHeight(context: Context): Int = LayoutMetrics.dialogMaxHeightPx(context)

    /**
     * Dialog shell: fixed title (+ optional header extras) / scrollable [body] / optional footer.
     * Call [ready] after filling [body] (and footer) so the scroll region gets a capped height.
     */
    private class Shell(
        val panel: LinearLayout,
        val body: LinearLayout,
        private val scroll: ScrollView,
        private val maxPanelH: Int,
        private val innerW: Int,
    ) {
        fun addHeaderExtra(view: View) {
            // Insert after title (index 0), before the title divider (index 1).
            panel.addView(view, 1)
        }

        fun setFooter(footer: View) {
            panel.addView(divider(panel.context))
            panel.addView(
                footer,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        /**
         * Measure chrome + body; only the middle ScrollView scrolls.
         * @param fillMaxHeight true：正文区直接吃满弹层最大高度（如 WiFi 列表），不随内容变矮。
         */
        fun ready(fillMaxHeight: Boolean = false) {
            var chrome = panel.paddingTop + panel.paddingBottom
            for (i in 0 until panel.childCount) {
                val child = panel.getChildAt(i)
                if (child === scroll) continue
                val lp = child.layoutParams as? LinearLayout.LayoutParams
                child.measure(
                    View.MeasureSpec.makeMeasureSpec(innerW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                chrome += child.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            }
            body.measure(
                View.MeasureSpec.makeMeasureSpec(innerW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val minBody = GlassColors.dp(panel.context, 48)
            val maxBody = (maxPanelH - chrome).coerceAtLeast(minBody)
            val bodyH = if (fillMaxHeight) {
                maxBody
            } else {
                body.measuredHeight
                    .coerceAtMost(maxBody)
                    .coerceAtLeast(minBody.coerceAtMost(body.measuredHeight.coerceAtLeast(1)))
            }
            scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bodyH)
        }
    }

    private fun openShell(
        context: Context,
        titleText: String,
        size: LayoutMetrics.DialogSize = LayoutMetrics.DialogSize.STANDARD,
    ): Shell {
        val w = LayoutMetrics.dialogWidthPx(context, size)
        val maxH = panelMaxHeight(context)
        val pad = GlassColors.dp(context, 16)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.dialogBg())
            setPadding(pad, pad, pad, pad)
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        panel.addView(title(context, titleText))
        panel.addView(divider(context))
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GlassColors.dp(context, 48)))
        return Shell(panel, body, scroll, maxH, w - pad * 2)
    }

    private fun buttonRow(context: Context, vararg buttons: Button): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val btnH = LayoutMetrics.disconnectBtnH(context)
        // 三钮横排时间距略收，把宽度留给文字。
        val gap = GlassColors.dp(context, if (buttons.size >= 3) 8 else 12)
        buttons.forEachIndexed { index, btn ->
            if (index > 0) {
                row.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(gap, 1)
                })
            }
            row.addView(btn, LinearLayout.LayoutParams(0, btnH, 1f))
        }
        return row
    }

    private fun title(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(GlassColors.TEXT_PRIMARY)
            textSize = LayoutMetrics.cardTitleSize(context)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun body(context: Context, text: String, color: Int = GlassColors.TEXT_BODY): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = LayoutMetrics.homeBodyTextSize(context)
            setLineSpacing(0f, 1.2f)
            setPadding(0, GlassColors.dp(context, 12), 0, GlassColors.dp(context, 12))
        }

    private fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(GlassColors.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1,
        ).apply { topMargin = GlassColors.dp(context, 4); bottomMargin = GlassColors.dp(context, 4) }
    }

    private fun checkMarkDrawable(context: Context, on: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setCornerRadius(GlassColors.dp(context, ShellShape.EXTRA_SMALL).toFloat())
            if (on) {
                setColor(GlassColors.ACCENT)
                setStroke(0, GlassColors.TRANSPARENT)
            } else {
                setColor(GlassColors.CARD)
                setStroke(0, GlassColors.TRANSPARENT)
            }
        }

    private fun dialogButton(
        context: Context,
        text: String,
        bgRes: Int,
        textColor: Int,
        listener: () -> Unit,
    ): Button = Button(context).apply {
        this.text = text
        setTextColor(textColor)
        setBackgroundResource(bgRes)
        textSize = LayoutMetrics.ghostTextSize(context)
        // 系统 Button 默认 minWidth/padding 过大，在窄弹窗里会把文字挤成「不完整」。
        minimumWidth = 0
        minWidth = 0
        minHeight = LayoutMetrics.disconnectBtnH(context)
        minimumHeight = LayoutMetrics.disconnectBtnH(context)
        includeFontPadding = false
        setSingleLine(false)
        maxLines = 2
        gravity = Gravity.CENTER
        val ph = LayoutMetrics.ghostPadH(context)
        val pv = LayoutMetrics.ghostPadV(context)
        setPadding(ph, pv, ph, pv)
        setOnClickListener { listener() }
        // 方向盘确定键可触发；聚焦叠青描边（主/危险钮圆角 16，次级 12）。
        isFocusable = true
        FocusSupport.attachStrokeOverlay(this, cornerRadiusDp = ShellShape.SMALL)
    }

    /**
     * @param allowFocus true 仅输入弹窗需要（软键盘）；其余保持 NOT_FOCUSABLE，避免闪/露系统导航栏。
     */
    private fun show(dialog: Dialog, panel: View, allowFocus: Boolean = false) {
        val ctx = dialog.context
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(GlassColors.OVERLAY)
            isClickable = true
            setOnClickListener {
                // cancel() respects setCancelable(false); MATCH_PARENT 遮罩下系统的 outside-touch 不会触发。
                if (dialog.isShowing) runCatching { dialog.cancel() }
            }
        }
        // Absorb clicks on the panel so they do not dismiss through the scrim.
        panel.isClickable = true
        val plp = panel.layoutParams
        val w = when {
            plp != null && plp.width > 0 -> plp.width
            else -> ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val h = when {
            plp != null && plp.height > 0 -> plp.height
            else -> ViewGroup.LayoutParams.WRAP_CONTENT
        }
        root.addView(panel, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        mount(dialog, root, allowFocus)
    }

    /**
     * 向上滑或点「下一张」为 +1，向下滑或点「上一张」为 -1。
     * 位移不够、或横向位移更大时不切换。
     */
    internal fun verticalStep(dx: Float, dy: Float, slop: Int): Int? {
        val distance = kotlin.math.abs(dy)
        if (distance <= slop || distance <= kotlin.math.abs(dx)) return null
        return if (dy < 0f) 1 else -1
    }

    /** 全屏看一张图。返回关闭；确定把当前这张交给调用方，由调用方写入后再关闭。 */
    fun showImagePreview(
        context: Context,
        bitmap: Bitmap,
        onConfirm: (Bitmap) -> Unit,
        onStep: ((Int) -> Unit)? = null,
    ): WallpaperPreview {
        val dialog = themed(context)
        dialog.setCanceledOnTouchOutside(false)
        val image = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = context.getString(R.string.settings_wallpaper_thumb_cd)
        }
        val note = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(GlassColors.TEXT_PRIMARY)
            textSize = LayoutMetrics.ghostTextSize(context)
            setBackgroundResource(GlassColors.dialogBg())
            val inset = GlassColors.dp(context, 16)
            setPadding(inset, inset, inset, inset)
            visibility = View.GONE
        }
        val spinner = ProgressBar(context).apply { visibility = View.GONE }
        val back = dialogButton(
            context,
            context.getString(R.string.back),
            GlassColors.btnSecondary(),
            GlassColors.BTN_SECONDARY_TEXT,
        ) { dialog.cancel() }
        val confirm = dialogButton(
            context,
            context.getString(R.string.dialog_ok),
            GlassColors.btnPrimary(),
            GlassColors.NAV_SELECTED_TEXT,
        ) {}
        val previous = dialogButton(
            context,
            context.getString(R.string.settings_wallpaper_previous),
            GlassColors.btnSecondary(),
            GlassColors.BTN_SECONDARY_TEXT,
        ) {}
        val next = dialogButton(
            context,
            context.getString(R.string.settings_wallpaper_next),
            GlassColors.btnSecondary(),
            GlassColors.BTN_SECONDARY_TEXT,
        ) {}
        val preview = WallpaperPreview(dialog, image, note, spinner, back, confirm, previous, next, bitmap)
        confirm.setOnClickListener {
            if (!preview.acceptsInput()) return@setOnClickListener
            preview.lockForSave()
            onConfirm(preview.bitmap())
        }
        previous.setOnClickListener {
            if (previous.isEnabled && preview.acceptsInput()) onStep?.invoke(-1)
        }
        next.setOnClickListener {
            if (next.isEnabled && preview.acceptsInput()) onStep?.invoke(1)
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop * 3
        var downX = 0f
        var downY = 0f
        image.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val step = verticalStep(event.x - downX, event.y - downY, slop) ?: return@setOnTouchListener true
                    val allowed = if (step < 0) previous.isEnabled else next.isEnabled
                    if (allowed && preview.acceptsInput()) onStep?.invoke(step)
                }
            }
            true
        }
        val pad = GlassColors.dp(context, 16)
        val actions = buttonRow(context, back, confirm).apply { setPadding(pad, pad, pad, pad) }
        val gap = GlassColors.dp(context, 12)
        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(previous, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LayoutMetrics.disconnectBtnH(context)))
            addView(View(context), LinearLayout.LayoutParams(1, gap))
            addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LayoutMetrics.disconnectBtnH(context)))
        }
        val lift = LayoutMetrics.disconnectBtnH(context) + pad
        val root = FrameLayout(context).apply {
            setBackgroundColor(GlassColors.OVERLAY)
            isClickable = true
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(spinner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(
                side,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    marginEnd = pad
                    bottomMargin = lift
                },
            )
            addView(
                actions,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
            )
        }
        dialog.setOnDismissListener { preview.release() }
        mount(dialog, root, allowFocus = false)
        return preview
    }

    class WallpaperPreview internal constructor(
        val dialog: Dialog,
        private val image: ImageView,
        private val note: TextView,
        private val spinner: ProgressBar,
        private val back: Button,
        private val confirm: Button,
        private val previous: Button,
        private val next: Button,
        private var bitmap: Bitmap,
    ) {
        private val mainThread = Handler(Looper.getMainLooper())
        private var busy = false
        private var locked = false
        private var hasPrevious = false
        private var hasNext = false

        init { applyEnds() }

        fun bitmap(): Bitmap = bitmap

        fun acceptsInput(): Boolean = !busy && !locked

        fun replace(nextBitmap: Bitmap) {
            if (nextBitmap === bitmap) return
            val previousBitmap = bitmap
            bitmap = nextBitmap
            image.setImageBitmap(nextBitmap)
            note.visibility = View.GONE
            if (previousBitmap !== nextBitmap && !previousBitmap.isRecycled) recycleAfterDraw(previousBitmap, null)
        }

        /**
         * 卸下正在显示的大图，等这一帧画完再回收，然后才回调。
         * 车机硬件加速仍引用上一张时立刻 recycle 会崩；上一张还占着堆时再解下一张也会崩。
         */
        fun releaseDisplayed(onReleased: () -> Unit) {
            val previousBitmap = bitmap
            note.visibility = View.GONE
            image.setImageDrawable(null)
            recycleAfterDraw(previousBitmap, onReleased)
        }

        fun showNote(text: String) {
            note.text = text
            note.visibility = View.VISIBLE
        }

        fun setEnds(hasPrevious: Boolean, hasNext: Boolean) {
            this.hasPrevious = hasPrevious
            this.hasNext = hasNext
            if (acceptsInput()) applyEnds()
        }

        fun setBusy(value: Boolean) {
            busy = value
            spinner.visibility = if (value) View.VISIBLE else View.GONE
            if (locked) return
            if (value) {
                previous.isEnabled = false
                next.isEnabled = false
                confirm.isEnabled = false
            } else {
                confirm.isEnabled = true
                applyEnds()
            }
        }

        fun lockForSave() {
            locked = true
            busy = true
            spinner.visibility = View.VISIBLE
            back.isEnabled = false
            confirm.isEnabled = false
            previous.isEnabled = false
            next.isEnabled = false
            dialog.setCancelable(false)
        }

        fun unlock() {
            locked = false
            busy = false
            spinner.visibility = View.GONE
            back.isEnabled = true
            confirm.isEnabled = true
            dialog.setCancelable(true)
            applyEnds()
        }

        fun release() {
            val previousBitmap = bitmap
            image.setImageDrawable(null)
            recycleAfterDraw(previousBitmap, null)
        }

        private fun recycleAfterDraw(old: Bitmap, onReleased: (() -> Unit)?) {
            if (old.isRecycled) {
                onReleased?.invoke()
                return
            }
            val finish = Runnable {
                if (!old.isRecycled && !imageStillShows(old)) old.recycle()
                onReleased?.invoke()
            }
            if (!image.isAttachedToWindow) {
                mainThread.post(finish)
                return
            }
            // 动画回调在本帧绘制之前。再丢回主线程队列，回收就会落在这次绘制之后。
            image.postOnAnimation { mainThread.post(finish) }
        }

        private fun imageStillShows(candidate: Bitmap): Boolean {
            val current = image.drawable
            return current is BitmapDrawable && current.bitmap === candidate
        }

        private fun applyEnds() {
            previous.isEnabled = hasPrevious
            next.isEnabled = hasNext
        }
    }

    private fun mount(dialog: Dialog, content: View, allowFocus: Boolean) {
        val window = dialog.window ?: return
        // 无窗口动画：部分车机 ROM 在 enter 动画期间重新评估导航栏。
        window.setWindowAnimations(0)
        window.setBackgroundDrawable(ColorDrawable(GlassColors.TRANSPARENT))
        window.setDimAmount(0f)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        window.decorView.setBackgroundDrawable(ColorDrawable(GlassColors.TRANSPARENT))
        track(dialog)
        dialog.setContentView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        ImmersiveBars.showDialogGuarded(dialog, activityOf(dialog.context), allowFocus = allowFocus)
    }

    /** Confirmation (danger-capable). Buttons: cancel / confirm. */
    fun showConfirm(
        context: Context,
        title: String,
        message: String,
        confirm: String,
        cancel: String,
        danger: Boolean = false,
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null,
    ) {
        val d = themed(context)
        val shell = openShell(context, title)
        shell.body.addView(body(context, message))
        var applied = false
        d.setOnCancelListener { if (!applied) onCancel?.invoke() }
        val cancelBtn = dialogButton(context, cancel, GlassColors.btnSecondary(), GlassColors.BTN_SECONDARY_TEXT) { d.cancel() }
        val confirmBtn = dialogButton(
            context, confirm,
            if (danger) GlassColors.btnDanger() else GlassColors.btnPrimary(),
            if (danger) GlassColors.BTN_DANGER_TEXT else GlassColors.NAV_SELECTED_TEXT,
        ) { applied = true; d.dismiss(); onConfirm() }
        shell.setFooter(buttonRow(context, cancelBtn, confirmBtn))
        shell.ready()
        show(d, shell.panel)
    }

    /** Simple info box with one OK button. */
    fun showInfo(
        context: Context,
        title: String,
        message: String,
        ok: String? = null,
        accent: Int = GlassColors.TEXT_BODY,
        onOk: (() -> Unit)? = null,
    ) {
        val d = themed(context)
        val shell = openShell(context, title)
        shell.body.addView(body(context, message, accent))
        val okBtn = dialogButton(context, ok ?: context.getString(R.string.dialog_ok), GlassColors.btnPrimary(), GlassColors.NAV_SELECTED_TEXT) { d.dismiss(); onOk?.invoke() }
        shell.setFooter(okBtn)
        shell.ready()
        show(d, shell.panel)
    }

    /** Input dialog with real-time error highlight. */
    fun showInput(
        context: Context,
        title: String,
        hint: String,
        initial: String,
        assist: String? = null,
        save: String? = null,
        cancel: String? = null,
        validator: (String) -> String? = { null },
        onSave: (String) -> Unit,
    ) {
        val d = themed(context)
        val shell = openShell(context, title)
        val saveText = save ?: context.getString(R.string.save)
        val cancelText = cancel ?: context.getString(R.string.cancel)
        if (assist != null) shell.body.addView(body(context, assist, GlassColors.TEXT_MUTED))
        val input = EditText(context).apply {
            setText(initial)
            setHint(hint)
            setSingleLine(true)
            setTextColor(GlassColors.TEXT_PRIMARY)
            setHintTextColor(GlassColors.TEXT_MUTED)
            setBackgroundResource(GlassColors.inputBg())
            setPadding(GlassColors.dp(context, 14), GlassColors.dp(context, 12), GlassColors.dp(context, 14), GlassColors.dp(context, 12))
        }
        shell.body.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = GlassColors.dp(context, 8); bottomMargin = GlassColors.dp(context, 4) })
        val error = TextView(context).apply {
            setTextColor(GlassColors.WARNING)
            textSize = ShellType.CAPTION
            visibility = View.GONE
            setPadding(0, GlassColors.dp(context, 4), 0, 0)
        }
        shell.body.addView(error, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val cancelBtn = dialogButton(context, cancelText, GlassColors.btnSecondary(), GlassColors.BTN_SECONDARY_TEXT) { d.dismiss() }
        val saveBtn = dialogButton(context, saveText, GlassColors.btnPrimary(), GlassColors.NAV_SELECTED_TEXT) {
            val problem = validator(input.text.toString())
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
                input.setBackgroundResource(GlassColors.inputBg())
                input.requestFocus()
            } else {
                d.dismiss()
                onSave(input.text.toString())
            }
        }
        shell.setFooter(buttonRow(context, cancelBtn, saveBtn))
        shell.ready()
        // 输入框需要焦点才能弹出软键盘。
        show(d, shell.panel, allowFocus = true)
    }

    /** Single-choice list; title fixed, options scroll. */
    fun showSingleChoice(
        context: Context,
        title: String,
        options: List<String>,
        current: Int,
        onSelect: (Int) -> Unit,
    ) {
        val d = themed(context)
        val shell = openShell(context, title, LayoutMetrics.DialogSize.NARROW)
        val rowH = LayoutMetrics.rowMinH(context)
        options.forEachIndexed { index, option ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setMinimumHeight(rowH)
                setPadding(GlassColors.dp(context, 6), 0, GlassColors.dp(context, 6), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { d.dismiss(); onSelect(index) }
            }
            val label = TextView(context).apply {
                text = option
                setTextColor(if (index == current) GlassColors.ACCENT else GlassColors.TEXT_BODY)
                textSize = LayoutMetrics.settingsRowTextSize(context)
                setTypeface(typeface, if (index == current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (index == current) {
                row.addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(GlassColors.ACCENT)
                        setCornerRadius(GlassColors.dp(context, ShellShape.EXTRA_SMALL).toFloat())
                    }
                    layoutParams = LinearLayout.LayoutParams(GlassColors.dp(context, 4), GlassColors.dp(context, 16))
                })
            }
            shell.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        shell.ready()
        show(d, shell.panel)
    }

    /** Multi-choice (checkbox list) with save / cancel. */
    fun showMultiChoice(
        context: Context,
        title: String,
        options: List<String>,
        checked: Set<Int>,
        save: String? = null,
        cancel: String? = null,
        onSave: (Set<Int>) -> Unit,
    ) {
        val d = themed(context)
        val shell = openShell(context, title)
        val saveText = save ?: context.getString(R.string.save)
        val cancelText = cancel ?: context.getString(R.string.cancel)
        val state = checked.toMutableSet()
        options.forEachIndexed { index, option ->
            val cb = android.widget.CheckBox(context).apply {
                text = option
                isChecked = state.contains(index)
                setTextColor(GlassColors.TEXT_BODY)
                setMinimumHeight(LayoutMetrics.rowMinH(context))
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) state.add(index) else state.remove(index)
                }
            }
            shell.body.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val cancelBtn = dialogButton(context, cancelText, GlassColors.btnSecondary(), GlassColors.BTN_SECONDARY_TEXT) { d.dismiss() }
        val saveBtn = dialogButton(context, saveText, GlassColors.btnPrimary(), GlassColors.NAV_SELECTED_TEXT) { d.dismiss(); onSave(state) }
        shell.setFooter(buttonRow(context, cancelBtn, saveBtn))
        shell.ready()
        show(d, shell.panel)
    }

    /** Progress dialog with optional cancel. */
    fun showProgress(
        context: Context,
        title: String,
        description: String,
        cancellable: Boolean = false,
        onCancel: (() -> Unit)? = null,
    ): Dialog {
        val d = themed(context)
        val shell = openShell(context, title)
        shell.body.addView(body(context, description, GlassColors.TEXT_MUTED))
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        shell.body.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GlassColors.dp(context, 10)))
        if (cancellable) {
            val cancelBtn = dialogButton(context, context.getString(R.string.cancel), GlassColors.btnSecondary(), GlassColors.BTN_SECONDARY_TEXT) { d.dismiss(); onCancel?.invoke() }
            shell.setFooter(cancelBtn)
        }
        shell.ready()
        show(d, shell.panel)
        return d
    }

    /** Progress dialog whose description can be updated live from connection progress. */
    fun showProgressLive(
        context: Context,
        title: String,
        description: String,
        footer: String? = null,
        cancellable: Boolean = false,
        onCancel: (() -> Unit)? = null,
    ): ProgressDialogHandle {
        val d = themed(context)
        val shell = openShell(context, title)
        val spinner = FrameLayout(context)
        spinner.layoutParams = LinearLayout.LayoutParams(
            GlassColors.dp(context, 78), GlassColors.dp(context, 78),
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = GlassColors.dp(context, 8)
            bottomMargin = GlassColors.dp(context, 12)
        }
        spinner.addView(ProgressRing(context))
        spinner.addView(TextView(context).apply {
            text = "75Play"
            textSize = ShellType.LABEL
            setTextColor(GlassColors.ACCENT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        shell.body.addView(spinner)
        shell.body.addView(TextView(context).apply {
            this.text = description
            textSize = ShellType.LABEL
            setTextColor(GlassColors.TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, GlassColors.dp(context, 6), 0, 0)
        })
        if (footer != null) {
            shell.body.addView(TextView(context).apply {
                this.text = footer
                textSize = ShellType.CAPTION
                setTextColor(GlassColors.DIM)
                gravity = Gravity.CENTER
                setPadding(0, GlassColors.dp(context, 6), 0, 0)
            })
        }
        val stageView = TextView(context).apply {
            textSize = ShellType.TITLE
            setTextColor(GlassColors.ACCENT2)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, GlassColors.dp(context, 16), 0, 0)
        }
        shell.body.addView(stageView)
        val diagView = TextView(context).apply {
            textSize = ShellType.CAPTION
            typeface = Typeface.create(ShellType.MONO_FAMILY, Typeface.BOLD)
            setTextColor(GlassColors.WARNING)
            gravity = Gravity.CENTER
            setPadding(0, GlassColors.dp(context, 6), 0, GlassColors.dp(context, 8))
        }
        shell.body.addView(diagView)
        if (cancellable) {
            val cancelBtn = dialogButton(context, context.getString(R.string.cancel), GlassColors.btnSecondary(), GlassColors.BTN_SECONDARY_TEXT) { d.dismiss(); onCancel?.invoke() }
            shell.setFooter(cancelBtn)
        }
        shell.ready()
        show(d, shell.panel)
        return ProgressDialogHandle(d, stageView, diagView)
    }

    /** 管理应用：标题/底栏固定，仅应用列表滚动。 */
    fun showManageApps(
        context: Context,
        apps: List<CarAppCatalog.App>,
        isShown: (CarAppCatalog.App) -> Boolean,
        isSystem: (CarAppCatalog.App) -> Boolean,
        onSetShown: (CarAppCatalog.App, Boolean) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val d = themed(context)
        d.setOnDismissListener { onDismiss() }
        val shell = openShell(
            context, context.getString(R.string.dialog_manage_apps),
            LayoutMetrics.DialogSize.WIDE,
        )
        shell.addHeaderExtra(TextView(context).apply {
            text = context.getString(R.string.dialog_manage_apps_hint)
            setTextColor(GlassColors.TEXT_MUTED)
            textSize = ShellType.CAPTION
            setPadding(0, GlassColors.dp(context, 2), 0, GlassColors.dp(context, 4))
        })
        val state = mutableMapOf<String, Boolean>()
        apps.forEach { state[it.packageName] = isShown(it) }

        fun checkbox(on: Boolean): TextView = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = ShellType.CAPTION
            text = if (on) "✓" else ""
            setTextColor(if (on) GlassColors.NAV_SELECTED_TEXT else GlassColors.TRANSPARENT)
            background = checkMarkDrawable(context, on)
        }

        val iconSize = GlassColors.dp(context, 26)
        fun setItem(app: CarAppCatalog.App, shown: Boolean) {
            state[app.packageName] = shown
            onSetShown(app, shown)
        }
        var refreshCategoryMarks: () -> Unit = {}
        fun rebuildList() {
            shell.body.removeAllViews()
            apps.forEach { app ->
                val shown = state[app.packageName] ?: false
                val cb = checkbox(shown)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        GlassColors.dp(context, 4), GlassColors.dp(context, 6),
                        GlassColors.dp(context, 4), GlassColors.dp(context, 6),
                    )
                    setBackgroundResource(GlassColors.btnSecondary())
                    isClickable = true
                    isFocusable = true
                }
                row.addView(ImageView(context).apply {
                    setImageDrawable(CarAppCatalog.icon(context, app, iconSize))
                    setPadding(0, 0, GlassColors.dp(context, 10), 0)
                }, LinearLayout.LayoutParams(iconSize + GlassColors.dp(context, 10), iconSize))
                row.addView(TextView(context).apply {
                    text = app.label
                    setTextColor(GlassColors.TEXT_PRIMARY)
                    textSize = ShellType.LABEL
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(cb, LinearLayout.LayoutParams(GlassColors.dp(context, 20), GlassColors.dp(context, 20)))
                row.setOnClickListener {
                    setItem(app, !(state[app.packageName] ?: false))
                    rebuildList()
                    shell.ready()
                }
                row.minimumHeight = LayoutMetrics.rowMinH(context)
                shell.body.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = GlassColors.dp(context, 4) })
            }
            refreshCategoryMarks()
        }
        rebuildList()

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val categoryMarks = mutableListOf<Pair<TextView, () -> Boolean>>()
        fun paintMark(cb: TextView, on: Boolean) {
            cb.text = if (on) "✓" else ""
            cb.setTextColor(if (on) GlassColors.NAV_SELECTED_TEXT else GlassColors.TRANSPARENT)
            cb.background = checkMarkDrawable(context, on)
        }
        refreshCategoryMarks = {
            categoryMarks.forEach { (cb, on) -> paintMark(cb, on()) }
        }
        fun addCategoryToggle(catSystem: Boolean, name: String) {
            val catApps = apps.filter { isSystem(it) == catSystem }
            fun allOn() = catApps.isNotEmpty() && catApps.all { state[it.packageName] == true }
            val cb = checkbox(allOn())
            categoryMarks.add(cb to { allOn() })
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = LayoutMetrics.rowMinH(context)
                setPadding(GlassColors.dp(context, 2), 0, GlassColors.dp(context, 10), 0)
            }
            cell.addView(cb, LinearLayout.LayoutParams(GlassColors.dp(context, 20), GlassColors.dp(context, 20)))
            cell.addView(TextView(context).apply {
                text = name
                setTextColor(GlassColors.TEXT_BODY)
                textSize = ShellType.CAPTION
                setPadding(GlassColors.dp(context, 6), 0, 0, 0)
            })
            cell.setOnClickListener {
                if (catApps.isEmpty()) return@setOnClickListener
                val targetOn = !allOn()
                catApps.forEach { setItem(it, targetOn) }
                rebuildList()
                shell.ready()
            }
            bottom.addView(cell)
        }
        addCategoryToggle(true, context.getString(R.string.dialog_category_system))
        addCategoryToggle(false, context.getString(R.string.dialog_category_non_system))
        bottom.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val done = dialogButton(context, context.getString(R.string.done), GlassColors.btnPrimary(), GlassColors.NAV_SELECTED_TEXT) { d.dismiss() }
        bottom.addView(done, LinearLayout.LayoutParams(0, GlassColors.dp(context, 40), 1f))
        shell.setFooter(bottom)
        shell.ready()
        show(d, shell.panel)
    }

    /** Long read-only text (license, diagnostics snapshot) with close. */
    fun showLongText(context: Context, title: String, text: String, close: String? = null) {
        val d = themed(context)
        val shell = openShell(context, title)
        shell.body.addView(body(context, text, GlassColors.TEXT_BODY))
        val closeBtn = dialogButton(context, close ?: context.getString(R.string.close), GlassColors.btnPrimary(), GlassColors.NAV_SELECTED_TEXT) { d.dismiss() }
        shell.setFooter(closeBtn)
        shell.ready()
        show(d, shell.panel)
    }

    /**
     * Custom view dialog (e.g. key-assignment grid). Optional single confirm button.
     * @param allowFocus 正文含输入框时必须为 true，否则窗口保持 NOT_FOCUSABLE，点击无法获得焦点。
     */
    fun showCustom(
        context: Context,
        title: String,
        view: View,
        okText: String? = null,
        onOk: (() -> Unit)? = null,
        fillMaxHeight: Boolean = false,
        allowFocus: Boolean = false,
    ): Dialog {
        val d = themed(context)
        val shell = openShell(context, title)
        shell.body.addView(view)
        if (okText != null) {
            val okBtn = dialogButton(context, okText, GlassColors.btnPrimary(), GlassColors.NAV_SELECTED_TEXT) { d.dismiss(); onOk?.invoke() }
            shell.setFooter(okBtn)
        }
        shell.ready(fillMaxHeight = fillMaxHeight)
        show(d, shell.panel, allowFocus = allowFocus)
        return d
    }

    /**
     * Multi-action choices (e.g. saved-report: view / share / done).
     * Buttons sit in a fixed horizontal footer so long path text can scroll without clipping actions.
     */
    fun showChoices(
        context: Context,
        title: String,
        message: String? = null,
        buttons: List<Pair<String, () -> Unit>>,
        header: View? = null,
    ): Dialog {
        val d = themed(context)
        val shell = openShell(context, title)
        if (header != null) shell.body.addView(header)
        if (message != null) {
            shell.body.addView(body(context, message).apply {
                if (LayoutMetrics.isShort(context)) textSize = ShellType.LABEL
            })
        }
        val btns = buttons.mapIndexed { index, (text, action) ->
            val primary = index == buttons.lastIndex
            dialogButton(
                context, text,
                if (primary) GlassColors.btnPrimary() else GlassColors.btnSecondary(),
                if (primary) GlassColors.NAV_SELECTED_TEXT else GlassColors.BTN_SECONDARY_TEXT,
            ) { d.dismiss(); action() }.also {
                // STANDARD 宽约 448dp：三钮均分后略缩小字号，避免中文被截断。
                if (buttons.size >= 3) it.textSize = if (LayoutMetrics.isShort(context)) 13f else 14f
            }
        }
        shell.setFooter(buttonRow(context, *btns.toTypedArray()))
        shell.ready()
        show(d, shell.panel)
        return d
    }

    /** In-app toast. Long messages belong in [showInfo]. */
    fun toast(context: Context, message: String, long: Boolean = false) {
        val tv = TextView(context).apply {
            text = message
            setTextColor(GlassColors.TEXT_PRIMARY)
            textSize = ShellType.LABEL
            setLineSpacing(0f, 1.15f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setBackgroundResource(GlassColors.toastBg())
            val h = GlassColors.dp(context, 20)
            val v = GlassColors.dp(context, 14)
            setPadding(h, v, h, v)
            // 限制宽度，避免长句贴满屏宽、左右无呼吸感
            maxWidth = GlassColors.dp(context, 360)
        }
        val toast = android.widget.Toast.makeText(
            context.applicationContext, message,
            if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
        )
        toast.view = tv
        toast.setGravity(Gravity.CENTER, 0, 0)
        toast.show()
        ImmersiveBars.pulse(activityOf(context))
    }

    /** Learn Android key codes or this car's wheel names. The switch that uses them lives in settings. */
    fun showSwcLearn(context: Context) {
        val d = themed(context)
        val shell = openShell(
            context,
            context.getString(R.string.settings_generic_swc_title),
            LayoutMetrics.DialogSize.WIDE,
        )
        val hint = context.getString(R.string.settings_generic_swc_hint)
        val status = body(context, hint, GlassColors.TEXT_MUTED)
        shell.body.addView(status)
        var bindings = AirPlayPersistence.loadGenericSwcBindings(context).toMutableMap()
        val valueViews = mutableMapOf<GenericSwcAction, TextView>()
        val titleViews = mutableMapOf<GenericSwcAction, TextView>()
        fun paint() {
            for (action in GenericSwcAction.values()) {
                val value = valueViews[action] ?: continue
                val title = titleViews[action] ?: continue
                val code = bindings[action]
                value.text = code?.label() ?: context.getString(R.string.settings_generic_swc_unbound)
                val armed = GenericSwcLearn.armedAction() == action
                title.setTextColor(if (armed) GlassColors.ACCENT else GlassColors.TEXT_BODY)
                value.setTextColor(if (code == null) GlassColors.TEXT_MUTED else GlassColors.TEXT_PRIMARY)
            }
        }
        GenericSwcLearn.setOnDisarm {
            status.text = hint
            paint()
        }
        for (action in GenericSwcAction.values()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pv = GlassColors.dp(context, 8)
                setPadding(0, pv, 0, pv)
                isClickable = true
                isLongClickable = true
            }
            val title = TextView(context).apply {
                text = action.label(context)
                textSize = LayoutMetrics.settingsRowTextSize(context)
                setTextColor(GlassColors.TEXT_BODY)
            }
            val value = TextView(context).apply {
                textSize = LayoutMetrics.settingsRowTextSize(context)
                setTextColor(GlassColors.TEXT_MUTED)
            }
            titleViews[action] = title
            valueViews[action] = value
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(value)
            row.setOnClickListener {
                status.text = context.getString(R.string.settings_generic_swc_press, action.label(context))
                GenericSwcLearn.arm(action) { code ->
                    bindings = GenericSwcMap.assign(bindings, action, code).toMutableMap()
                    AirPlayPersistence.saveGenericSwcBindings(context, bindings)
                    GenericSwcLearn.disarm()
                }
                paint()
            }
            row.setOnLongClickListener {
                bindings.remove(action)
                AirPlayPersistence.saveGenericSwcBindings(context, bindings)
                GenericSwcLearn.disarm()
                true
            }
            shell.body.addView(row)
        }
        paint()
        val close = dialogButton(
            context,
            context.getString(R.string.dialog_ok),
            GlassColors.btnPrimary(),
            GlassColors.NAV_SELECTED_TEXT,
        ) { d.dismiss() }
        shell.setFooter(close)
        shell.ready()
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP && GenericSwcLearn.disarm()) return@setOnKeyListener true
                if (GenericSwcLearn.isArmed()) return@setOnKeyListener true
                return@setOnKeyListener false
            }
            GenericSwcLearn.onKey(event)
        }
        val learnReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val press = HeadUnitKeyEvents.parse(intent) ?: return
                if (!press.pressed) return
                if (GenericSwcLearn.onName(press.token)) {
                    // The learn sink saved the name and disarmed. Refresh is via setOnDisarm.
                }
            }
        }
        val learnFilter = IntentFilter().apply {
            HeadUnitKeyEvents.learnActions().forEach { addAction(it) }
        }
        val learnContext = context.applicationContext
        runCatching { learnContext.registerReceiver(learnReceiver, learnFilter) }
        d.setOnDismissListener {
            runCatching { learnContext.unregisterReceiver(learnReceiver) }
            GenericSwcLearn.close()
        }
        show(d, shell.panel, allowFocus = true)
    }
}

/** Handle for a progress dialog whose stage text + per-stage timer can be updated live. */
class ProgressDialogHandle internal constructor(
    private val dialog: Dialog,
    private val stageView: android.widget.TextView,
    private val diagView: android.widget.TextView,
) {
    val dialogRef: Dialog get() = dialog
    private val handler = Handler(Looper.getMainLooper())
    private var stageStartAt = SystemClock.elapsedRealtime()
    private val diagRunnable = object : Runnable {
        override fun run() {
            val s = ((SystemClock.elapsedRealtime() - stageStartAt) / 1000).toInt()
            runCatching { diagView.text = dialog.context.getString(R.string.dialog_stage_elapsed, s) }
            handler.postDelayed(this, 500)
        }
    }

    init { handler.postDelayed(diagRunnable, 0) }

    /** 更新当前连接阶段并重置阶段计时。 */
    fun updateDescription(text: String) {
        runCatching {
            stageView.text = text
            stageStartAt = SystemClock.elapsedRealtime()
        }
    }

    fun dismiss() {
        runCatching {
            handler.removeCallbacks(diagRunnable)
            if (dialog.isShowing) dialog.dismiss()
        }
    }
}

/** 旋转进度环：圆环描边 + 顶部强调色弧，无限旋转（对齐设计稿 .oc-spinner）。 */
class ProgressRing(context: Context) : View(context) {
    private val stroke = GlassColors.dp(context, 4).toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = GlassColors.DIVIDER
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = GlassColors.ACCENT
    }

    private var animator: ObjectAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ObjectAnimator.ofFloat(this, "rotation", 0f, 360f).apply {
            duration = ShellMotion.MODAL * 2
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val r = (width / 2f) - stroke
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r, track)
        // API 19 只有 RectF 版的 drawArc；float 版（left,top,right,bottom,...）自 API 21 才提供，
        // 车机(API19)会抛 NoSuchMethodError，因此必须用 RectF 重载。
        val oval = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, -90f, 270f, false, arc)
    }
}
