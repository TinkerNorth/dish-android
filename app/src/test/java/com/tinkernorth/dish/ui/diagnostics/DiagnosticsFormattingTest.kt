// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import android.content.res.Resources
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.hotpath.input.Transport
import io.mockk.MockKAnswerScope
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The overview cards take a Context only for getString, so a mock that echoes the resource id
// and joins the key-value pair pins which lines each card shows.
class DiagnosticsFormattingTest {
    private val resources =
        mockk<Resources> {
            every { getQuantityString(R.plurals.diagnostics_last_pings, any<Int>(), any<Int>()) } answers { "${secondArg<Int>()} pings" }
        }

    // getString's format arguments arrive as one array, so the answers index into it.
    private val context =
        mockk<Context> {
            every { resources } returns this@DiagnosticsFormattingTest.resources
            every { getString(any()) } answers { "s${firstArg<Int>()}" }
            every { getString(R.string.diagnostics_kv, *anyVararg()) } answers { "${formatArg(0)}=${formatArg(1)}" }
            every { getString(R.string.diagnostics_joined, *anyVararg()) } answers { "${formatArg(0)} · ${formatArg(1)}" }
            every { getString(R.string.diagnostics_hz, *anyVararg()) } answers { "${formatArg(0)} Hz" }
            every { getString(R.string.diagnostics_ms_whole, *anyVararg()) } answers { "${formatArg(0)} ms" }
            every { getString(R.string.diagnostics_ms_approx_window, *anyVararg()) } answers {
                "~${formatArg(0)} ms over ${formatArg(1)}"
            }
        }

    private fun MockKAnswerScope<*, *>.formatArg(index: Int): Any? = secondArg<Array<Any?>>()[index]

    private fun pad(
        isVirtual: Boolean,
        hasGyro: Boolean = false,
        gyroHz: Int = 0,
    ) = ControllerDiag(
        slotId = "3",
        name = "Pad",
        isVirtual = isVirtual,
        transport = Transport.Usb,
        isUsbSynthetic = false,
        hasGyro = hasGyro,
        pollRateHz = 250,
        gyroHz = gyroHz,
        state = ControllerDiagState.CONNECTED,
        battery = null,
        host = null,
        functions = emptyList(),
    )

    private fun host(kind: ConnectionKind) =
        HostDiag(
            id = "h",
            label = "Host",
            detail = "",
            kind = kind,
            live = LinkState.Connected,
            btProfile = null,
            telemetry = null,
            features = null,
            serverVersion = null,
            slots = emptyList(),
        )

    @Test
    fun `a virtual pad has no transport, poll or state line`() {
        val lines = context.controllerCardLines(pad(isVirtual = true))
        assertEquals(listOf("s${R.string.diagnostics_host}=s${R.string.binding_card_not_bound}"), lines)
    }

    @Test
    fun `a physical pad leads with its transport and poll rate and ends with its state and host`() {
        val lines = context.controllerCardLines(pad(isVirtual = false))
        assertEquals(
            listOf(
                "s${R.string.diagnostics_transport}=s${R.string.diagnostics_transport_usb_standard}",
                "s${R.string.diagnostics_poll_rate}=250 Hz",
                "s${R.string.diagnostics_state}=s${R.string.diagnostics_state_connected}",
                "s${R.string.diagnostics_host}=s${R.string.binding_card_not_bound}",
            ),
            lines,
        )
    }

    @Test
    fun `a gyro reads its rate when measured and present otherwise`() {
        assertTrue(
            context
                .controllerCardLines(
                    pad(isVirtual = true, hasGyro = true),
                ).contains("s${R.string.diagnostics_gyro}=s${R.string.diagnostics_present}"),
        )
        assertTrue(context.controllerCardLines(pad(isVirtual = true, gyroHz = 100)).contains("s${R.string.diagnostics_gyro}=100 Hz"))
    }

    @Test
    fun `a pad with functions lists them last`() {
        val lines = context.controllerCardLines(pad(isVirtual = true).copy(functions = listOf(Feature.MOTION)))
        assertEquals("s${R.string.binding_label_functions}=s${R.string.setup_cap_motion}", lines.last())
    }

    @Test
    fun `a host card leads with transport and link and a satellite adds its own facts`() {
        val bluetooth = context.hostCardLines(host(ConnectionKind.BLUETOOTH))
        assertEquals(2, bluetooth.size)
        assertEquals("s${R.string.diagnostics_transport}=s${R.string.overlay_connection_kind_bluetooth}", bluetooth[0])
        val satellite = context.hostCardLines(host(ConnectionKind.SATELLITE))
        assertTrue(satellite.size > 2)
        assertEquals("s${R.string.diagnostics_host}=s${R.string.diagnostics_offline}", satellite[2])
    }

    @Test
    fun `a Moonlight latency row shows the control round trip`() {
        val row = HostLatencyRow(label = "PC", kind = ConnectionKind.MOONLIGHT, oneWayMs = null, samples = 0, controlRttMs = 9L)
        assertEquals("PC · 9 ms", context.hostLatencyValue(row))
    }

    @Test
    fun `a satellite latency row shows the one-way figure over its pings`() {
        val row = HostLatencyRow(label = "PC", kind = ConnectionKind.SATELLITE, oneWayMs = 3.5, samples = 4, controlRttMs = null)
        assertEquals("PC · ~3.5 ms over 4 pings", context.hostLatencyValue(row))
    }

    @Test
    fun `a latency row with nothing measured reads unknown`() {
        val row = HostLatencyRow(label = "PC", kind = ConnectionKind.SATELLITE, oneWayMs = null, samples = 0, controlRttMs = null)
        assertEquals("PC · s${R.string.diagnostics_unknown}", context.hostLatencyValue(row))
    }
}
