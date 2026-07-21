// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.withClip
import com.tinkernorth.dish.R
import kotlin.math.abs

private fun linearLoop(
    durationMs: Long,
    onFrame: (phase: Float) -> Unit,
): ValueAnimator =
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = durationMs
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        // Linear: any easing would change the spec's visual rhythm.
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { animator -> onFrame(animator.animatedValue as Float) }
    }

class DishSpinnerDrawable(
    context: Context,
    private val sizePx: Int,
) : Drawable(),
    Animatable {
    private val color = ContextCompat.getColor(context, R.color.colorPrimary)

    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            this.color = this@DishSpinnerDrawable.color
        }

    private val rect = RectF()

    private var phase: Float = 0f
    private val animator =
        linearLoop(context.resources.getInteger(R.integer.motion_duration_spinner).toLong()) { p ->
            phase = p
            invalidateSelf()
        }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() == 0 || b.height() == 0) return
        val side = minOf(b.width(), b.height()).toFloat()
        val stroke = side * (6f / 64f)
        strokePaint.strokeWidth = stroke
        val inset = stroke / 2f
        rect.set(
            b.left + inset,
            b.top + inset,
            b.right - inset,
            b.bottom - inset,
        )
        strokePaint.alpha = (0.25f * 255f).toInt()
        canvas.drawArc(rect, 0f, 360f, false, strokePaint)
        strokePaint.alpha = 255
        val sweep = 360f * (50f / 138f)
        val rotation = phase * 360f
        canvas.drawArc(rect, -90f + rotation, sweep, false, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
    }

    override fun setTintList(tint: ColorStateList?) {
        val tinted = tint?.defaultColor ?: color
        strokePaint.color = tinted
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (!animator.isStarted) animator.start()
    }

    override fun stop() {
        if (animator.isStarted) animator.cancel()
    }

    override fun isRunning(): Boolean = animator.isRunning

    override fun setVisible(
        visible: Boolean,
        restart: Boolean,
    ): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (!animator.isStarted) animator.start()
        } else {
            if (animator.isStarted) animator.cancel()
        }
        return changed
    }
}

class DishDotsDrawable(
    context: Context,
    private val sizePx: Int,
) : Drawable(),
    Animatable {
    private val color = ContextCompat.getColor(context, R.color.colorPrimary)

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = this@DishDotsDrawable.color
        }

    private var phase: Float = 0f
    private val animator =
        linearLoop(context.resources.getInteger(R.integer.motion_duration_spinner).toLong()) { p ->
            phase = p
            invalidateSelf()
        }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() == 0 || b.height() == 0) return
        val side = minOf(b.width(), b.height()).toFloat()
        val scale = side / 64f
        val staggerFraction = 0.18f / 1.2f
        for (i in 0..2) {
            val dotPhase = ((phase + i * staggerFraction) % 1f).let { if (it < 0f) it + 1f else it }
            val tri = 1f - abs(dotPhase - 0.5f) * 2f
            val opacity = 0.25f + 0.75f * tri
            val r = scale * (4f + 2f * tri)
            val cx = b.left + scale * (16f + i * 16f)
            val cy = b.exactCenterY()
            fillPaint.alpha = (opacity * 255f).toInt()
            canvas.drawCircle(cx, cy, r, fillPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
    }

    override fun setTintList(tint: ColorStateList?) {
        val tinted = tint?.defaultColor ?: color
        fillPaint.color = tinted
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (!animator.isStarted) animator.start()
    }

    override fun stop() {
        if (animator.isStarted) animator.cancel()
    }

    override fun isRunning(): Boolean = animator.isRunning

    override fun setVisible(
        visible: Boolean,
        restart: Boolean,
    ): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (!animator.isStarted) animator.start()
        } else {
            if (animator.isStarted) animator.cancel()
        }
        return changed
    }
}

class DishBarDrawable(
    context: Context,
    private val widthPx: Int,
) : Drawable(),
    Animatable {
    private val color = ContextCompat.getColor(context, R.color.colorPrimary)

    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = this@DishBarDrawable.color
            alpha = (0.22f * 255f).toInt()
        }

    private val sliderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = this@DishBarDrawable.color
        }

    private val trackRect = RectF()
    private val sliderRect = RectF()

    private var phase: Float = 0f
    private val animator =
        linearLoop(context.resources.getInteger(R.integer.motion_duration_bar).toLong()) { p ->
            phase = p
            invalidateSelf()
        }

    override fun getIntrinsicWidth(): Int = widthPx

    override fun getIntrinsicHeight(): Int = (widthPx * (16f / 240f)).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() == 0 || b.height() == 0) return
        val w = b.width().toFloat()
        val trackHeight = w * (8f / 240f)
        val sliderWidth = w * (80f / 240f)
        val cy = b.exactCenterY()
        val top = cy - trackHeight / 2f
        val bottom = cy + trackHeight / 2f
        val rx = trackHeight / 2f
        trackRect.set(b.left.toFloat(), top, b.right.toFloat(), bottom)
        canvas.drawRoundRect(trackRect, rx, rx, trackPaint)
        val x = -sliderWidth + (w + sliderWidth) * phase
        sliderRect.set(b.left + x, top, b.left + x + sliderWidth, bottom)
        canvas.withClip(trackRect) {
            drawRoundRect(sliderRect, rx, rx, sliderPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        sliderPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        sliderPaint.colorFilter = colorFilter
        trackPaint.colorFilter = colorFilter
    }

    override fun setTintList(tint: ColorStateList?) {
        val tinted = tint?.defaultColor ?: color
        sliderPaint.color = tinted
        trackPaint.color = tinted
        trackPaint.alpha = (0.22f * 255f).toInt()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (!animator.isStarted) animator.start()
    }

    override fun stop() {
        if (animator.isStarted) animator.cancel()
    }

    override fun isRunning(): Boolean = animator.isRunning

    override fun setVisible(
        visible: Boolean,
        restart: Boolean,
    ): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (!animator.isStarted) animator.start()
        } else {
            if (animator.isStarted) animator.cancel()
        }
        return changed
    }
}
