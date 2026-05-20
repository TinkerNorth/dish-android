// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.tinkernorth.dish.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Renders [DishNotificationQueue] emissions as Material [Snackbar]s anchored
 * to the activity's content view. The platform-native idiom for actionable
 * transient messages — slide-in from below, swipe-to-dismiss (via the
 * CoordinatorLayout the activity roots into), built-in queueing, single
 * source of accessibility behaviour.
 *
 * Threading: every method must be called on the main thread. The queue's
 * collectors are gated on `repeatOnLifecycle(STARTED)` so they only run while
 * the host activity is foreground.
 */
class DishSnackbarController(
    /** The view Snackbar.make() walks up from to find a CoordinatorLayout. */
    private val rootView: View,
) {
    /**
     * Optional view the Snackbar should sit above. Toggled by the host
     * activity when the [com.tinkernorth.dish.util.LowPowerManager]
     * countdown pill engages — without an anchor the Snackbar would draw
     * over the countdown.
     */
    var anchorView: View? = null

    private val byKey = HashMap<String, Snackbar>()
    private val byId = HashMap<Long, Snackbar>()
    private var collectJob: Job? = null

    /**
     * Subscribe to [queue.posts] + [queue.dismissals] while [owner] is in
     * STARTED. Idempotent: rebinding cancels the prior collector.
     */
    fun bindLifecycle(
        owner: LifecycleOwner,
        queue: DishNotificationQueue,
    ) {
        collectJob?.cancel()
        collectJob =
            owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { queue.posts.onEach(::show).launchIn(this) }
                    launch { queue.dismissals.onEach(::dismiss).launchIn(this) }
                }
            }
    }

    /** Render [notification] as a Material Snackbar. */
    fun show(notification: DishNotification) {
        // Same-key replacement: dismiss the prior holder before queuing the
        // new one. Without this a state-driven notification (e.g. "BT off")
        // would queue multiple copies on each state re-emit.
        notification.key?.let { byKey[it]?.dismiss() }

        val ctx = rootView.context
        val snackbar =
            Snackbar
                .make(rootView, buildStyledText(ctx, notification), durationFor(notification))
                .applyDishTheme(notification.severity)
        anchorView?.let { snackbar.anchorView = it }
        notification.action?.let { action ->
            snackbar.setAction(action.label) { action.handler() }
        }
        snackbar.addCallback(
            object : Snackbar.Callback() {
                override fun onDismissed(
                    transientBottomBar: Snackbar,
                    event: Int,
                ) {
                    notification.key?.let { k -> if (byKey[k] === transientBottomBar) byKey.remove(k) }
                    byId.remove(notification.id)
                }
            },
        )
        notification.key?.let { byKey[it] = snackbar }
        byId[notification.id] = snackbar

        snackbar.show()
    }

    /** Programmatic dismissal — silently no-op if the id has already been dismissed. */
    fun dismiss(id: Long) {
        byId[id]?.dismiss()
    }

    private fun durationFor(notification: DishNotification): Int =
        when {
            notification.durationMs == DishNotification.DURATION_PERSISTENT ->
                Snackbar.LENGTH_INDEFINITE
            notification.durationMs >= DishNotification.DURATION_LONG ->
                Snackbar.LENGTH_LONG
            else -> Snackbar.LENGTH_SHORT
        }
}

// ── Brand theming ───────────────────────────────────────────────────────────

/**
 * Style the Snackbar's content text as a two-tier message:
 *   - title in bold, full text color
 *   - body in a smaller monospace, muted color
 *
 * Material's default snackbar renders raw text; without this the banner
 * reads as a single-line grey blob with no visual hierarchy between the
 * imperative title and the supporting detail.
 */
