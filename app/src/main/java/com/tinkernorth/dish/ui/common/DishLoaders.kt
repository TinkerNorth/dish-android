// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

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
import com.tinkernorth.dish.R
import kotlin.math.abs

/**
 * Three indeterminate progress drawables ported from the design spec in
 * `app-icon/project/app-essentials.jsx` (sections `LoaderSpinner`,
 * `LoaderDots`, `LoaderBar`). Proportions (stroke width, dasharray ratio,
 * timings) are pixel-faithful to the spec; the colour is `colorPrimary`
 * rather than the spec's hard-coded `#8FCFE3` — this app's brand intentionally
 * runs on a different blue (see `colors.xml`), but every other geometric rule
 * of the spec is preserved. The Swift twin lives in dish-mac at
 * `Sources/Dish/UI/DishLoaders.swift`.
 *
 * The loaders implement [Drawable] + [Animatable] so they can be used in
 * three places without ceremony:
 *   1. As `MaterialButton.icon` (the in-button loader pattern used on the
 *      connection screens — the spinner accompanies the label inside the same
 *      button so disabled-state and "working"-state read as one thing).
 *   2. As the `src` of an [android.widget.ImageView] anywhere a section-level
 *      hint is needed (e.g. "this list might still update").
 *   3. As any other drawable host's source.
 *
 * Use:
 *   · [DishSpinnerDrawable] — default for short, bounded waits (network calls, scans).
 *   · [DishDotsDrawable]    — "thinking" states (AI generation, search suggestions).
 *   · [DishBarDrawable]     — whole-area / pane-level loading.
 *
 * All three call [Drawable.invalidateSelf] from a [ValueAnimator] tied to the
 * platform animation clock. Callers that mount the drawable on a `MaterialButton`
 * via `setIcon()` don't need to call [start] manually — Material kicks off
 * animatable icons automatically. Callers that mount on an `ImageView` should
 * either call `start()` directly or rely on `ImageView.setImageDrawable` doing
 * the start dance via [Animatable].
 *
 * Durations resolve from R.integer.motion_duration_* at construction time
 * so a future timing tweak in motion.xml lands in every loader without a
 * recompile or a touched constant here. Spinner + dots share the same
 * period (1.2 s in the spec); the bar runs slightly slower (1.4 s) on
 * purpose to read as a different cadence than the spinner.
 */

private fun linearLoop(
    durationMs: Long,
    onFrame: (phase: Float) -> Unit,
): ValueAnimator =
    // Repeating linear ValueAnimator that drives a Drawable invalidation each
    // frame for [durationMs]. The 0..1 fraction is passed into [onFrame] each
    // tick — keeping the timing math inside the drawable means setBounds
    // resizes are reflected immediately without re-parameterising the animator.
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = durationMs
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        // Linear interpolator: the spec's SMIL `<animate>` is linear → triangle
        // for the dots, linear → rotation for the spinner, linear → translate
        // for the bar. Any easing would change the visual rhythm.
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { animator -> onFrame(animator.animatedValue as Float) }
    }

// ─── Spinner ────────────────────────────────────────────────────────────

/**
 * Indeterminate rotating arc. Spec reference: `LoaderSpinner` in
 * app-essentials.jsx.
 *
 * - 64×64 design canvas → stroke width is `size × 6/64`.
 * - Background ring sits at 25% alpha, full arc on top at 100%.
 * - Visible arc is `strokeDasharray="50 88"` of the circumference, i.e.
 *   `50 / 138 ≈ 36.2%` of the ring (the rest is gap).
 * - 1.2 s linear rotation, indefinite.
 *
 * The intrinsic size is the size passed at construction; callers can override
 * by calling [setBounds] on the drawable (the MaterialButton icon size
 * defaults to honouring intrinsic bounds, so picking a sensible default at
 * the call site is the path of least surprise).
 */
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
        // Derive the stroke from the *actual* drawn box so a MaterialButton
        // that re-bounds us (icon size attribute) keeps the spec's 6/64 ratio.
        val side = minOf(b.width(), b.height()).toFloat()
        val stroke = side * (6f / 64f)
        strokePaint.strokeWidth = stroke
        // Inset by half-stroke so the ring renders fully inside the bounds.
        val inset = stroke / 2f
        rect.set(
            b.left + inset,
            b.top + inset,
            b.right - inset,
            b.bottom - inset,
        )
        // Background ring: full circle at 25% alpha.
        strokePaint.alpha = (0.25f * 255f).toInt()
        canvas.drawArc(rect, 0f, 360f, false, strokePaint)
        // Foreground arc: 50/138 of the circumference, rotating.
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
        // Tinting is supported so MaterialButton's iconTint can recolour the
        // spinner — when null, the spec colour from colors.xml remains.
        val tinted = tint?.defaultColor ?: color
        strokePaint.color = tinted
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (!animator.isStarted) animator.start()
    }

    override fun stop() {
        if (animator.isStarted) animator.cancel()
    }

    override fun isRunning(): Boolean = animator.isRunning

    /**
     * When the host View detaches (RecyclerView recycle, removeAllViews, etc.)
     * the framework calls setVisible(false). Stop the animator there so it
     * doesn't keep ticking — and a strong reference to this drawable —
     * inside the global AnimationHandler after the View is gone.
     */
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

