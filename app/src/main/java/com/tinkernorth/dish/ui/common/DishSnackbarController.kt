// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.BaseTransientBottomBar
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
 * The custom DishNotificationHost this replaces had three real problems:
 *   1. Non-Android-idiomatic X button (Material guidelines say "swipe").
 *   2. Stacking multiple banners at once is overwhelming + non-standard.
 *   3. Hand-rolled animations and dismissal logic.
 *
 * Snackbar fixes all three by definition. Same-key replacement and
 * programmatic dismissal still flow through the queue, but the rendering,
 * timing, and animation are platform behaviour now.
 *
 * Threading: every method must be called on the main thread. The queue's
 * collectors are gated on `repeatOnLifecycle(STARTED)` so they only run while
 * the host activity is foreground — backgrounded posts replay into the next
 * foreground activity via the queue's replay=1 buffer.
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

        val snackbar =
            Snackbar
                .make(rootView, notification.titleAndBodyText(), durationFor(notification))
                .applyDishTheme(notification.severity)
        anchorView?.let { snackbar.anchorView = it }
        notification.action?.let { action ->
            snackbar.setAction(action.label) {
                action.handler()
                // Snackbar auto-dismisses on action tap (default behaviour),
                // so we don't need to call dismiss() explicitly.
            }
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

    private fun DishNotification.titleAndBodyText(): CharSequence = if (body.isNullOrBlank()) title else "$title\n$body"
}

// ── Brand theming ───────────────────────────────────────────────────────────

/**
 * Apply the v6 deep-space cyan theme to a Snackbar: dark surface background,
 * cream-white text, cyan action label. Severity tints the action label so
 * ERROR / WARN actions visually pop while INFO / SUCCESS stay brand cyan.
 *
 * Material 3's default Snackbar uses an "inverse surface" tint which would
 * read as a light pill on our dark theme — explicitly overriding keeps the
 * visual continuity with bg_pill, the dim overlay, and every other floating
 * chrome element in the app.
 */
private fun Snackbar.applyDishTheme(severity: DishNotification.Severity): Snackbar {
    val ctx = view.context
    setBackgroundTint(ctx.getColor(R.color.colorSurface))
    setTextColor(ctx.getColor(R.color.colorOnSurface))
    val actionColor =
        when (severity) {
            DishNotification.Severity.INFO,
            DishNotification.Severity.SUCCESS,
            -> R.color.colorPrimary
            DishNotification.Severity.WARN -> R.color.colorWarning
            DishNotification.Severity.ERROR -> R.color.colorError
        }
    setActionTextColor(ctx.getColor(actionColor))
    // Two lines of body text need extra room — Material's default cap is 2,
    // which is what we want, but multi-line snackbars still render fine.
    val maxLines = if (view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text) != null) 3 else 2
    view
        .findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
        ?.maxLines = maxLines
    // BaseTransientBottomBar suppresses the animation if the view is not
    // attached yet — Snackbar handles that internally, no work needed here.
    @Suppress("UNUSED_VARIABLE")
    val unused = BaseTransientBottomBar.LENGTH_INDEFINITE
    return this
}