private fun buildStyledText(
    ctx: Context,
    notification: DishNotification,
): CharSequence {
    val builder = SpannableStringBuilder()
    val titleStart = builder.length
    builder.append(notification.title)
    builder.setSpan(
        StyleSpan(Typeface.BOLD),
        titleStart,
        builder.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    val body = notification.body
    if (!body.isNullOrBlank()) {
        builder.append("\n")
        val bodyStart = builder.length
        builder.append(body)
        builder.setSpan(
            ForegroundColorSpan(ctx.getColor(R.color.colorMuted)),
            bodyStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            AbsoluteSizeSpan(spToPx(ctx, BODY_SP)),
            bodyStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            TypefaceSpan("monospace"),
            bodyStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return builder
}

/**
 * Apply the v6 deep-space cyan theme to a Snackbar: dark pill surface with
 * a severity-colored left rail (mirrors the leading-accent treatment from
 * the original custom host while keeping platform-native dismiss/animation
 * behaviour), monospace-leaning typography, cyan action label.
 *
 * Material 3's default Snackbar uses an "inverse surface" tint which would
 * read as a light pill on our dark theme — explicitly setting the
 * background drawable keeps visual continuity with bg_pill, the dim
 * overlay, and every other floating chrome element in the app.
 */
private fun Snackbar.applyDishTheme(severity: DishNotification.Severity): Snackbar {
    val ctx = view.context
    // Custom background: rounded surface + leading severity rail. Replaces
    // Snackbar's default flat-tinted background.
    view.background = buildBackground(ctx, severity)
    // Material gives the Snackbar a default 6dp inset/elevation; keep that
    // but lift the rail-bearing pill a hair so it visually separates from
    // the content beneath.
    view.elevation = dpToPx(ctx, ELEVATION_DP)
    // Inset slightly from the edges so the rounded corners breathe and the
    // pill doesn't sit flush against the screen edge.
    val horizontalPad = dpToPxInt(ctx, HORIZONTAL_PAD_DP)
    val verticalPad = dpToPxInt(ctx, VERTICAL_PAD_DP)
    view.setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)
    // Text typography: bold title (set via SpannableString), white-on-dark.
    setTextColor(ctx.getColor(R.color.colorOnSurface))
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
        maxLines = MAX_TEXT_LINES
        textSize = TITLE_SP
        // Title typeface is overridden by the SpannableString's StyleSpan;
        // setting it here is a guard for the no-body case where the entire
        // text is the title.
        typeface = Typeface.DEFAULT_BOLD
        // Indent text past the severity rail so the rail has space to read.
        setPadding(dpToPxInt(ctx, TEXT_LEADING_INDENT_DP), 0, 0, 0)
    }
    // Action label: severity-tinted so ERROR / WARN actions visually pop.
    val actionColor =
        when (severity) {
            DishNotification.Severity.INFO,
            DishNotification.Severity.SUCCESS,
            -> R.color.colorPrimary
            DishNotification.Severity.WARN -> R.color.colorWarning
            DishNotification.Severity.ERROR -> R.color.colorError
        }
    setActionTextColor(ctx.getColor(actionColor))
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_action)?.apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = ACTION_SP
        letterSpacing = ACTION_LETTER_SPACING
    }
    return this
}

/**
 * The two-layer background drawable: a rounded surface pill (matches
 * `bg_pill.xml`), with a 4dp wide rail of the severity color anchored to
 * the leading edge. Drawn as a LayerDrawable so the rail floats on top of
 * the pill without needing custom view inflation.
 */
private fun buildBackground(
    ctx: Context,
    severity: DishNotification.Severity,
): Drawable {
    val surface =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(ctx, CORNER_RADIUS_DP)
            setColor(ctx.getColor(R.color.colorSurface))
            setStroke(dpToPxInt(ctx, 1f), ctx.getColor(R.color.colorOutline))
        }
    val railColorRes =
        when (severity) {
            DishNotification.Severity.INFO -> R.color.colorPrimary
            DishNotification.Severity.SUCCESS -> R.color.colorSuccess
            DishNotification.Severity.WARN -> R.color.colorWarning
            DishNotification.Severity.ERROR -> R.color.colorError
        }
    val rail =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ctx.getColor(railColorRes))
            // Round only the leading corners so the rail follows the pill's
            // curvature on the left edge.
            val r = dpToPx(ctx, CORNER_RADIUS_DP)
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
    val layers = LayerDrawable(arrayOf(surface, rail))
    layers.setLayerWidth(1, dpToPxInt(ctx, RAIL_WIDTH_DP))
    layers.setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
    return layers
}

private fun dpToPx(
    ctx: Context,
    dp: Float,
): Float = dp * ctx.resources.displayMetrics.density

private fun dpToPxInt(
    ctx: Context,
    dp: Float,
): Int = dpToPx(ctx, dp).toInt()

private fun spToPx(
    ctx: Context,
    sp: Float,
): Int = (sp * ctx.resources.displayMetrics.scaledDensity).toInt()

// ── Styling constants ──────────────────────────────────────────────────────
//
// Pulled out of the apply function so a designer can sweep here without
// hunting through layout code. All numbers are dp / sp; conversion to px
// happens at the call site via the helpers above.

private const val CORNER_RADIUS_DP = 10f
private const val RAIL_WIDTH_DP = 4f
private const val ELEVATION_DP = 6f
private const val HORIZONTAL_PAD_DP = 4f
private const val VERTICAL_PAD_DP = 2f
private const val TEXT_LEADING_INDENT_DP = 12f
private const val TITLE_SP = 13f
private const val BODY_SP = 11f
private const val ACTION_SP = 12f
private const val ACTION_LETTER_SPACING = 0.04f

/** Title (1 line) + body (up to 2 lines) — Material default is 2 total. */
private const val MAX_TEXT_LINES = 3