// ─── Dots ───────────────────────────────────────────────────────────────

/**
 * Three pulsing circles. Spec reference: `LoaderDots`.
 *
 * Each dot oscillates in opacity (0.25 ↔ 1) and radius (4 ↔ 6 design units)
 * on a 1.2 s cycle, staggered 0.18 s between dots. The original SMIL
 * `<animate values="A;B;A" dur="1.2s">` is a linear interpolation that
 * produces a triangle wave — mirrored here directly so the visual matches.
 *
 * Centres sit at design-x 16 / 32 / 48 (spacing of 16 units between centres).
 */
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
        val scale = side / 64f // design-units → px
        // Stagger per dot: 0.18 s of the 1.2 s loop = 0.15 of a full cycle.
        val staggerFraction = 0.18f / 1.2f
        for (i in 0..2) {
            val dotPhase = ((phase + i * staggerFraction) % 1f).let { if (it < 0f) it + 1f else it }
            // Triangle wave: 0 at the edges, 1 at the midpoint.
            val tri = 1f - abs(dotPhase - 0.5f) * 2f
            val opacity = 0.25f + 0.75f * tri // 0.25 → 1
            val r = scale * (4f + 2f * tri) // 4 → 6 design-units
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

    @Suppress("DEPRECATION")
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

// ─── Bar ────────────────────────────────────────────────────────────────

/**
 * Indeterminate horizontal bar — an 80-unit-wide highlight slides across a
 * 240-unit track on a 1.4 s linear cycle. Spec reference: `LoaderBar`.
 *
 * The slider starts off-screen at `x = -80` and ends off-screen at `x = 240`,
 * so the bright pip animates fully through the visible area. Track sits at
 * 22% alpha, slider at 100%; both have rounded caps with radius = half track
 * height (rx="4" on an 8-unit-tall rect).
 *
 * The drawable's intrinsic dimensions are the [widthPx] passed in and the
 * design-correct height ratio (16 / 240 of width). Callers wanting a custom
 * height can call [setBounds] directly.
 */
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
        // The spec's track is 240 wide × 8 tall (with 4-px corner radius), drawn
        // inside a 240 × 16 viewBox vertically centred. Mirror that geometry by
        // anchoring the track to the bounds' vertical centre and giving it a
        // height = w × 8/240 (proportional, like the spec).
        val trackHeight = w * (8f / 240f)
        val sliderWidth = w * (80f / 240f)
        val cy = b.exactCenterY()
        val top = cy - trackHeight / 2f
        val bottom = cy + trackHeight / 2f
        val rx = trackHeight / 2f
        trackRect.set(b.left.toFloat(), top, b.right.toFloat(), bottom)
        canvas.drawRoundRect(trackRect, rx, rx, trackPaint)
        // Slider x ∈ [-sliderWidth, w] over the cycle → enters from the left
        // edge and exits past the right edge cleanly.
        val x = -sliderWidth + (w + sliderWidth) * phase
        sliderRect.set(b.left + x, top, b.left + x + sliderWidth, bottom)
        // Clip to the track so the slider doesn't visually spill past the
        // rounded ends — matches the SVG's container behaviour.
        val save = canvas.save()
        canvas.clipRect(trackRect)
        canvas.drawRoundRect(sliderRect, rx, rx, sliderPaint)
        canvas.restoreToCount(save)
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

    @Suppress("DEPRECATION")
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
