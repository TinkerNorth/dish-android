// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainScreenFactsTest {
    private fun summary(live: LinkState) =
        ConnectionSummary(
            id = "s:${live.name}",
            kind = ConnectionKind.SATELLITE,
            label = "PC",
            detail = "",
            live = live,
            boundSlotIds = emptyList(),
        )

    @Test
    fun `no connections reads tap to manage`() {
        assertEquals(ConnectionsSummary.TapToManage, connectionsSummary(emptyList()))
    }

    @Test
    fun `remembered hosts with none online are counted as remembered`() {
        val summary = connectionsSummary(listOf(summary(LinkState.Saved), summary(LinkState.Connecting)))
        assertEquals(ConnectionsSummary.Remembered(totalCount = 2), summary)
    }

    @Test
    fun `online hosts read as x of y`() {
        val summary = connectionsSummary(listOf(summary(LinkState.Connected), summary(LinkState.Saved)))
        assertEquals(ConnectionsSummary.ConnectedOf(liveCount = 1, totalCount = 2), summary)
    }

    @Test
    fun `an unstable link is still online`() {
        assertEquals(
            ConnectionsSummary.ConnectedOf(liveCount = 1, totalCount = 1),
            connectionsSummary(listOf(summary(LinkState.Unstable))),
        )
    }

    @Test
    fun `a failed native load outranks the welcome gate`() {
        assertEquals(
            DashboardRedirect.NATIVE_UNAVAILABLE,
            dashboardRedirect(nativeLoadFailed = true, welcomeCompleted = false),
        )
        assertEquals(
            DashboardRedirect.NATIVE_UNAVAILABLE,
            dashboardRedirect(nativeLoadFailed = true, welcomeCompleted = true),
        )
    }

    @Test
    fun `a first run is sent to setup`() {
        assertEquals(DashboardRedirect.SETUP, dashboardRedirect(nativeLoadFailed = false, welcomeCompleted = false))
    }

    @Test
    fun `a welcomed user with native code stays on the dashboard`() {
        assertNull(dashboardRedirect(nativeLoadFailed = false, welcomeCompleted = true))
    }
}
