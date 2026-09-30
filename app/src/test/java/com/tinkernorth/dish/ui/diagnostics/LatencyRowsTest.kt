// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.source.bluetooth.BluetoothLinkType
import com.tinkernorth.dish.source.inputrate.FrameworkTimingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyRowsTest {
    private fun host(
        id: String,
        kind: ConnectionKind,
        satellite: SatelliteSnapshot? = null,
        moonlight: MoonlightSnapshot? = null,
    ) = HostDiag(
        id = id,
        label = "Host $id",
        detail = "",
        kind = kind,
        live = LinkState.Connected,
        btProfile = null,
        telemetry = null,
        features = null,
        serverVersion = null,
        slots = emptyList(),
        satellite = satellite,
        moonlight = moonlight,
    )

    private fun facts(
        directTiming: DeviceLatency? = null,
        frameworkTiming: FrameworkTimingSummary? = null,
    ) = PadFacts(
        vendorId = 0x054C,
        productId = 0x0CE6,
        endpoint = null,
        direct = null,
        urbErrors = 0L,
        quirkBits = 0,
        linkType = BluetoothLinkType.UNKNOWN,
        reportCount = 0L,
        lastInputAtMs = 0L,
        directTiming = directTiming,
        frameworkTiming = frameworkTiming,
        stickHistory = null,
    )

    private fun pad(
        name: String,
        facts: PadFacts?,
    ) = ControllerDiag(
        slotId = name,
        name = name,
        isVirtual = false,
        transport = null,
        isUsbSynthetic = false,
        hasGyro = false,
        pollRateHz = 0,
        gyroHz = 0,
        state = ControllerDiagState.CONNECTED,
        battery = null,
        host = null,
        functions = emptyList(),
        facts = facts,
    )

    private fun stats(
        p50: Double?,
        samples: Int,
    ) = SatelliteSessionStats(
        rttP50Ms = p50,
        rttP99Ms = null,
        rttSamples = samples,
        rttRecentMs = emptyList(),
        pings = 0,
        acks = 0,
        missed = 0,
    )

    @Test
    fun `a Bluetooth host has no latency row`() {
        val rows = latencyRows(emptyList(), listOf(host("bt", ConnectionKind.BLUETOOTH)))
        assertTrue(rows.hosts.isEmpty())
    }

    @Test
    fun `a satellite row halves its heartbeat round trip and keeps the sample count`() {
        val snapshot = SatelliteSnapshot(live = true, slots = emptyMap(), telemetry = null, stats = stats(p50 = 8.0, samples = 12))
        val row = latencyRows(emptyList(), listOf(host("sat", ConnectionKind.SATELLITE, satellite = snapshot))).hosts.single()
        assertEquals(4.0, row.oneWayMs!!, 1e-9)
        assertEquals(12, row.samples)
        assertNull(row.controlRttMs)
    }

    @Test
    fun `a pad with no timing samples has no latency row`() {
        val empty =
            facts(directTiming = DeviceLatency(samples = 0, stage1P50Ms = null, stage1P99Ms = null, gapP50Ms = null, gapP99Ms = null))
        val rows = latencyRows(listOf(pad("Idle", empty), pad("Bare", facts()), pad("Virtual", null)), emptyList())
        assertTrue(rows.pads.isEmpty())
    }

    @Test
    fun `a pad with direct samples or framework timing gets its row`() {
        val direct = facts(directTiming = DeviceLatency(samples = 5, stage1P50Ms = 0.2, stage1P99Ms = 0.5, gapP50Ms = 4.0, gapP99Ms = 4.2))
        val framework =
            facts(frameworkTiming = FrameworkTimingSummary(samples = 3, gapP50Ms = 8f, gapP99Ms = 9f, delayP50Ms = 1f, delayP99Ms = 2f))
        val rows = latencyRows(listOf(pad("Direct", direct), pad("Framework", framework)), emptyList())
        assertEquals(listOf("Direct", "Framework"), rows.pads.map { it.name })
    }

    @Test
    fun `a Moonlight row shows its control round trip`() {
        val row = HostLatencyRow(label = "PC", kind = ConnectionKind.MOONLIGHT, oneWayMs = null, samples = 0, controlRttMs = 9L)
        assertEquals(HostLatencyFigure.ControlRoundTrip(9L), hostLatencyFigure(row))
    }

    @Test
    fun `a Moonlight row without a control round trip reads unknown even with a one-way figure`() {
        val row = HostLatencyRow(label = "PC", kind = ConnectionKind.MOONLIGHT, oneWayMs = 3.0, samples = 4, controlRttMs = null)
        assertEquals(HostLatencyFigure.Unknown, hostLatencyFigure(row))
    }

    @Test
    fun `a satellite row shows its one-way figure with the sample count`() {
        val row = HostLatencyRow(label = "PC", kind = ConnectionKind.SATELLITE, oneWayMs = 3.5, samples = 4, controlRttMs = null)
        assertEquals(HostLatencyFigure.OneWay(3.5, 4), hostLatencyFigure(row))
    }

    @Test
    fun `a satellite row with no stats yet reads unknown`() {
        val row = HostLatencyRow(label = "PC", kind = ConnectionKind.SATELLITE, oneWayMs = null, samples = 0, controlRttMs = null)
        assertEquals(HostLatencyFigure.Unknown, hostLatencyFigure(row))
    }
}
