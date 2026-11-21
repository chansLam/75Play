// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.shihab.diplay.host.R

/**
 * Draws the iPhone's cluster map on the head unit's presentation display. The window stays on that
 * display; it is not a view in the centre CarPlay activity.
 *
 * [testOnly] renders a local test card instead of the video surface. It answers "can this app
 * draw on the cluster display at all" independently of the iPhone stream, so a blank test card
 * isolates a display/window problem from a stream problem.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private val testOnly: Boolean = false,
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
        val metrics = DisplayMetrics()
        runCatching { display?.getRealMetrics(metrics) }
        if (testOnly) {
            // 本地测试卡：不依赖 iPhone 流。若仪表看不到这张卡，故障在显示通道（窗口/层级/屏）；
            // 若能看到但没有地图，故障在 alt 视频流或 showUI。
            val card = FrameLayout(context).apply { setBackgroundColor(Color.rgb(0x1B, 0x4F, 0x2A)) }
            card.addView(
                TextView(context).apply {
                    text = "75Play\n${context.getString(R.string.cluster_test_card)}\n" +
                        "${metrics.widthPixels}x${metrics.heightPixels} id=${display?.displayId ?: "?"}"
                    setTextColor(Color.WHITE)
                    textSize = 28f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            setContentView(card)
            return
        }
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
