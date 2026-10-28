// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.graphics.Matrix
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarPlayHostActivity
import com.shihab.diplay.CarPlayHostActivity.DisplaySize
import com.shihab.diplay.CarPlayViewAreas
import com.shihab.diplay.SplitScreenSettings
import com.shihab.diplay.media.CarPlayVideoLayout
import com.shihab.diplay.session.CarPlaySessionDisplay
import com.shihab.diplay.util.AppLog

/**
 * Display-size debounce, view-area / split-screen handling, and video layout transforms for Host.
 * Owns the intentional session auto-calibration prefs writes (max detected display, split windows).
 */
internal class HostDisplayLayout(private val activity: CarPlayHostActivity) {

    val applyDisplaySizeRunnable = Runnable {
        val size = activity.pendingDisplaySize ?: return@Runnable
        activity.pendingDisplaySize = null
        applyDisplaySize(size)
    }

    fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || activity.shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == activity.pendingDisplaySize) return
        activity.mainHandler.removeCallbacks(applyDisplaySizeRunnable)
        if (size == activity.activeDisplaySize && !displayLayoutChanged()) {
            activity.pendingDisplaySize = null
            // A rotation back can cancel the last pending resize after teardown has finished.
            activity.maybeStartCarPlay()
            return
        }
        activity.pendingDisplaySize = size
        // With a turning screen's square canvas nothing reconnects, so the turn need not wait for the size to settle.
        activity.mainHandler.postDelayed(
            applyDisplaySizeRunnable,
            if (activity.sessionDisplay?.viewAreas?.turnsWithScreen == true) {
                0L
            } else {
                CarPlayHostActivity.DISPLAY_CHANGE_DEBOUNCE_MILLIS
            },
        )
    }

    private fun applyDisplaySize(size: DisplaySize) {
        val display = activity.sessionDisplay
        if (display != null && applyViewArea(size, display)) return
        val layoutChanged = displayLayoutChanged(size)
        if (activity.shuttingDown.get()) return
        if (size == activity.activeDisplaySize && !layoutChanged) {
            // Teardown may have removed the session that made this same-size rotation pending.
            activity.maybeStartCarPlay()
            return
        }
        val previous = activity.activeDisplaySize
        activity.activeDisplaySize = size
        recordDetectedMaximum(size)
        if (previous == null) {
            activity.appendLog("Display detected: ${size.width}x${size.height}")
            activity.maybeStartCarPlay()
        } else if (activity.handshakeResetInProgress) {
            activity.appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (activity.controller == null && display == null) {
            activity.appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            activity.maybeStartCarPlay()
        } else if (display != null && !layoutChanged &&
            size.width <= display.windowWidth && size.height <= display.windowHeight
        ) {
            // Keep camera shrink/restore cycles within the original window connected. If the
            // session started in a camera window, growth beyond it needs a full-size canvas.
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                "keeping CarPlay session canvas=${display.width}x${display.height}"
            activity.appendLog(message)
            AppLog.i(CarPlayHostActivity.TAG, message)
            activity.videoView?.let { updateVideoLayout(it.width, it.height) }
        } else {
            activity.restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    /**
     * Entering or leaving the head unit's split screen, or turning the screen with a square canvas, moves
     * CarPlay to the matching view area instead of reconnecting. The split window is remembered per screen
     * orientation for the next session's area. False when the change is something else (a camera window,
     * a turn without a square canvas), which the usual path handles.
     */
    private fun applyViewArea(size: DisplaySize, display: CarPlaySessionDisplay): Boolean {
        val areas = display.viewAreas ?: return false
        if (activity.controller == null || activity.handshakeResetInProgress ||
            activity.shuttingDown.get()
        ) {
            return false
        }
        val split = activity.isMultiWindowActive()
        // A full window's own shape tells the screen's orientation; during a turn the display metrics and
        // rotation can lag behind the window. A split-screen window can have either shape, so it asks the
        // display, which is settled then.
        val portrait = if (split) screenPortrait() else size.height > size.width
        val previous = activity.activeDisplaySize
        if (areas.turnsWithScreen && display.rotation != activity.displayRotation() && size == previous) {
            // The turn arrived before the window's new size: note it and wait for the size, which picks
            // the area, instead of letting the old rotation look like a changed layout.
            activity.sessionDisplay = display.copy(rotation = activity.displayRotation())
            AppLog.i(
                CarPlayHostActivity.TAG,
                "Screen turned before the window resized (${size.width}x${size.height}); waiting for its size",
            )
            return true
        }
        val turned = previous != null && !split && (previous.height > previous.width) != portrait
        if (turned && !areas.turnsWithScreen) return false
        if (split) {
            // Against the whole screen, which the system bars (shown in BYD's split screen) do not change.
            val screen = android.util.DisplayMetrics().also {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.getRealMetrics(it)
            }
            val screenLong = maxOf(screen.widthPixels, screen.heightPixels).toFloat()
            val screenShort = minOf(screen.widthPixels, screen.heightPixels).toFloat()
            if (screenLong > 0 && screenShort > 0) {
                if (portrait) {
                    SplitScreenSettings.saveWindow(
                        activity, true, size.width / screenShort, size.height / screenLong,
                    )
                } else {
                    SplitScreenSettings.saveWindow(
                        activity, false, size.width / screenLong, size.height / screenShort,
                    )
                }
            }
        }
        val target = areas.indexFor(size.width, size.height, split, portrait) ?: return false
        if (activity.sidePanelShown) activity.resetSidePanel() // a turn or the split screen ends the side panel
        // The same whole-screen area without a turn (a camera window, say) keeps the usual handling.
        if (!turned && target == areas.current &&
            areas.kindOf(target) == CarPlayViewAreas.Kind.FULL_SCREEN
        ) {
            return false
        }
        activity.activeDisplaySize = size
        activity.sessionDisplay = display.copy(rotation = activity.displayRotation())
        val sent = if (target == areas.current) true else activity.controller?.showViewArea(target) == true
        if (sent) areas.use(target)
        activity.videoView?.let { updateVideoLayout(it.width, it.height) }
        val message = "Window ${previous?.width}x${previous?.height} -> ${size.width}x${size.height} " +
            "split=$split portrait=$portrait turned=$turned: view area $target sent=$sent"
        activity.appendLog(message)
        AppLog.i(CarPlayHostActivity.TAG, message)
        return true
    }

    /** Whether the screen itself is portrait now (DiPlay's own window may be a split-screen part of it). */
    @Suppress("DEPRECATION")
    fun screenPortrait(): Boolean {
        val metrics = android.util.DisplayMetrics()
        activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels > metrics.widthPixels
    }

    fun displayLayoutChanged(newSize: DisplaySize? = activity.activeDisplaySize): Boolean {
        val display = activity.sessionDisplay ?: return false
        if (display.rotation != activity.displayRotation()) return true
        if (display.hideTopBar != activity.hideTopBar || display.hideBottomBar != activity.hideBottomBar) {
            return true
        }
        if (newSize != null && newSize.width > 0 && newSize.height > 0) {
            val baseAspect = display.width.toDouble() / display.height
            val currentAspect = newSize.width.toDouble() / newSize.height
            val aspectDiff = kotlin.math.abs(currentAspect / baseAspect - 1.0)
            if (aspectDiff > 0.08 && AirPlayPersistence.loadAdaptPipResolution(activity)) {
                return true
            }
        }
        return false
    }

    fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val display = activity.sessionDisplay
            ?: return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // In the head unit's split screen, or on a turning screen's square canvas, CarPlay draws in part of
        // the canvas; that area fills the window and the rest falls outside it. Video and touches both
        // follow this. An area of the whole canvas keeps the usual fit.
        display.viewAreas?.let { areas ->
            val area = areas.layoutArea(areas.current)
            if (area.width != display.width || area.height != display.height) {
                val scaleX = viewWidth.toFloat() / area.width
                val scaleY = viewHeight.toFloat() / area.height
                return CarPlayVideoLayout(
                    -area.originX * scaleX, -area.originY * scaleY,
                    display.width * scaleX, display.height * scaleY,
                )
            }
        }
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        val view = activity.videoView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val content = contentRect(viewWidth, viewHeight)
        val fallback = activity.fallbackVideoView
        if (fallback != null) {
            val bounds = CarPlaySurfaceBounds.from(content)
            if (activity.fallbackVideoBounds != bounds) {
                activity.fallbackVideoBounds = bounds
                fallback.layoutParams = FrameLayout.LayoutParams(
                    bounds.width, bounds.height,
                    Gravity.TOP or Gravity.LEFT,
                ).apply {
                    leftMargin = bounds.left
                    topMargin = bounds.top
                }
            }
        } else {
            (view as? TextureView)?.setTransform(Matrix().apply {
                setScale(content.width / viewWidth, content.height / viewHeight)
                postTranslate(content.left, content.top)
            })
        }
        if (activity.sidePanelShown) activity.placeSidePanel(viewWidth, viewHeight)
    }

    fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(activity.maximumDetectedWidthPixels, size.width)
        val height = maxOf(activity.maximumDetectedHeightPixels, size.height)
        if (width == activity.maximumDetectedWidthPixels &&
            height == activity.maximumDetectedHeightPixels
        ) {
            return
        }
        activity.maximumDetectedWidthPixels = width
        activity.maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(activity, width, height)
    }
}
