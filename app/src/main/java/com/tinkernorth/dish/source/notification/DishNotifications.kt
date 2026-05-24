// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.notification

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.model.DishNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped notification bus + per-Activity Snackbar renderer in one class.
 *
 * Replaces the prior `DishNotificationQueue` + `DishSnackbarController` split.
 * The two had identical lifetimes (every Activity that observed the queue also
 * spun up a controller) and the boundary between them was leaking through every
 * Activity's onCreate. Merging them collapses ~10 lines of per-Activity
 * boilerplate to one [attach] call.
 *
 * **Decoupling preserved.** The class subscribes to nothing on its own — emitters
 * (`SatelliteConnectionManager`, etc.) call [post] / [error] / etc. directly,
 * exactly as they did against the old queue. The merge does *not* turn this
 * into a god-mouth that imports every data-layer flow; that boundary stays.
 *
 * **Testability preserved.** The data-flow surface (posts, dismissals, same-key
 * dedup, monotonic ids) is independent of any View work. Rendering goes through
 * a swappable [Renderer]; tests pass a recording fake. The default renderer
 * (`MaterialSnackbarRenderer`) is the only place View / Snackbar APIs are
 * touched.
 *
 * **One-shot, no replay.** Posts go to a `replay = 0` SharedFlow. An Activity
 * STARTED at emit time renders the banner; an Activity in transition (gap
 * between A.onStop and B.onStart) misses it. This matches the prior queue's
 * behaviour and is intentional — state-driven UX persists via re-derivation
 * from upstream StateFlows, not by replaying old notifications.
 *
 * **Same-key replacement.** Two posts with the same non-null `key` cause the
 * prior live Snackbar to dismiss before the new one shows. Lives on the render
 * side because dedup requires tracking live `Renderer.Handle`s.
 */
