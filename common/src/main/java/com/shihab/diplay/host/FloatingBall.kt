package com.shihab.diplay.host

import android.content.Context
import com.shihab.diplay.DiPlayActivity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.abs
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.LayoutMetrics
import com.shihab.diplay.ui.theme.ShellComponentSize
import com.shihab.diplay.ui.theme.ShellShape
import com.shihab.diplay.ui.theme.ShellSpacing
import com.shihab.diplay.ui.theme.ShellStroke
import com.shihab.diplay.util.AppLog

/**
 * A small floating ball shown while the DiPlay app is in front AND while CarPlay is in
 * front. A tap performs a system Back action through the assist-back accessibility service
 * (guides the user there when it is disabled); a long-press returns to the DiPlay shell.
 * Drag to move it. Releasing it on a screen edge tucks it away and leaves an arrow; tapping
 * that arrow brings the ball back. Needs install-time SYSTEM_ALERT_WINDOW; uses TYPE_PHONE
 * (API 19).
 */
internal object FloatingBall {
    const val TAG = "DiPlay-FloatingBall"

    private const val PREFS = "diplay_floating_ball"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_EDGE = "edge"
    private const val KEY_RAIL_PARK = "rail_park_v1"
    private const val BALL_SIZE_DP = 56
    private const val DOCK_THICKNESS_DP = 18
    private const val DOCK_LENGTH_DP = 42

    private var root: View? = null
    private var onTapAction: (() -> Unit)? = null
    private var onLongPressAction: (() -> Unit)? = null

    @Suppress("UNUSED_PARAMETER")
    fun permitted(context: Context): Boolean = true

    val shown: Boolean get() = root != null

