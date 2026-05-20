// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import androidx.annotation.DrawableRes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Dish replacement for [android.widget.Toast]. A theme-styled,
 * top-of-screen, optionally-actionable banner rendered by
 * [DishNotificationHost] in every activity.
 *
 * Toasts were the original feedback channel; they are bursty, inaccessible,
 * impossible to act on, and visually disconnected from the v6 brand. This
 * type + the host view replace every native Toast in the app.
 *
 * Construction goes through [DishNotificationQueue.post] (not the data class
 * directly) so the id and timestamp are assigned centrally.
 *
 * Field rationale:
 *
 *  - [severity] picks the outline / dot color. ERROR / WARN are persistent by
 *    default; INFO / SUCCESS auto-dismiss after [defaultDurationMs].
 *  - [title] is the imperative one-line headline (the part TalkBack reads
 *    first). Keep it < ~50 chars so the pill doesn't wrap awkwardly.
 *  - [body] is the optional one-line monospace detail (network ip, error
 *    code, etc.). Hidden when null.
 *  - [glyph] paints a v6 brand glyph on the leading edge; defaults pick a
 *    sensible icon per severity. Pass an explicit drawable to override.
 *  - [action] is the right-edge CTA — the whole point of the redesign. The
 *    label is short (RETRY / SETTINGS / RECONNECT) and the handler runs on
 *    the main thread; tapping it dismisses the notification.
 *  - [key] de-duplicates: two posts with the same non-null key replace each
 *    other rather than stacking. Use for state-driven feedback (e.g. "wifi
 *    is off" should never queue twice if the user backgrounds + foregrounds).
 *  - [durationMs] is in ms; pass [DURATION_PERSISTENT] for stay-until-dismissed,
 *    or one of [DURATION_SHORT] / [DURATION_LONG] for auto-dismiss.
 */
@ConsistentCopyVisibility
data class DishNotification internal constructor(
    val id: Long,
    val severity: Severity,
    val title: String,
    val body: String?,
    @param:DrawableRes val glyph: Int?,
    val action: Action?,
    val key: String?,
    val durationMs: Long,
) {
    enum class Severity { INFO, SUCCESS, WARN, ERROR }

    /**
     * Tap target inside the banner. [label] is shown as an outlined button at
     * the right of the row; [handler] runs on the main thread and the
     * notification auto-dismisses after the tap. The handler should be
     * idempotent: a fast double-tap can fire it twice before dismissal lands.
     */
    data class Action(
        val label: String,
        val handler: () -> Unit,
    )

    companion object {
        /** Stays up until [DishNotificationQueue.dismiss] or a same-key replacement. */
        const val DURATION_PERSISTENT: Long = 0L

        /** Roughly Toast.LENGTH_SHORT equivalent. */
        const val DURATION_SHORT: Long = 3_500L

        /** Roughly Toast.LENGTH_LONG equivalent. */
        const val DURATION_LONG: Long = 6_000L
    }
}

/**
 * Process-scoped notification bus. Activities collect [posts] and emissions
 * appear as a banner on whichever activity is foreground.
 *
 * **One-shot semantics — no replay.** A notification fires for whichever
 * activity is collecting at emit time and is then gone. Activity-switch
 * does NOT re-show it (the previous replay=1 behaviour ricocheted the same
 * banner across every activity transition — exactly the bug the user
 * flagged). Persistent state-driven UX (Bluetooth off, KEY_MISSING, etc.)
 * is now driven by activity-side observers of the underlying StateFlows,
 * so each new activity binds, observes the live state, and re-derives its
 * own banner — same outcome the user sees, but the queue is no longer
 * involved in cross-activity persistence.
 *
 * Posters call [post] (or the convenience variants); the queue stamps a
 * monotonic id and emits onto the SharedFlow. The dismissal channel is a
 * second SharedFlow so the controller can react to programmatic
 * dismissals (same-key replacement, etc.) without re-emitting the original.
 */
@Singleton
class DishNotificationQueue
    @Inject
    constructor() {
        private val _posts =
            MutableSharedFlow<DishNotification>(
                replay = 0,
                extraBufferCapacity = 16,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val posts: SharedFlow<DishNotification> = _posts.asSharedFlow()

        private val _dismissals =
            MutableSharedFlow<Long>(
                replay = 0,
                extraBufferCapacity = 16,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** Programmatic dismissals (e.g. same-key replacement, state cleared). */
        val dismissals: SharedFlow<Long> = _dismissals.asSharedFlow()

        private val nextId = AtomicLong(1L)

        /**
         * Post a notification to the bus. The id is assigned and returned so a
         * caller can later [dismiss] it (e.g. when the underlying state clears
         * before the auto-dismiss fires). Posts with the same non-null [key]
         * replace prior posts — the host treats `key` as the de-duplication
         * axis so state-driven banners ("Wi-Fi off", "BT permission needed")
         * never stack.
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

        /**
         * Severity-default duration. Every severity auto-dismisses by
         * default; persistent banners are explicit opt-in via
         * `durationMs = DURATION_PERSISTENT`. The previous default of
         * "WARN/ERROR are persistent" caused transient failures like
         * "satellite isn't responding" to stay on screen forever — wrong
         * for one-shot errors, right only for state-stuck conditions
         * (BT off, KEY_MISSING) which now opt in explicitly.
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

        // ── Convenience builders ──────────────────────────────────────────
        //
        // Severity-specific helpers so callers don't have to spell out the
        // enum on every post() and so the auto-dismiss defaults read
        // consistently. The full builder ([post]) is still available for the
        // rare case that needs to override `durationMs` or `glyph`.

        /** Short transient banner. */
        fun info(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_SHORT,
        ): Long =
            post(
                severity = DishNotification.Severity.INFO,
                title = title,
                body = body,
                glyph = glyph,
                action = action,
                key = key,
                durationMs = durationMs,
            )

        /** Short transient success banner. */
        fun success(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_SHORT,
        ): Long =
            post(
                severity = DishNotification.Severity.SUCCESS,
                title = title,
                body = body,
                glyph = glyph,
                action = action,
                key = key,
                durationMs = durationMs,
            )

        /**
         * Warning banner. Auto-dismisses after [DishNotification.DURATION_LONG]
         * by default; pass `durationMs = DishNotification.DURATION_PERSISTENT`
         * for state-stuck conditions that should stay until acked.
         */
        fun warn(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_LONG,
        ): Long =
            post(
                severity = DishNotification.Severity.WARN,
                title = title,
                body = body,
                glyph = glyph,
                action = action,
                key = key,
                durationMs = durationMs,
            )

        /** Error banner. Same duration semantics as [warn]. */
        fun error(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
            durationMs: Long = DishNotification.DURATION_LONG,
        ): Long =
            post(
                severity = DishNotification.Severity.ERROR,
                title = title,
                body = body,
                glyph = glyph,
                action = action,
                key = key,
                durationMs = durationMs,
            )
    }
