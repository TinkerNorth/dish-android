// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionSummary

// The one line under the connections heading. Unstable links are still streaming, so they
// count as online here just as on the connections screen; the plural selects on the total.
internal sealed interface ConnectionsSummary {
    data object TapToManage : ConnectionsSummary

    data class Remembered(
        val totalCount: Int,
    ) : ConnectionsSummary

    data class ConnectedOf(
        val liveCount: Int,
        val totalCount: Int,
    ) : ConnectionsSummary
}

internal fun connectionsSummary(connections: List<ConnectionSummary>): ConnectionsSummary {
    val liveCount = connections.count { it.live.isLiveLink() }
    val totalCount = connections.size
    return when {
        totalCount == 0 -> ConnectionsSummary.TapToManage
        liveCount == 0 -> ConnectionsSummary.Remembered(totalCount)
        else -> ConnectionsSummary.ConnectedOf(liveCount, totalCount)
    }
}

internal enum class DashboardRedirect { NATIVE_UNAVAILABLE, SETUP }

// Where the dashboard hands off instead of drawing itself; null means it stays. A failed
// native load outranks the welcome gate, since GameActivity loads native code on touch and
// the fallback has to be chosen before the JNI surface is hit.
internal fun dashboardRedirect(
    nativeLoadFailed: Boolean,
    welcomeCompleted: Boolean,
): DashboardRedirect? =
    when {
        nativeLoadFailed -> DashboardRedirect.NATIVE_UNAVAILABLE
        !welcomeCompleted -> DashboardRedirect.SETUP
        else -> null
    }