    /**
     * Adds the floating ball. [onTap] performs a Back action, [onLongPress] returns to the
     * DiPlay shell. Main thread.
     */
    fun show(context: Context, onTap: () -> Unit, onLongPress: () -> Unit): Boolean {
        // Actions can change when the front activity changes (e.g. tap=Back while CarPlay is
        // in front); update them even when the ball is already shown.
        onTapAction = onTap
        onLongPressAction = onLongPress
        if (root != null) return true
        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val metrics = context.resources.displayMetrics
        val size = (BALL_SIZE_DP * metrics.density).toInt().coerceAtLeast(1)
        val dockThickness = (DOCK_THICKNESS_DP * metrics.density).toInt().coerceAtLeast(1)
        val dockLength = (DOCK_LENGTH_DP * metrics.density).toInt().coerceAtLeast(dockThickness)
        // resources.displayMetrics stops at the system bars. The ball is clamped to the
        // physical display so a drag can sit flush with every edge.
        val real = android.util.DisplayMetrics()
        runCatching {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(real)
        }
        val (screenWidth, screenHeight) = floatingBallScreen(
            real.widthPixels, real.heightPixels, metrics.widthPixels, metrics.heightPixels,
        )
        val maxX = floatingBallLimit(screenWidth, size)
        val maxY = floatingBallLimit(screenHeight, size)
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // TOP|START so x/y are measured from the top-left and the ball can be dragged
            // anywhere on screen (TOP|END previously confined it to the right half).
            // FLAG_LAYOUT_IN_SCREEN keeps that origin on the physical screen; without it
            // TYPE_PHONE is inset below the status bar.
            gravity = Gravity.TOP or Gravity.START
            title = "DiPlay floating ball"
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val margin = (8 * metrics.density).toInt()
        val railX = margin.coerceIn(0, maxX)
        val railY = (maxY - margin).coerceAtLeast(0)
        val savedX = prefs.getInt(KEY_X, -1)
        val savedY = prefs.getInt(KEY_Y, -1)
        val parked = prefs.getBoolean(KEY_RAIL_PARK, false)
        val coveringColumn = !parked && savedX > maxX - size * 2 &&
            savedY in 0 until (screenHeight * 0.5f).toInt()
        var edge = FloatingBallEdge.from(prefs.getString(KEY_EDGE, null))
        var ballX: Int
        var ballY: Int
        if (savedX < 0 || coveringColumn) {
            ballX = railX
            ballY = railY
            edge = null
            prefs.edit().putBoolean(KEY_RAIL_PARK, true).putInt(KEY_X, ballX).putInt(KEY_Y, ballY)
                .remove(KEY_EDGE).apply()
        } else {
            ballX = savedX.coerceIn(0, maxX)
            ballY = savedY.coerceIn(0, maxY)
            when (edge) {
                FloatingBallEdge.LEFT -> ballX = 0
                FloatingBallEdge.RIGHT -> ballX = maxX
                FloatingBallEdge.TOP -> ballY = 0
                FloatingBallEdge.BOTTOM -> ballY = maxY
                null -> Unit
            }
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = ballX
        var startY = ballY
        var startEdge = edge
        var downAt = 0L
        var dragging = false
        var pulledOut = false
        var paintedEdge = edge
        var paintedWidth = -1
        var paintedHeight = -1
        lateinit var ballView: FrameLayout
        fun place(view: View, update: Boolean) {
            val frame = floatingBallFrame(
                edge, ballX, ballY, screenWidth, screenHeight, size, dockThickness, dockLength,
            )
            params.x = frame.x
            params.y = frame.y
            params.width = frame.width
            params.height = frame.height
            if (frame.width != paintedWidth || frame.height != paintedHeight || edge != paintedEdge) {
                paintedWidth = frame.width
                paintedHeight = frame.height
                paintedEdge = edge
                val docked = edge
                view.background = if (docked == null) {
                    expandedBackground(context, frame.width, frame.height)
                } else {
                    dockBackground(context, docked, frame.width, frame.height)
                }
                view.contentDescription = context.getString(
                    if (docked == null) R.string.cd_floating_ball else R.string.cd_floating_ball_docked,
                )
            }
            if (update) runCatching { windowManager.updateViewLayout(view, params) }
        }
        fun save() {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_X, ballX)
                .putInt(KEY_Y, ballY)
            val docked = edge
            if (docked == null) editor.remove(KEY_EDGE) else editor.putString(KEY_EDGE, docked.name)
            editor.apply()
        }
        ballView = FrameLayout(context).apply {
            place(this, update = false)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = ballX
                        startY = ballY
                        startEdge = edge
                        downAt = SystemClock.uptimeMillis()
                        dragging = false
                        pulledOut = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dragging || abs(dx) > slop || abs(dy) > slop) {
                            dragging = true
                            val dock = startEdge
                            if (dock != null && !pulledOut) {
                                val inward = floatingBallInward(dock, dx, dy)
                                if (inward > slop) {
                                    pulledOut = true
                                    edge = null
                                    ballX = (startX + dx).toInt().coerceIn(0, maxX)
                                    ballY = (startY + dy).toInt().coerceIn(0, maxY)
                                } else if (dock == FloatingBallEdge.TOP || dock == FloatingBallEdge.BOTTOM) {
                                    ballX = (startX + dx).toInt().coerceIn(0, maxX)
                                } else {
                                    ballY = (startY + dy).toInt().coerceIn(0, maxY)
                                }
                            } else {
                                edge = null
                                ballX = (startX + dx).toInt().coerceIn(0, maxX)
                                ballY = (startY + dy).toInt().coerceIn(0, maxY)
                            }
                            place(ballView, update = true)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (event.actionMasked == MotionEvent.ACTION_CANCEL && !dragging) {
                            return@setOnTouchListener true
                        }
                        if (dragging) {
                            if (edge == null) {
                                edge = floatingBallDock(
                                    ballX, ballY, maxX, maxY, ballX - startX, ballY - startY,
                                )
                            }
                            save()
                            place(ballView, update = true)
                        } else if (edge != null) {
                            edge = null
                            save()
                            place(ballView, update = true)
                        } else {
                            val held = SystemClock.uptimeMillis() - downAt
                            if (held >= ViewConfiguration.getLongPressTimeout()) {
                                onLongPressAction?.invoke()
                            } else {
                                onTapAction?.invoke()
                            }
                        }
                    }
                }
                true
            }
        }
        return try {
            windowManager.addView(ballView, params)
            root = ballView
            AppLog.i(TAG, "ball shown ${params.width}x${params.height} at ${params.x},${params.y} edge=$edge")
            true
        } catch (error: RuntimeException) {
            AppLog.w(TAG, "ball failed", error)
            false
        }
    }

    /** Removes the floating ball. Main thread. */
    fun hide() {
        val view = root ?: return
        root = null
        // P1 leak兜底：onTap/onLongPress lambda 捕获 DiPlayActivity 引用，hide 后必须清空，
        // 否则单例 FloatingBall 持有 Activity 直到下次 show() 覆盖。
        onTapAction = null
        onLongPressAction = null
        @Suppress("DEPRECATION")
        val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { windowManager?.removeViewImmediate(view) }
        AppLog.i(TAG, "ball hidden")
    }

    /** Returns to the DiPlay home screen from the CarPlay screen (application context).
     * Carries a "returnFromCarPlay" flag + timestamp so DiPlayActivity.onResume does not
     * immediately re-enter the active CarPlay session (which made the home screen flash back
     * to CarPlay). */
    fun backToDiPlay(context: Context) {
        runCatching {
            com.shihab.diplay.DiPlayActivity.returnedFromCarPlayAt = System.currentTimeMillis()
            val intent = Intent(context, DiPlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra("returnFromCarPlay", true)
            }
            context.startActivity(intent)
        }
    }

    /** Floating ball tap: performs a global Back action through the assist-back accessibility
     * service. When the service is disabled, guides the user to system accessibility settings. */
    fun back() {
        if (AssistBackService.performBack()) return
        val context = root?.context ?: return
        runCatching {
            com.shihab.diplay.ui.DiPlayDialog.toast(
                context, context.getString(R.string.floating_ball_assist_toast), true)
        }
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppLog.w(TAG, "open accessibility settings failed", it) }
    }
}

