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
 * Process-scoped notification bus. Activities collect [stream] and emissions
 * appear as a banner on whichever activity is foreground. The default buffer
 * survives a one-activity transition so a notification emitted while
 * MainActivity stops on the way to ConnectionsActivity reaches the new
 * activity instead of being silently dropped.
 *
 * Posters call [post] (or the convenience variants in [DishNotify]); the queue
 * stamps an id and a creation timestamp, then emits onto the SharedFlow. The
 * dismissal channel is a second SharedFlow so the host can react to programmatic
 * dismissals (replacing a same-key notification, manager.disconnect clearing a
 * pending pair banner, etc.) without re-emitting the original.
 */
@Singleton
class DishNotificationQueue
    @Inject
    constructor() {
        private val _posts =
            MutableSharedFlow<DishNotification>(
                replay = 1,
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

        private fun defaultDurationFor(severity: DishNotification.Severity): Long =
            when (severity) {
                DishNotification.Severity.INFO,
                DishNotification.Severity.SUCCESS,
                -> DishNotification.DURATION_SHORT
                DishNotification.Severity.WARN,
                DishNotification.Severity.ERROR,
                -> DishNotification.DURATION_PERSISTENT
            }

        // ── Convenience builders ──────────────────────────────────────────
        //
        // Severity-specific helpers so callers don't have to spell out the
        // enum on every post() and so the auto-dismiss defaults read
        // consistently. The full builder ([post]) is still available for the
        // rare case that needs to override `durationMs` or `glyph`.

        /** Short transient banner. Auto-dismisses after [DishNotification.DURATION_SHORT]. */
        fun info(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
        ): Long =
            post(
                severity = DishNotification.Severity.INFO,
                title = title,
                body = body,
                glyph = glyph,
                action = action,
                key = key,
            )

        /** Short transient success banner. Auto-dismisses after [DishNotification.DURATION_SHORT]. */
        fun success(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
        ): Long =
            post(
                severity = DishNotification.Severity.SUCCESS,
                title = title,
                body = body,
                glyph = glyph,
                action = action,
                key = key,
            )

        /**
         * Warning banner. Persistent by default — warnings usually carry
         * actionable state ("BT is off", "pairing needed") that should stay
         * up until the user acks or the state recovers.
         */
        fun warn(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
        ): Long =
            post(
                severity = DishNotification.Severity.WARN,
                title = title,
                body = body,
                glyph = glyph,
                action = action,
                key = key,
            )

        /** Error banner. Persistent by default — same reasoning as [warn]. */
        fun error(
            title: String,
            body: String? = null,
            @DrawableRes glyph: Int? = null,
            action: DishNotification.Action? = null,
            key: String? = null,
        ): Long =
            post(
                severity = DishNotification.Severity.ERROR,
                title = title,
                body = body,
                glyph = glyph,
                action = action,
                key = key,
            )
    }
