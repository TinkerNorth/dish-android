// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.model

import androidx.annotation.DrawableRes

/**
 * The Dish replacement for [android.widget.Toast]. A theme-styled, top-of-screen,
 * optionally-actionable banner rendered by `DishNotifications.attach(...)` in
 * every activity.
 *
 * Toasts were the original feedback channel; they are bursty, inaccessible,
 * impossible to act on, and visually disconnected from the v6 brand. This type +
 * the host view replace every native Toast in the app.
 *
 * **Pattern position:** this is a pure value type — it lives in `core/model/`
 * because it has no flow shape. The publisher is
 * [com.tinkernorth.dish.source.notification.DishNotifications] in
 * `source/notification/`; that class is the `AbstractStateSource` that owns
 * the bus.
 *
 * Construction goes through `DishNotifications.post` (not the data class
 * directly) so the id and timestamp are assigned centrally.
 *
 * Field rationale:
 *
 *  - [severity] picks the outline / dot color. ERROR / WARN are persistent by
 *    default; INFO / SUCCESS auto-dismiss after a sensible default.
 *  - [title] is the imperative one-line headline (the part TalkBack reads first).
 *    Keep it < ~50 chars so the pill doesn't wrap awkwardly.
 *  - [body] is the optional one-line monospace detail (network ip, error code,
 *    etc.). Hidden when null.
 *  - [glyph] paints a v6 brand glyph on the leading edge; defaults pick a sensible
 *    icon per severity. Pass an explicit drawable to override.
 *  - [action] is the right-edge CTA — the whole point of the redesign. The label is
 *    short (RETRY / SETTINGS / RECONNECT) and the handler runs on the main thread;
 *    tapping it dismisses the notification.
 *  - [key] de-duplicates: two posts with the same non-null key replace each other
 *    rather than stacking. Use for state-driven feedback (e.g. "wifi is off" should
 *    never queue twice if the user backgrounds + foregrounds).
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
     * Tap target inside the banner. [label] is shown as an outlined button at the
     * right of the row; [handler] runs on the main thread and the notification
     * auto-dismisses after the tap. The handler should be idempotent: a fast
     * double-tap can fire it twice before dismissal lands.
     */
    data class Action(
        val label: String,
        val handler: () -> Unit,
    )

    companion object {
        /** Stays up until `DishNotifications.dismiss` or a same-key replacement. */
        const val DURATION_PERSISTENT: Long = 0L

        /** Roughly Toast.LENGTH_SHORT equivalent. */
        const val DURATION_SHORT: Long = 3_500L

        /** Roughly Toast.LENGTH_LONG equivalent. */
        const val DURATION_LONG: Long = 6_000L
    }
}
