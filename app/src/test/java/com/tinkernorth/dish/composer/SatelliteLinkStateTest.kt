// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.source.connection.SatelliteSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

// Locks the satellite session -> LinkState mapping so the dashboard and the connections screen can
// never drift on how a Live/Linking/Faltering/Idle satellite is shown.
class SatelliteLinkStateTest {
    @Test
    fun `live is connected`() {
        assertEquals(LinkState.Connected, satelliteLinkState(SatelliteSessionState.Live, isStale = false, isDiscovered = false))
    }

    @Test
    fun `linking is connecting`() {
        assertEquals(LinkState.Connecting, satelliteLinkState(SatelliteSessionState.Linking, isStale = false, isDiscovered = false))
    }

    @Test
    fun `faltering is unstable`() {
        assertEquals(LinkState.Unstable, satelliteLinkState(SatelliteSessionState.Faltering, isStale = false, isDiscovered = false))
    }

    @Test
    fun `idle and stale needs pairing`() {
        assertEquals(LinkState.Stale, satelliteLinkState(SatelliteSessionState.Idle, isStale = true, isDiscovered = false))
    }

    @Test
    fun `idle and discovered is ready`() {
        assertEquals(LinkState.Ready, satelliteLinkState(SatelliteSessionState.Idle, isStale = false, isDiscovered = true))
    }

    @Test
    fun `idle remembered-only is saved`() {
        assertEquals(LinkState.Saved, satelliteLinkState(SatelliteSessionState.Idle, isStale = false, isDiscovered = false))
    }

    @Test
    fun `null state is treated like idle`() {
        assertEquals(LinkState.Saved, satelliteLinkState(null, isStale = false, isDiscovered = false))
    }

    @Test
    fun `stale wins over discovered when idle`() {
        assertEquals(LinkState.Stale, satelliteLinkState(SatelliteSessionState.Idle, isStale = true, isDiscovered = true))
    }

    @Test
    fun `a connected link ignores stale and discovered flags`() {
        assertEquals(LinkState.Connected, satelliteLinkState(SatelliteSessionState.Live, isStale = true, isDiscovered = true))
    }
}
