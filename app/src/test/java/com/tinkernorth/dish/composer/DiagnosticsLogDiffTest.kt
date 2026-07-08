// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.usb.DirectClaimFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogDiffTest {
    private fun summary(
        id: String,
        live: LinkState,
        label: String = id,
    ) = ConnectionSummary(
        id = id,
        kind = ConnectionKind.SATELLITE,
        label = label,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    private fun device(
        id: Int,
        name: String = "Pad-$id",
        isUsbSynthetic: Boolean = false,
        needsReplug: Boolean = false,
        directFailure: DirectClaimFailure? = null,
        disconnectingTimeLeftSec: Int? = null,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = name,
        vendorId = 0x054C,
        productId = 0x09CC,
        isUsbSynthetic = isUsbSynthetic,
        needsReplug = needsReplug,
        directFailure = directFailure,
        disconnectingTimeLeftSec = disconnectingTimeLeftSec,
    )

    @Test
    fun `a new connection and a link change each produce one line`() {
        val appeared = DiagnosticsLogDiff.connectionEvents(emptyList(), listOf(summary("s:1", LinkState.Connected, "Desk PC")))
        assertEquals(listOf("Desk PC: appeared (SATELLITE, Connected)"), appeared)

        val changed =
            DiagnosticsLogDiff.connectionEvents(
                listOf(summary("s:1", LinkState.Connected, "Desk PC")),
                listOf(summary("s:1", LinkState.Unstable, "Desk PC")),
            )
        assertEquals(listOf("Desk PC: Connected -> Unstable"), changed)
    }

    @Test
    fun `a removed connection produces a removed line and no change spam`() {
        val events =
            DiagnosticsLogDiff.connectionEvents(
                listOf(summary("s:1", LinkState.Connected, "Desk PC")),
                emptyList(),
            )
        assertEquals(listOf("Desk PC: removed"), events)
    }

    @Test
    fun `an unchanged snapshot produces nothing`() {
        val same = listOf(summary("s:1", LinkState.Connected))
        assertTrue(DiagnosticsLogDiff.connectionEvents(same, same).isEmpty())
        val devices = mapOf(1 to device(1))
        assertTrue(DiagnosticsLogDiff.deviceEvents(devices, devices).isEmpty())
    }

    @Test
    fun `device attach carries the path and the usb identity`() {
        val events = DiagnosticsLogDiff.deviceEvents(emptyMap(), mapOf(1 to device(1, name = "DualShock 4")))
        assertEquals(listOf("DualShock 4: attached (Usb, 054c:09cc)"), events)

        val direct = DiagnosticsLogDiff.deviceEvents(emptyMap(), mapOf(-1001 to device(-1001, isUsbSynthetic = true)))
        assertEquals(listOf("Pad--1001: attached (USB direct, 054c:09cc)"), direct)
    }

    @Test
    fun `flag onsets log once each and detach logs the removal`() {
        val before = mapOf(1 to device(1))
        val after =
            mapOf(
                1 to device(1, needsReplug = true, directFailure = DirectClaimFailure.Busy, disconnectingTimeLeftSec = 5),
            )
        val events = DiagnosticsLogDiff.deviceEvents(before, after)
        assertEquals(
            listOf(
                "Pad-1: needs replug",
                "Pad-1: direct claim failed (Busy)",
                "Pad-1: disconnecting",
            ),
            events,
        )

        assertEquals(listOf("Pad-1: detached"), DiagnosticsLogDiff.deviceEvents(after, emptyMap()))
    }
}
