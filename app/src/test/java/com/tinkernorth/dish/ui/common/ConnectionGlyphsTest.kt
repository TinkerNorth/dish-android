// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionGlyphsTest {
    @Test
    fun `satellite Connected uses ic_satellite_connected`() {
        assertEquals(
            R.drawable.ic_satellite_connected,
            glyphForConnection(ConnectionKind.SATELLITE, LinkState.Connected),
        )
    }

    @Test
    fun `satellite Saved uses ic_satellite_off`() {
        assertEquals(
            R.drawable.ic_satellite_off,
            glyphForConnection(ConnectionKind.SATELLITE, LinkState.Saved),
        )
    }

    @Test
    fun `satellite Stale uses ic_satellite_off (same as Saved)`() {
        assertEquals(
            R.drawable.ic_satellite_off,
            glyphForConnection(ConnectionKind.SATELLITE, LinkState.Stale),
        )
    }

    @Test
    fun `satellite Connecting uses base ic_satellite`() {
        assertEquals(
            R.drawable.ic_satellite,
            glyphForConnection(ConnectionKind.SATELLITE, LinkState.Connecting),
        )
    }

    @Test
    fun `satellite Found uses base ic_satellite`() {
        assertEquals(
            R.drawable.ic_satellite,
            glyphForConnection(ConnectionKind.SATELLITE, LinkState.Found),
        )
    }

    @Test
    fun `satellite Ready uses base ic_satellite`() {
        assertEquals(
            R.drawable.ic_satellite,
            glyphForConnection(ConnectionKind.SATELLITE, LinkState.Ready),
        )
    }

    @Test
    fun `satellite Unstable uses base ic_satellite`() {
        assertEquals(
            R.drawable.ic_satellite,
            glyphForConnection(ConnectionKind.SATELLITE, LinkState.Unstable),
        )
    }

    @Test
    fun `bluetooth Connected uses ic_bluetooth_connected`() {
        assertEquals(
            R.drawable.ic_bluetooth_connected,
            glyphForConnection(ConnectionKind.BLUETOOTH, LinkState.Connected),
        )
    }

    @Test
    fun `bluetooth Connecting uses ic_bluetooth_searching (radar variant)`() {
        assertEquals(
            R.drawable.ic_bluetooth_searching,
            glyphForConnection(ConnectionKind.BLUETOOTH, LinkState.Connecting),
        )
    }

    @Test
    fun `bluetooth Saved uses ic_bluetooth_off`() {
        assertEquals(
            R.drawable.ic_bluetooth_off,
            glyphForConnection(ConnectionKind.BLUETOOTH, LinkState.Saved),
        )
    }

    @Test
    fun `bluetooth Stale uses ic_bluetooth_off (same as Saved)`() {
        assertEquals(
            R.drawable.ic_bluetooth_off,
            glyphForConnection(ConnectionKind.BLUETOOTH, LinkState.Stale),
        )
    }

    @Test
    fun `Connected is green`() {
        assertEquals(R.color.colorSuccess, dotColorForState(LinkState.Connected))
    }

    @Test
    fun `Connecting and Unstable share primary cyan`() {
        assertEquals(R.color.colorPrimary, dotColorForState(LinkState.Connecting))
        assertEquals(R.color.colorPrimary, dotColorForState(LinkState.Unstable))
    }

    @Test
    fun `Stale is warning amber`() {
        assertEquals(R.color.colorWarning, dotColorForState(LinkState.Stale))
    }

    @Test
    fun `Saved Ready and Found collapse to muted`() {
        assertEquals(R.color.colorMuted, dotColorForState(LinkState.Saved))
        assertEquals(R.color.colorMuted, dotColorForState(LinkState.Ready))
        assertEquals(R.color.colorMuted, dotColorForState(LinkState.Found))
    }

    @Test
    fun `chip text matches the shared LinkState vocabulary`() {
        assertEquals(R.string.chip_status_found, statusChipTextRes(LinkState.Found))
        assertEquals(R.string.chip_status_needs_pairing, statusChipTextRes(LinkState.Stale))
        assertEquals(R.string.chip_status_offline, statusChipTextRes(LinkState.Saved))
        assertEquals(R.string.chip_status_ready, statusChipTextRes(LinkState.Ready))
        assertEquals(R.string.chip_status_connecting, statusChipTextRes(LinkState.Connecting))
        assertEquals(R.string.chip_status_online, statusChipTextRes(LinkState.Connected))
        assertEquals(R.string.chip_status_unstable, statusChipTextRes(LinkState.Unstable))
    }

    @Test
    fun `every LinkState has a chip text`() {
        for (state in LinkState.entries) statusChipTextRes(state)
    }

    @Test
    fun `every LinkState has a dot color`() {
        for (state in LinkState.entries) dotColorForState(state)
    }

    @Test
    fun `every kind+state combo resolves to a non-zero drawable`() {
        for (kind in ConnectionKind.entries) {
            for (state in LinkState.entries) {
                val res = glyphForConnection(kind, state)
                org.junit.Assert.assertTrue("missing glyph for $kind/$state: $res", res != 0)
            }
        }
    }
}
