// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.tinkernorth.dish.R
import kotlin.math.abs

class ScrollAccumulator(
    private val stepPx: Float,
) {
    private var remainder = 0f

    fun add(deltaPx: Float): Int {
        val total = remainder + deltaPx
        val notches = (total / stepPx).toInt()
        remainder = total - notches * stepPx
        return notches
    }

    fun reset() {
        remainder = 0f
    }
}

class ScrollStripView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        var onScroll: ((Int) -> Unit)? = null
        var onMiddleTap: (() -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val accumulator = ScrollAccumulator(STEP_DP_PER_NOTCH * density)

        private val bgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorSurfaceDim)
            }
        private val activePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(context, R.color.colorPrimary),
                        ACTIVE_TINT_ALPHA,
                    )
            }
        private val glyphPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.colorOnSurfaceVariant)
            }

        private val bounds = RectF()
        private val chevron = Path()

        private var trackedPointerId = INVALID_POINTER
        private var downY = 0f
        private var lastY = 0f
        private var downTimeMs = 0L
        private var scrolled = false
        private var touching = false

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    requestUnbufferedDispatch(event)
                    trackedPointerId = event.getPointerId(0)
                    downY = event.y
                    lastY = event.y
                    downTimeMs = event.eventTime
                    scrolled = false
                    touching = true
                    accumulator.reset()
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(trackedPointerId)
                    if (index < 0) return true
                    val y = event.getY(index)
                    if (!scrolled && abs(y - downY) > touchSlop) scrolled = true
                    if (scrolled) {
                        val notches = accumulator.add(lastY - y)
                        if (notches != 0) onScroll?.invoke(notches)
                    }
                    lastY = y
                }
                MotionEvent.ACTION_UP -> {
                    touching = false
                    if (!scrolled && event.eventTime - downTimeMs <= TAP_MAX_MS) performClick()
                    trackedPointerId = INVALID_POINTER
                    accumulator.reset()
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    trackedPointerId = INVALID_POINTER
                    accumulator.reset()
                    invalidate()
                }
                else -> return false
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            onMiddleTap?.invoke()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            val corner = CORNER_RADIUS_DP * density
            canvas.drawRoundRect(bounds, corner, corner, bgPaint)
            if (touching) canvas.drawRoundRect(bounds, corner, corner, activePaint)

            val cx = width / 2f
            val half = CHEVRON_HALF_WIDTH_DP * density
            val rise = CHEVRON_RISE_DP * density
            val inset = CHEVRON_EDGE_INSET_DP * density
            drawChevron(canvas, cx, inset + rise, half, -rise)
            drawChevron(canvas, cx, height - inset - rise, half, rise)
            val dotRadius = DOT_RADIUS_DP * density
            val dotGap = DOT_GAP_DP * density
            canvas.drawCircle(cx, height / 2f - dotGap, dotRadius, glyphPaint)
            canvas.drawCircle(cx, height / 2f, dotRadius, glyphPaint)
            canvas.drawCircle(cx, height / 2f + dotGap, dotRadius, glyphPaint)
        }

        private fun drawChevron(
            canvas: Canvas,
            cx: Float,
            baseY: Float,
            halfWidth: Float,
            rise: Float,
        ) {
            chevron.reset()
            chevron.moveTo(cx - halfWidth, baseY)
            chevron.lineTo(cx, baseY + rise)
            chevron.lineTo(cx + halfWidth, baseY)
            chevron.close()
            canvas.drawPath(chevron, glyphPaint)
        }

        private companion object {
            const val INVALID_POINTER = -1
            const val STEP_DP_PER_NOTCH = 32f
            const val TAP_MAX_MS = 250L
            const val ACTIVE_TINT_ALPHA = 0x40
            const val CORNER_RADIUS_DP = 14f
            const val CHEVRON_HALF_WIDTH_DP = 9f
            const val CHEVRON_RISE_DP = 7f
            const val CHEVRON_EDGE_INSET_DP = 14f
            const val DOT_RADIUS_DP = 2.5f
            const val DOT_GAP_DP = 9f
        }
    }
