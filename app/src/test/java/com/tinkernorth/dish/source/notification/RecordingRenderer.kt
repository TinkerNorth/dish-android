// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.notification

import android.view.View
import com.tinkernorth.dish.core.model.DishNotification

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

    val liveHandles get() = handles.filterNot { it.dismissed }

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
