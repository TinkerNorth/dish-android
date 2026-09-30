// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The one predicate both controller-audio composers gate on: which links carry controller audio at all.
class StreamingSatelliteTest {
    private fun summary(
        kind: ConnectionKind,
        live: LinkState,
    ) = ConnectionSummary(
        id = "c",
        kind = kind,
        label = "c",
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    @Test
    fun `a connected satellite is streaming`() {
        assertTrue(isStreamingSatellite(summary(ConnectionKind.SATELLITE, LinkState.Connected)))
    }

    @Test
    fun `an unstable satellite link still counts as streaming`() {
        assertTrue(isStreamingSatellite(summary(ConnectionKind.SATELLITE, LinkState.Unstable)))
    }

    @Test
    fun `a satellite link that is not up is not streaming`() {
        val down = LinkState.entries - setOf(LinkState.Connected, LinkState.Unstable)
        for (live in down) {
            assertFalse(live.name, isStreamingSatellite(summary(ConnectionKind.SATELLITE, live)))
        }
    }

    @Test
    fun `a moonlight link never streams controller audio`() {
        assertFalse(isStreamingSatellite(summary(ConnectionKind.MOONLIGHT, LinkState.Connected)))
    }

    @Test
    fun `a bluetooth link never streams controller audio`() {
        assertFalse(isStreamingSatellite(summary(ConnectionKind.BLUETOOTH, LinkState.Connected)))
    }

    @Test
    fun `a slot with no summary is not streaming`() {
        assertFalse(isStreamingSatellite(null))
    }
}