@Singleton
class DishNotifications
    @Inject
    constructor() {
        // ── Posting API (pure SharedFlow surface; no Android types) ──────────

        private val _posts =
            MutableSharedFlow<DishNotification>(
                replay = 0,
                extraBufferCapacity = BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val posts: SharedFlow<DishNotification> = _posts.asSharedFlow()

        private val _dismissals =
            MutableSharedFlow<Long>(
                replay = 0,
                extraBufferCapacity = BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val dismissals: SharedFlow<Long> = _dismissals.asSharedFlow()

        private val nextId = AtomicLong(1L)

        /**
         * Post a notification to the bus. The id is assigned and returned so a
         * caller can later [dismiss] it (e.g. when the underlying state clears
         * before the auto-dismiss fires). Posts with the same non-null [key]
         * replace prior posts.
         */
        fun post(
            severity: DishNotification.Severity = DishNotification.Severity.INFO,
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = defaultDurationFor(severity),
        ): Long {
            val id = nextId.getAndIncrement()
            val n =
                DishNotification(
                    id = id,
                    severity = severity,
                    title = title,
                    body = body,
                    glyph = glyph,
                    action = action,
                    key = key,
                    durationMs = durationMs,
                )
            _posts.tryEmit(n)
            return id
        }

        /** Dismiss a previously-posted notification by id. No-op if already gone. */
        fun dismiss(id: Long) {
            _dismissals.tryEmit(id)
        }

        // ── Convenience builders ────────────────────────────────────────────

        /** Short transient banner. */
        fun info(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_SHORT,
        ): Long = post(DishNotification.Severity.INFO, title, body, glyph, action, key, durationMs)

        /** Short transient success banner. */
        fun success(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_SHORT,
        ): Long = post(DishNotification.Severity.SUCCESS, title, body, glyph, action, key, durationMs)

        /** Warning banner. Auto-dismisses after DURATION_LONG by default; opt in to PERSISTENT. */
        fun warn(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_LONG,
        ): Long = post(DishNotification.Severity.WARN, title, body, glyph, action, key, durationMs)

        /** Error banner. Same duration semantics as [warn]. */
        fun error(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_LONG,
        ): Long = post(DishNotification.Severity.ERROR, title, body, glyph, action, key, durationMs)

        // ── Attachment + rendering ──────────────────────────────────────────

        /**
         * Production attach: call once in `Activity.onCreate(...)`.
         *
         * Wires a [MaterialSnackbarRenderer] against [rootView], subscribes to
         * the posts/dismissals flows while the owner is STARTED, and cleans up
         * on DESTROYED. The returned [Attachment] holds the per-Activity
         * anchor view (used by `GamepadActivityHost` to lift Snackbars above
         * the low-power countdown pill).
         */
        fun attach(
            owner: LifecycleOwner,
            rootView: View,
        ): Attachment = attachWithRenderer(owner, MaterialSnackbarRenderer(rootView), owner.lifecycleScope)

        /**
         * Test seam: attach with a custom renderer + scope. Production code
         * should use [attach]; this overload exists so unit tests can drive the
         * lifecycle without an Android View tree.
         */
        fun attachWithRenderer(
            owner: LifecycleOwner,
            renderer: Renderer,
            scope: CoroutineScope = owner.lifecycleScope,
        ): Attachment {
            val attachment = Attachment(renderer)

            scope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // posts → render, dedup by key, track for dismiss-by-id
                    launch {
                        posts.onEach { n -> attachment.handlePost(n) }.launchIn(this)
                    }
                    // dismissals → dismiss the live handle if we still have one
                    launch {
                        dismissals.onEach { id -> attachment.handleDismiss(id) }.launchIn(this)
                    }
                }
            }

            owner.lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onDestroy(o: LifecycleOwner) {
                        attachment.dismissAll()
                    }
                },
            )

            return attachment
        }

        // ── Internals ───────────────────────────────────────────────────────

        /**
         * Severity-default duration. Every severity auto-dismisses by default;
         * persistent banners are explicit opt-in. Prior default of "WARN/ERROR
         * are persistent" caused transient failures like "satellite isn't
         * responding" to stay on screen forever.
         */
        private fun defaultDurationFor(severity: DishNotification.Severity): Long =
            when (severity) {
                DishNotification.Severity.INFO,
                DishNotification.Severity.SUCCESS,
                -> DishNotification.DURATION_SHORT
                DishNotification.Severity.WARN,
                DishNotification.Severity.ERROR,
                -> DishNotification.DURATION_LONG
            }

        // ── Public interfaces ───────────────────────────────────────────────

        /**
         * The View-touching boundary. The production implementation is
         * [MaterialSnackbarRenderer]; tests swap a recording fake.
         */
        interface Renderer {
            fun show(
                notification: DishNotification,
                anchor: View?,
            ): Handle

            interface Handle {
                fun dismiss()
            }
        }

        /**
         * Returned by [attach]. Holds per-Activity rendering state (live
         * Snackbar handles, dedup map, anchor view). The anchor view setter is
         * exposed for `GamepadActivityHost`, which lifts the Snackbar above
         * the low-power countdown pill while it's visible.
         *
         * Internally tracks live handles by id and by key for dedup. Cleared
         * on `onDestroy`.
         */
        class Attachment internal constructor(
            private val renderer: Renderer,
        ) {
            @Volatile var anchorView: View? = null

            // Single source of truth: byId maps id → handle. byKey is a side
            // index mapping key → id so we can look up the live id for a key
            // in O(1). Any mutation that touches one map must touch the other
            // to keep them coherent — that's why both are guarded by [lock].
            private val byId = mutableMapOf<Long, Renderer.Handle>()
            private val byKey = mutableMapOf<String, Long>()
            private val lock = Any()

            internal fun handlePost(notification: DishNotification) {
                // Same-key replacement: pull both the prior id (from byKey)
                // and its handle (from byId) so neither map is left holding a
                // stale entry. Earlier this only dropped byKey, which left
                // byId tracking the dismissed handle and made liveById
                // over-count.
                val priorHandle: Renderer.Handle? =
                    synchronized(lock) {
                        val key = notification.key
                        if (key != null) {
                            val priorId = byKey.remove(key)
                            if (priorId != null) byId.remove(priorId) else null
                        } else {
                            null
                        }
                    }
                priorHandle?.dismiss()

                val handle = renderer.show(notification, anchorView)

                synchronized(lock) {
                    byId[notification.id] = handle
                    notification.key?.let { byKey[it] = notification.id }
                }
            }

            internal fun handleDismiss(id: Long) {
                val handle: Renderer.Handle? =
                    synchronized(lock) {
                        val h = byId.remove(id)
                        if (h != null) {
                            // Clear the side index too. removeAll is safe even
                            // when no entry exists for this id (null key).
                            byKey.entries.removeAll { it.value == id }
                        }
                        h
                    }
                handle?.dismiss()
            }

            internal fun dismissAll() {
                val toDismiss: List<Renderer.Handle> =
                    synchronized(lock) {
                        val snapshot = byId.values.toList()
                        byId.clear()
                        byKey.clear()
                        snapshot
                    }
                toDismiss.forEach { it.dismiss() }
            }

            /** Visible for testing: how many handles are currently tracked by id. */
            internal fun liveById(): Int = synchronized(lock) { byId.size }

            /** Visible for testing: how many handles are currently tracked by key. */
            internal fun liveByKey(): Int = synchronized(lock) { byKey.size }
        }

        private companion object {
            const val BUFFER = 16
        }
    }

