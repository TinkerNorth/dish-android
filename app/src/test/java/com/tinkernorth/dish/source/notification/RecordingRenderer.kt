// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.notification

import android.view.View
import com.tinkernorth.dish.core.model.DishNotification

/**
 * Test-only fake renderer that records every `show` call and the handles it
 * returned. Used by [DishNotificationsAttachmentTest] and
 * [DishNotificationsTransitionTest] to verify the data-flow side of
 * [DishNotifications] without involving real Android Snackbars.
 */
class RecordingRenderer : DishNotifications.Renderer {
    data class Shown(
        val notification: DishNotification,
        val anchor: View?,
    )

    val shown = mutableListOf<Shown>()
    val handles = mutableListOf<RecordingHandle>()

    override fun show(
        notification: DishNotification,
        anchor: View?,
    ): DishNotifications.Renderer.Handle {
        shown += Shown(notification, anchor)
        val handle = RecordingHandle(notification.id)
        handles += handle
        return handle
    }

    /** Handles still tracked as live (not yet dismissed). */
    val liveHandles get() = handles.filterNot { it.dismissed }

    /** Ids of all handles, in show order. */
    val shownIds get() = shown.map { it.notification.id }

    class RecordingHandle(
        val id: Long,
    ) : DishNotifications.Renderer.Handle {
        var dismissed = false
            private set

        override fun dismiss() {
            dismissed = true
        }
    }
}
