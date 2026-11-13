// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Draws the iPhone's cluster map on the head unit's presentation display. The window stays on that
 * display; it is not a view in the centre CarPlay activity.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private val onSurface: (Surface, Int, Int) -> Unit,
    private val onSurfaceChanged: (Surface, Int, Int, Int) -> Unit,
    private val onSurfaceGone: (Surface) -> Unit,
    private val onWindow: (String) -> Unit,
) : Presentation(context, display) {
    override fun onStop() {
        onWindow("stop")
        super.onStop()
    }

    override fun onDisplayRemoved() {
        onWindow("display removed")
        super.onDisplayRemoved()
    }

    override fun onDisplayChanged() {
        val metrics = DisplayMetrics()
        val shown = display
        runCatching { shown?.getRealMetrics(metrics) }
        onWindow(
            "display changed ${metrics.widthPixels}x${metrics.heightPixels} " +
                "rotation=${shown?.rotation ?: "?"}",
        )
        super.onDisplayChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val frame = holder.surfaceFrame
                onSurface(holder.surface, frame.width(), frame.height())
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                onSurfaceChanged(holder.surface, format, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceGone(holder.surface)
            }
        })
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
    }
}