internal enum class FloatingBallEdge {
    LEFT, RIGHT, TOP, BOTTOM;

    companion object {
        fun from(name: String?): FloatingBallEdge? = entries.firstOrNull { it.name == name }
    }
}

internal data class FloatingBallFrame(val x: Int, val y: Int, val width: Int, val height: Int)

/** Physical pixels for the floating ball. A positive real size wins over the inset window metrics. */
internal fun floatingBallScreen(
    realWidth: Int,
    realHeight: Int,
    fallbackWidth: Int,
    fallbackHeight: Int,
): Pair<Int, Int> {
    val width = if (realWidth > 0) realWidth else fallbackWidth.coerceAtLeast(0)
    val height = if (realHeight > 0) realHeight else fallbackHeight.coerceAtLeast(0)
    return width to height
}

/** Furthest top-left that keeps the ball fully on screen and flush with the far edge. */
internal fun floatingBallLimit(screenPx: Int, ballPx: Int): Int =
    (screenPx - ballPx.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * Edge the ball should tuck into after a drag. Only a release that is actually flush with an
 * edge docks, so the inset resting spot stays expanded. A corner follows the stronger drag.
 */
internal fun floatingBallDock(
    x: Int,
    y: Int,
    maxX: Int,
    maxY: Int,
    dragX: Int,
    dragY: Int,
): FloatingBallEdge? {
    val horizontal = when {
        x <= 0 && maxX > 0 && x >= maxX -> if (dragX < 0) FloatingBallEdge.LEFT else FloatingBallEdge.RIGHT
        x <= 0 -> FloatingBallEdge.LEFT
        maxX > 0 && x >= maxX -> FloatingBallEdge.RIGHT
        else -> null
    }
    val vertical = when {
        y <= 0 && maxY > 0 && y >= maxY -> if (dragY < 0) FloatingBallEdge.TOP else FloatingBallEdge.BOTTOM
        y <= 0 -> FloatingBallEdge.TOP
        maxY > 0 && y >= maxY -> FloatingBallEdge.BOTTOM
        else -> null
    }
    return when {
        horizontal != null && vertical != null ->
            if (abs(dragX) >= abs(dragY)) horizontal else vertical
        else -> horizontal ?: vertical
    }
}

/** Positive when [dragX]/[dragY] moves from [edge] back onto the screen. */
internal fun floatingBallInward(edge: FloatingBallEdge, dragX: Float, dragY: Float): Float = when (edge) {
    FloatingBallEdge.LEFT -> dragX
    FloatingBallEdge.RIGHT -> -dragX
    FloatingBallEdge.TOP -> dragY
    FloatingBallEdge.BOTTOM -> -dragY
}

/** Window for an expanded ball, or the arrow tab left behind once it is tucked into [edge]. */
internal fun floatingBallFrame(
    edge: FloatingBallEdge?,
    x: Int,
    y: Int,
    screenWidth: Int,
    screenHeight: Int,
    ball: Int,
    tabThickness: Int,
    tabLength: Int = ball,
): FloatingBallFrame {
    val ballPx = ball.coerceAtLeast(1)
    val thickPx = tabThickness.coerceIn(1, ballPx)
    val lenPx = tabLength.coerceIn(thickPx, maxOf(screenWidth, screenHeight))
    val maxX = floatingBallLimit(screenWidth, ballPx)
    val maxY = floatingBallLimit(screenHeight, ballPx)
    val left = x.coerceIn(0, maxX)
    val top = y.coerceIn(0, maxY)
    val ballWidth = ballPx.coerceAtMost(screenWidth.coerceAtLeast(1))
    val ballHeight = ballPx.coerceAtMost(screenHeight.coerceAtLeast(1))
    return when (edge) {
        null -> FloatingBallFrame(left, top, ballWidth, ballHeight)
        FloatingBallEdge.LEFT -> {
            val dockHeight = lenPx.coerceAtMost(screenHeight)
            val dockTop = (top + (ballHeight - dockHeight) / 2).coerceIn(0, (screenHeight - dockHeight).coerceAtLeast(0))
            FloatingBallFrame(0, dockTop, thickPx.coerceAtMost(ballWidth), dockHeight)
        }
        FloatingBallEdge.RIGHT -> {
            val dockHeight = lenPx.coerceAtMost(screenHeight)
            val dockTop = (top + (ballHeight - dockHeight) / 2).coerceIn(0, (screenHeight - dockHeight).coerceAtLeast(0))
            FloatingBallFrame(
                (screenWidth - thickPx).coerceAtLeast(0), dockTop, thickPx.coerceAtMost(ballWidth), dockHeight,
            )
        }
        FloatingBallEdge.TOP -> {
            val dockWidth = lenPx.coerceAtMost(screenWidth)
            val dockLeft = (left + (ballWidth - dockWidth) / 2).coerceIn(0, (screenWidth - dockWidth).coerceAtLeast(0))
            FloatingBallFrame(dockLeft, 0, dockWidth, thickPx.coerceAtMost(ballHeight))
        }
        FloatingBallEdge.BOTTOM -> {
            val dockWidth = lenPx.coerceAtMost(screenWidth)
            val dockLeft = (left + (ballWidth - dockWidth) / 2).coerceIn(0, (screenWidth - dockWidth).coerceAtLeast(0))
            FloatingBallFrame(
                dockLeft, (screenHeight - thickPx).coerceAtLeast(0), dockWidth, thickPx.coerceAtMost(ballHeight),
            )
        }
    }
}

private fun expandedBackground(context: Context, width: Int, height: Int): BitmapDrawable {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = w * 0.30f
    val clip = Path().apply {
        addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
    }
    canvas.save()
    canvas.clipPath(clip)
    val icon = runCatching {
        context.packageManager.getApplicationIcon(context.packageName)
    }.getOrNull() ?: ContextCompat.getDrawable(context, R.drawable.ic_carplay)
    if (icon != null) {
        icon.setBounds(0, 0, w, h)
        icon.draw(canvas)
    }
    canvas.restore()
    return BitmapDrawable(context.resources, bitmap)
}

private fun dockBackground(
    context: Context,
    edge: FloatingBallEdge,
    width: Int,
    height: Int,
): BitmapDrawable {
    GlassColors.syncWithNightMode(context)
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val radius = minOf(w, h).toFloat()
    val stroke = LayoutMetrics.dp(context, ShellStroke.HAIRLINE).toFloat().coerceAtLeast(1f)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = when (edge) {
        FloatingBallEdge.LEFT -> RectF(-radius, 0f, w.toFloat(), h.toFloat())
        FloatingBallEdge.RIGHT -> RectF(0f, 0f, w + radius, h.toFloat())
        FloatingBallEdge.TOP -> RectF(0f, -radius, w.toFloat(), h.toFloat())
        FloatingBallEdge.BOTTOM -> RectF(0f, 0f, w.toFloat(), h + radius)
    }
    paint.style = Paint.Style.FILL
    paint.color = GlassColors.GHOST_FILL
    canvas.drawRoundRect(rect, radius, radius, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = stroke
    paint.color = GlassColors.GLASS_EDGE
    canvas.drawRoundRect(rect, radius, radius, paint)

    val density = context.resources.displayMetrics.density
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = (2.2f * density).coerceAtLeast(2f)
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    paint.color = GlassColors.INK
    val armDepth = (3.5f * density).coerceAtLeast(2f)
    val armSpan = (6.5f * density).coerceAtLeast(4f)
    val cx = w / 2f
    val cy = h / 2f
    val path = Path()
    when (edge) {
        FloatingBallEdge.LEFT -> {
            path.moveTo(cx - armDepth * 0.5f, cy - armSpan)
            path.lineTo(cx + armDepth * 0.5f, cy)
            path.lineTo(cx - armDepth * 0.5f, cy + armSpan)
        }
        FloatingBallEdge.RIGHT -> {
            path.moveTo(cx + armDepth * 0.5f, cy - armSpan)
            path.lineTo(cx - armDepth * 0.5f, cy)
            path.lineTo(cx + armDepth * 0.5f, cy + armSpan)
        }
        FloatingBallEdge.TOP -> {
            path.moveTo(cx - armSpan, cy - armDepth * 0.5f)
            path.lineTo(cx, cy + armDepth * 0.5f)
            path.lineTo(cx + armSpan, cy - armDepth * 0.5f)
        }
        FloatingBallEdge.BOTTOM -> {
            path.moveTo(cx - armSpan, cy + armDepth * 0.5f)
            path.lineTo(cx, cy - armDepth * 0.5f)
            path.lineTo(cx + armSpan, cy + armDepth * 0.5f)
        }
    }
    canvas.drawPath(path, paint)
    return BitmapDrawable(context.resources, bitmap)
}
