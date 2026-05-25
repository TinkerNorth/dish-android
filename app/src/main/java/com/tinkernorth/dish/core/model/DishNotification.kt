// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import androidx.annotation.DrawableRes

// Construct via DishNotifications.post (not directly) so id and timestamp are assigned centrally.
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

    // handler runs on main thread; should be idempotent (fast double-tap can fire twice).
    data class Action(
        val label: String,
        val handler: () -> Unit,
    )

    companion object {
        const val DURATION_PERSISTENT: Long = 0L

        const val DURATION_SHORT: Long = 3_500L

        const val DURATION_LONG: Long = 6_000L
    }
}
