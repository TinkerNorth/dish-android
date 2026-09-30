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

private const val ALPHA_MAX = 255f
private const val FULL_TURN_DEG = 360f
private const val TOP_START_DEG = -90f
private const val SPEC_SIZE_PX = 64f

// The spinner spec: stroke and sweep as fractions of the 64px art, the track dimmed under it.
private const val SPINNER_STROKE_RATIO = 6f / SPEC_SIZE_PX
private const val SPINNER_SWEEP_FRACTION = 50f / 138f
private const val SPINNER_TRACK_ALPHA = 0.25f

// The dots spec: three dots on a 16px pitch, each breathing in size and opacity a step behind
// the last.
private const val DOT_COUNT = 3
private const val DOT_STAGGER_FRACTION = 0.18f / 1.2f
private const val DOT_PITCH_PX = 16f
private const val DOT_RADIUS_MIN_PX = 4f
private const val DOT_RADIUS_RANGE_PX = 2f
private const val DOT_ALPHA_MIN = 0.25f
private const val DOT_ALPHA_RANGE = 0.75f

// The bar spec, as fractions of the 240px art.
private const val BAR_SPEC_WIDTH_PX = 240f
private const val BAR_HEIGHT_RATIO = 16f / BAR_SPEC_WIDTH_PX
private const val BAR_TRACK_RATIO = 8f / BAR_SPEC_WIDTH_PX
private const val BAR_SLIDER_RATIO = 80f / BAR_SPEC_WIDTH_PX
private const val BAR_TRACK_ALPHA = 0.22f

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
    private val animator = linearLoop(context.resources.getInteger(R.integer.motion_duration_spinner).toLong(), ::onFrame)

    private fun onFrame(p: Float) {
        phase = p
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() == 0 || b.height() == 0) return
        val side = minOf(b.width(), b.height()).toFloat()
        val stroke = side * SPINNER_STROKE_RATIO
        strokePaint.strokeWidth = stroke
        val inset = stroke / 2f
        rect.set(
            b.left + inset,
            b.top + inset,
            b.right - inset,
            b.bottom - inset,
        )
        strokePaint.alpha = (SPINNER_TRACK_ALPHA * ALPHA_MAX).toInt()
        canvas.drawArc(rect, 0f, FULL_TURN_DEG, false, strokePaint)
        strokePaint.alpha = ALPHA_MAX.toInt()
        val sweep = FULL_TURN_DEG * SPINNER_SWEEP_FRACTION
        val rotation = phase * FULL_TURN_DEG
        canvas.drawArc(rect, TOP_START_DEG + rotation, sweep, false, strokePaint)
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

    // Abstract on Drawable, so it must be implemented; deprecated there since API 29 because the
    // framework ignores it, which is why this override carries the same mark.
    @Deprecated("Drawable.getOpacity is unused by the framework from API 29.")
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
    private val animator = linearLoop(context.resources.getInteger(R.integer.motion_duration_spinner).toLong(), ::onFrame)

    private fun onFrame(p: Float) {
        phase = p
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() == 0 || b.height() == 0) return
        val side = minOf(b.width(), b.height()).toFloat()
        val scale = side / SPEC_SIZE_PX
        for (i in 0 until DOT_COUNT) {
            val dotPhase = ((phase + i * DOT_STAGGER_FRACTION) % 1f).let { if (it < 0f) it + 1f else it }
            val tri = 1f - abs(dotPhase - 0.5f) * 2f
            val opacity = DOT_ALPHA_MIN + DOT_ALPHA_RANGE * tri
            val r = scale * (DOT_RADIUS_MIN_PX + DOT_RADIUS_RANGE_PX * tri)
            val cx = b.left + scale * (DOT_PITCH_PX + i * DOT_PITCH_PX)
            val cy = b.exactCenterY()
            fillPaint.alpha = (opacity * ALPHA_MAX).toInt()
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

    // Abstract on Drawable, so it must be implemented; deprecated there since API 29 because the
    // framework ignores it, which is why this override carries the same mark.
    @Deprecated("Drawable.getOpacity is unused by the framework from API 29.")
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
            alpha = (BAR_TRACK_ALPHA * ALPHA_MAX).toInt()
        }

    private val sliderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = this@DishBarDrawable.color
        }

    private val trackRect = RectF()
    private val sliderRect = RectF()

    private var phase: Float = 0f
    private val animator = linearLoop(context.resources.getInteger(R.integer.motion_duration_bar).toLong(), ::onFrame)

    private fun onFrame(p: Float) {
        phase = p
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = widthPx

    override fun getIntrinsicHeight(): Int = (widthPx * BAR_HEIGHT_RATIO).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() == 0 || b.height() == 0) return
        val w = b.width().toFloat()
        val trackHeight = w * BAR_TRACK_RATIO
        val sliderWidth = w * BAR_SLIDER_RATIO
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
        trackPaint.alpha = (BAR_TRACK_ALPHA * ALPHA_MAX).toInt()
    }

    // Abstract on Drawable, so it must be implemented; deprecated there since API 29 because the
    // framework ignores it, which is why this override carries the same mark.
    @Deprecated("Drawable.getOpacity is unused by the framework from API 29.")
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
