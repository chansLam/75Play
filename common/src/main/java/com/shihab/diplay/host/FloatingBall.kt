package com.shihab.diplay.host

import android.content.Context
import com.shihab.diplay.CarPlayHostActivity
import com.shihab.diplay.DiPlayActivity
import com.shihab.diplay.WheelKeyService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * A small white floating ball shown while the DiPlay app is in front AND while CarPlay is in
 * front (in CarPlay a tap returns to the DiPlay home). On the DiPlay home a tap simulates a
 * system Back press (via accessibility); a long-press returns to the CarPlay screen. Drag to
 * move it anywhere on screen. Needs install-time SYSTEM_ALERT_WINDOW; uses TYPE_PHONE (API 19).
 */
internal object FloatingBall {
    const val TAG = "DiPlay-FloatingBall"

    private const val PREFS = "diplay_floating_ball"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val BALL_SIZE_DP = 56
    private const val TOP_MARGIN_DP = 96

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var onTapAction: (() -> Unit)? = null
    private var onLongPressAction: (() -> Unit)? = null

    @Suppress("UNUSED_PARAMETER")
    fun permitted(context: Context): Boolean = true

    val shown: Boolean get() = root != null

    /**
     * Adds the floating ball. [onTap] returns to CarPlay, [onLongPress] opens recents. Main thread.
     */
    fun show(context: Context, onTap: () -> Unit, onLongPress: () -> Unit): Boolean {
        // Actions can change when the front activity changes (e.g. tap returns to DiPlay while
        // CarPlay is in front); update them even when the ball is already shown.
        onTapAction = onTap
        onLongPressAction = onLongPress
        if (root != null) return true
        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val metrics = context.resources.displayMetrics
        val size = (BALL_SIZE_DP * metrics.density).toInt()
        val maxX = (metrics.widthPixels - size).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - size).coerceAtLeast(0)
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // TOP|START so x/y are measured from the top-left and the ball can be dragged
            // anywhere on screen (TOP|END previously confined it to the right half).
            gravity = Gravity.TOP or Gravity.START
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val topY = (TOP_MARGIN_DP * metrics.density).toInt()
            val savedX = prefs.getInt(KEY_X, -1)
            x = (if (savedX < 0) maxX else savedX).coerceIn(0, maxX)
            y = prefs.getInt(KEY_Y, topY).coerceIn(0, maxY)
            title = "DiPlay floating ball"
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = params.x
        var startY = params.y
        var downAt = 0L
        var dragging = false
        val ball = FrameLayout(context).apply {
            // 悬浮球圆角化：把应用图标本身裁剪成圆角矩形直接显示（去掉深蓝圆底+内嵌图标的
            // 双层方案，避免视觉突兀）。API19 无 outline，用 Bitmap + clipPath 圆角裁剪。
            val radius = size * 0.30f
            val bmp = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val clip = android.graphics.Path().apply {
                addRoundRect(
                    android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()),
                    radius, radius, android.graphics.Path.Direction.CW)
            }
            c.save()
            c.clipPath(clip)
            val icon = runCatching {
                context.packageManager.getApplicationIcon(context.packageName)
            }.getOrNull() ?: ContextCompat.getDrawable(
                context, com.shihab.diplay.host.R.drawable.ic_carplay)
            if (icon != null) {
                icon.setBounds(0, 0, size, size)
                icon.draw(c)
            }
            c.restore()
            background = android.graphics.drawable.BitmapDrawable(context.resources, bmp)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startX = params.x; startY = params.y
                        downAt = SystemClock.uptimeMillis()
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dragging || abs(dx) > slop || abs(dy) > slop) {
                            dragging = true
                            params.x = (startX + dx).toInt().coerceIn(0, maxX)
                            params.y = (startY + dy).toInt().coerceIn(0, maxY)
                            runCatching { windowManager.updateViewLayout(view, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
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
            windowManager.addView(ball, params)
            root = ball
            Log.i(TAG, "ball shown ${size}x$size at ${params.x},${params.y}")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "ball failed", error)
            false
        }
    }

    /** Removes the floating ball. Main thread. */
    fun hide() {
        val view = root ?: return
        root = null
        @Suppress("DEPRECATION")
        val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { windowManager?.removeViewImmediate(view) }
        Log.i(TAG, "ball hidden")
    }

    /** Returns to the CarPlay screen from anywhere (application context). */
    fun backToCarPlay(context: Context) {
        runCatching {
            val intent = Intent(context, CarPlayHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
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

    /** Simulate a system Back press (floating ball tap), via the accessibility service. */
    fun back(): Boolean = WheelKeyService.pressBack()
}
