// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.source.system.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLinkGuardTest {
    private fun summary(
        live: LinkState,
        kind: ConnectionKind = ConnectionKind.SATELLITE,
        id: String = "s:1",
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = "Desk PC",
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    private fun guard(
        summary: ConnectionSummary? = summary(LinkState.Connected),
        boundConnectionId: String? = "s:1",
        connectionId: String = "s:1",
        device: SlotDeviceState? = null,
        network: NetworkState = NetworkState.WIFI,
    ) = overlayGuardFor(summary, boundConnectionId, connectionId, device, network)

    @Test
    fun `a connected bound slot shows no guard`() {
        assertEquals(GuardKind.NONE, guard().kind)
    }

    @Test
    fun `an unsteady link keeps routing and shows no guard`() {
        assertEquals(GuardKind.NONE, guard(summary = summary(LinkState.Unstable)).kind)
    }

    @Test
    fun `a virtual slot never reports a controller state`() {
        for (state in LinkState.entries) {
            val ui = guard(summary = summary(state), device = null)
            assertTrue(state.name, ui.kind != GuardKind.UNPLUGGED && ui.kind != GuardKind.DEPARTED)
        }
    }

    @Test
    fun `a forgotten connection is terminal`() {
        val ui = guard(summary = null)
        assertEquals(GuardKind.GONE, ui.kind)
        assertTrue(ui.autoClose)
    }

    @Test
    fun `a rebound slot is terminal`() {
        val ui = guard(boundConnectionId = "s:other")
        assertEquals(GuardKind.UNBOUND, ui.kind)
        assertTrue(ui.autoClose)
    }

    @Test
    fun `an unbound slot is terminal`() {
        assertEquals(GuardKind.UNBOUND, guard(boundConnectionId = null).kind)
    }

    @Test
    fun `a controller in its disconnect grace warns with the replug countdown`() {
        val ui = guard(device = SlotDeviceState(present = true, disconnectingSecLeft = 7))
        assertEquals(GuardKind.UNPLUGGED, ui.kind)
        assertEquals(7, ui.countdownSec)
        assertFalse(ui.autoClose)
    }

    @Test
    fun `a controller the OS dropped warns without a countdown`() {
        val ui = guard(device = SlotDeviceState(present = true, needsReplug = true))
        assertEquals(GuardKind.UNPLUGGED, ui.kind)
        assertEquals(null, ui.countdownSec)
    }

    @Test
    fun `a controller that never came back is terminal`() {
        val ui = guard(device = SlotDeviceState(present = false))
        assertEquals(GuardKind.DEPARTED, ui.kind)
        assertTrue(ui.autoClose)
    }

    @Test
    fun `a path-switch transition is left alone`() {
        val ui = guard(device = SlotDeviceState(present = true, disconnectingSecLeft = 3, transitioning = true))
        assertEquals(GuardKind.NONE, ui.kind)
    }

    @Test
    fun `a connecting link reads as reconnecting`() {
        assertEquals(GuardKind.RECONNECTING, guard(summary = summary(LinkState.Connecting)).kind)
    }

    @Test
    fun `every dead link state reads as host lost`() {
        for (state in listOf(LinkState.Saved, LinkState.Stale, LinkState.Ready, LinkState.Found)) {
            assertEquals(state.name, GuardKind.HOST_LOST, guard(summary = summary(state)).kind)
        }
    }

    @Test
    fun `only a lost satellite offers reconnect`() {
        assertTrue(guard(summary = summary(LinkState.Saved, ConnectionKind.SATELLITE)).showReconnect)
        assertFalse(guard(summary = summary(LinkState.Saved, ConnectionKind.BLUETOOTH)).showReconnect)
        assertFalse(guard(summary = summary(LinkState.Saved, ConnectionKind.MOONLIGHT)).showReconnect)
        assertFalse(guard(summary = summary(LinkState.Connecting, ConnectionKind.SATELLITE)).showReconnect)
    }

    @Test
    fun `a lost satellite off wifi points at the network`() {
        for (network in listOf(NetworkState.NONE, NetworkState.CELLULAR)) {
            val ui = guard(summary = summary(LinkState.Saved), network = network)
            assertEquals(network.name, GuardDetail.WIFI_DOWN, ui.detail)
        }
    }

    @Test
    fun `a lost satellite on wifi blames the host`() {
        val ui = guard(summary = summary(LinkState.Saved), network = NetworkState.WIFI)
        assertEquals(GuardDetail.GENERIC, ui.detail)
        assertEquals("Desk PC", ui.hostLabel)
    }

    @Test
    fun `a lost bluetooth host points at the host device regardless of wifi`() {
        for (network in NetworkState.entries) {
            val ui = guard(summary = summary(LinkState.Saved, ConnectionKind.BLUETOOTH), network = network)
            assertEquals(network.name, GuardDetail.BLUETOOTH_HOST, ui.detail)
        }
    }

    @Test
    fun `a dead moonlight session on wifi points at the session`() {
        val ui = guard(summary = summary(LinkState.Saved, ConnectionKind.MOONLIGHT))
        assertEquals(GuardDetail.MOONLIGHT_SESSION, ui.detail)
    }

    @Test
    fun `a dead moonlight session off wifi points at the network first`() {
        val ui = guard(summary = summary(LinkState.Saved, ConnectionKind.MOONLIGHT), network = NetworkState.NONE)
        assertEquals(GuardDetail.WIFI_DOWN, ui.detail)
    }

    @Test
    fun `a gone connection outranks a gone controller`() {
        val ui = guard(summary = null, device = SlotDeviceState(present = false))
        assertEquals(GuardKind.GONE, ui.kind)
    }

    @Test
    fun `a gone controller outranks an unbound slot`() {
        val ui = guard(boundConnectionId = null, device = SlotDeviceState(present = false))
        assertEquals(GuardKind.DEPARTED, ui.kind)
    }

    @Test
    fun `a replug grace outranks a dead link`() {
        val ui =
            guard(
                summary = summary(LinkState.Saved),
                device = SlotDeviceState(present = true, disconnectingSecLeft = 5),
            )
        assertEquals(GuardKind.UNPLUGGED, ui.kind)
    }

    @Test
    fun `an unbound slot outranks a dead link`() {
        val ui = guard(summary = summary(LinkState.Saved), boundConnectionId = null)
        assertEquals(GuardKind.UNBOUND, ui.kind)
    }

    @Test
    fun `a present healthy controller falls through to the link checks`() {
        val device = SlotDeviceState(present = true)
        assertEquals(GuardKind.NONE, guard(device = device).kind)
        assertEquals(GuardKind.HOST_LOST, guard(summary = summary(LinkState.Saved), device = device).kind)
    }

    @Test
    fun `terminal states and only terminal states auto-close`() {
        val terminal = setOf(GuardKind.GONE, GuardKind.UNBOUND, GuardKind.DEPARTED)
        val samples =
            listOf(
                guard(),
                guard(summary = summary(LinkState.Connecting)),
                guard(summary = summary(LinkState.Saved)),
                guard(device = SlotDeviceState(present = true, disconnectingSecLeft = 3)),
                guard(device = SlotDeviceState(present = true, needsReplug = true)),
                guard(device = SlotDeviceState(present = false)),
                guard(boundConnectionId = null),
                guard(summary = null),
            )
        samples.forEach { ui ->
            assertEquals(ui.kind.name, ui.kind in terminal, ui.autoClose)
        }
    }
}
