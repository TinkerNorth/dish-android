// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tinkernorth.dish.R

/**
 * Live stick plot: bounding box, crosshair, current position dot, and (during a range
 * capture) the sweep trail so the reach envelope is visible as it is learned. Inputs are
 * normalized -1..1; +Y down matches the wire convention.
 */
class StickPlotView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private var x = 0f
        private var y = 0f
        private val trail = Path()
        private var trailActive = false

        private val boxPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = ContextCompat.getColor(context, R.color.colorOutline)
            }
        private val crossPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = ContextCompat.getColor(context, R.color.colorOutline)
            }
        private val dotPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = ContextCompat.getColor(context, R.color.colorPrimary)
            }
        private val trailPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = ContextCompat.getColor(context, R.color.colorPrimaryMid)
            }

        fun update(sample: StickSample) {
            x = sample.x.coerceIn(-1f, 1f)
            y = sample.y.coerceIn(-1f, 1f)
            if (trailActive) {
                val px = toPxX(x)
                val py = toPxY(y)
                if (trail.isEmpty) trail.moveTo(px, py) else trail.lineTo(px, py)
            }
            invalidate()
        }

        fun startTrail() {
            trail.reset()
            trailActive = true
            invalidate()
        }

        fun stopTrail() {
            trailActive = false
            invalidate()
        }

        fun clearTrail() {
            trail.reset()
            invalidate()
        }

        private fun toPxX(v: Float): Float = width / 2f + v * (width / 2f - PAD)

        private fun toPxY(v: Float): Float = height / 2f + v * (height / 2f - PAD)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(1f, 1f, w - 1f, h - 1f, boxPaint)
            canvas.drawLine(w / 2f, PAD, w / 2f, h - PAD, crossPaint)
            canvas.drawLine(PAD, h / 2f, w - PAD, h / 2f, crossPaint)
            canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f - PAD, crossPaint)
            if (!trail.isEmpty) canvas.drawPath(trail, trailPaint)
            canvas.drawCircle(toPxX(x), toPxY(y), DOT_RADIUS, dotPaint)
        }

        private companion object {
            const val PAD = 8f
            const val DOT_RADIUS = 7f
        }
    }

/** Touch surface tester: rectangle with per-finger dots and trails, tinted on click. */
class TouchPlotView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private var f0: Pair<Float, Float>? = null
        private var f1: Pair<Float, Float>? = null
        private var click = false
        private val trail0 = Path()
        private val trail1 = Path()

        private val boxPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = ContextCompat.getColor(context, R.color.colorOutline)
            }
        private val clickPaint =
            Paint().apply {
                style = Paint.Style.FILL
                color = ContextCompat.getColor(context, R.color.colorPrimaryDark)
                alpha = 40
            }
        private val dot0Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.colorPrimary) }
        private val dot1Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.colorWarning) }
        private val trail0Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = ContextCompat.getColor(context, R.color.colorPrimaryMid)
            }
        private val trail1Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = ContextCompat.getColor(context, R.color.colorWarning)
                alpha = 140
            }

        // Wire coords are full-range int16 in both axes, +Y down.
        fun update(snapshot: InputSnapshot) {
            click = snapshot.click
            f0 = trackFinger(snapshot.f0Active, snapshot.f0X, snapshot.f0Y, trail0)
            f1 = trackFinger(snapshot.f1Active, snapshot.f1X, snapshot.f1Y, trail1)
            invalidate()
        }

        private fun trackFinger(
            active: Boolean,
            wireX: Int,
            wireY: Int,
            trail: Path,
        ): Pair<Float, Float>? {
            if (!active) {
                trail.reset()
                return null
            }
            val nx = (wireX + HALF_RANGE) / FULL_RANGE
            val ny = (wireY + HALF_RANGE) / FULL_RANGE
            val px = nx * width
            val py = ny * height
            if (trail.isEmpty) trail.moveTo(px, py) else trail.lineTo(px, py)
            return px to py
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (click) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clickPaint)
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, boxPaint)
            if (!trail0.isEmpty) canvas.drawPath(trail0, trail0Paint)
            if (!trail1.isEmpty) canvas.drawPath(trail1, trail1Paint)
            f0?.let { canvas.drawCircle(it.first, it.second, DOT_RADIUS, dot0Paint) }
            f1?.let { canvas.drawCircle(it.first, it.second, DOT_RADIUS, dot1Paint) }
        }

        private companion object {
            const val HALF_RANGE = 32768f
            const val FULL_RANGE = 65535f
            const val DOT_RADIUS = 9f
        }
    }

/** Minimal polyline over the recent RTT window; scaled to its own max so shape survives spikes. */
class SparklineView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private var values: FloatArray = FloatArray(0)

        private val linePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = ContextCompat.getColor(context, R.color.colorPrimary)
            }

        fun update(newValues: FloatArray) {
            values = newValues
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (values.size < 2) return
            val max = values.max().coerceAtLeast(1f)
            val stepX = width.toFloat() / (values.size - 1)
            var prevX = 0f
            var prevY = height - (values[0] / max) * (height - PAD)
            for (i in 1 until values.size) {
                val x = i * stepX
                val y = height - (values[i] / max) * (height - PAD)
                canvas.drawLine(prevX, prevY, x, y, linePaint)
                prevX = x
                prevY = y
            }
        }

        private companion object {
            const val PAD = 4f
        }
    }