// ── Material renderer (default) ─────────────────────────────────────────────
//
// The production implementation of [DishNotifications.Renderer]. Builds a
// Material [Snackbar] for each post, styled with the brand cyan/severity rail
// and monospace body. Kept in this file so the renderer's lifetime is obviously
// the same as the queue's and to avoid an extra file for what's essentially
// "the Snackbar build helper."

internal class MaterialSnackbarRenderer(
    private val rootView: View,
) : DishNotifications.Renderer {
    override fun show(
        notification: DishNotification,
        anchor: View?,
    ): DishNotifications.Renderer.Handle {
        val ctx = rootView.context
        val snackbar =
            Snackbar
                .make(rootView, buildStyledText(ctx, notification), durationFor(notification))
                .applyDishTheme(notification.severity)
        anchor?.let { snackbar.anchorView = it }
        notification.action?.let { action ->
            snackbar.setAction(action.label) { action.handler() }
        }
        snackbar.show()
        return SnackbarHandle(snackbar)
    }

    private fun durationFor(notification: DishNotification): Int =
        when {
            notification.durationMs == DishNotification.DURATION_PERSISTENT ->
                Snackbar.LENGTH_INDEFINITE
            notification.durationMs >= DishNotification.DURATION_LONG ->
                Snackbar.LENGTH_LONG
            else -> Snackbar.LENGTH_SHORT
        }

    private class SnackbarHandle(
        private val snackbar: Snackbar,
    ) : DishNotifications.Renderer.Handle {
        override fun dismiss() {
            snackbar.dismiss()
        }
    }
}

// ── Brand theming (unchanged from the prior DishSnackbarController) ──────────

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
            AbsoluteSizeSpan(ctx.resources.getDimensionPixelSize(R.dimen.notification_text_body)),
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

private fun Snackbar.applyDishTheme(severity: DishNotification.Severity): Snackbar {
    val ctx = view.context
    val res = ctx.resources
    view.setBackgroundResource(backgroundForSeverity(severity))
    // The M3 Snackbar style (Widget.Material3.Snackbar, inherited from
    // Theme.Material3) applies backgroundTint=colorInverseSurface — M3's
    // high-contrast "inverse" colour that's LIGHT on dark themes — to
    // whatever drawable sits on the snackbar view. Without clearing the
    // tint here, our dark notification_bg_<severity>.xml layer-list gets
    // its colorSurface fill re-tinted to the M3 inverse light colour, so
    // the toast paints light instead of the intended dark navy. Setting
    // the tint list to null disables the tint operation entirely and the
    // drawable's own solid colours render through.
    view.backgroundTintList = null
    view.elevation = res.getDimension(R.dimen.notification_elevation)
    val horizontalPad = res.getDimensionPixelSize(R.dimen.notification_padding_horizontal)
    val verticalPad = res.getDimensionPixelSize(R.dimen.notification_padding_vertical)
    view.setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)
    setTextColor(ctx.getColor(R.color.colorOnSurface))
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
        maxLines = MAX_TEXT_LINES
        // setTextSize(COMPLEX_UNIT_PX, …) consumes the already-scaled value
        // getDimension returns for an sp dimen (density × fontScale × value),
        // so this lands on the user's actual sp size without the textSize=Sp
        // implicit conversion that would re-scale.
        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.notification_text_title))
        typeface = Typeface.DEFAULT_BOLD
        setPadding(res.getDimensionPixelSize(R.dimen.notification_text_leading_indent), 0, 0, 0)
    }
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
        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.notification_text_action))
        letterSpacing = ACTION_LETTER_SPACING
    }
    return this
}

/**
 * Pick the severity-keyed layer-list drawable that paints the Snackbar's
 * rounded surface + leading colour rail. Each severity has its own static
 * XML resource (`notification_bg_info / _success / _warn / _error`) so the
 * surface shape, stroke, and rail width live in one place instead of being
 * rebuilt as a `LayerDrawable(GradientDrawable(), GradientDrawable())` per
 * notification.
 */
@androidx.annotation.DrawableRes
private fun backgroundForSeverity(severity: DishNotification.Severity): Int =
    when (severity) {
        DishNotification.Severity.INFO -> R.drawable.notification_bg_info
        DishNotification.Severity.SUCCESS -> R.drawable.notification_bg_success
        DishNotification.Severity.WARN -> R.drawable.notification_bg_warn
        DishNotification.Severity.ERROR -> R.drawable.notification_bg_error
    }

private const val ACTION_LETTER_SPACING = 0.04f

/** Title (1 line) + body (up to 2 lines) — Material default is 2 total. */
private const val MAX_TEXT_LINES = 3
