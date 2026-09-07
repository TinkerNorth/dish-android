// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsMappersTest {
    private fun device(
        id: Int,
        name: String,
        synthetic: Boolean = false,
        transport: Transport = Transport.Usb,
        vid: Int = 0x054C,
        pid: Int = 0x0CE6,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = name,
        hasRumble = true,
        isUsbSynthetic = synthetic,
        vendorId = vid,
        productId = pid,
        transport = transport,
    )

    private fun summary(
        id: String,
        kind: ConnectionKind,
        bound: List<String>,
        types: Map<String, Int> = emptyMap(),
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = "Host $id",
        detail = "detail $id",
        live = live,
        boundSlotIds = bound,
        satelliteControllerTypes = types,
    )

    private fun caps(vararg features: Feature) = SlotCapabilities.NONE.copy(controller = CapabilitySet.of(*features))

    private fun world(
        devices: List<PhysicalGamepadRegistry.Device> = emptyList(),
        bindings: Map<String, String> = emptyMap(),
        summaries: List<ConnectionSummary> = emptyList(),
        satellites: Map<String, SatelliteSnapshot> = emptyMap(),
        rates: Map<String, SlotInputRates> = emptyMap(),
        batteries: Map<String, BatterySample> = emptyMap(),
        caps: Map<String, SlotCapabilities> = emptyMap(),
    ) = DiagnosticsWorld(
        devices = devices.associateBy { it.id },
        virtualName = "Virtual Controller",
        bindings = bindings,
        summaries = summaries,
        satellites = satellites,
        rates = rates,
        batteries = batteries,
        caps = caps,
        hostFeatures = emptyMap(),
        serverVersions = emptyMap(),
    )

    private val touchpadMode: (String) -> String = { "ds4" }

    @Test
    fun `the virtual pad leads the list and physical pads follow by name`() {
        val w = world(devices = listOf(device(7, "Zed pad"), device(3, "Alpha pad", transport = Transport.Bluetooth)))
        val ids = controllerDiags(w, touchpadMode).map { it.slotId }
        assertEquals(listOf(VIRTUAL_SLOT_ID, "3", "7"), ids)
        assertTrue(controllerDiags(w, touchpadMode).first().isVirtual)
    }

    @Test
    fun `an unbound pad has no host but keeps its own functions and battery`() {
        val w =
            world(
                devices = listOf(device(3, "Pad")),
                caps = mapOf("3" to caps(Feature.GAMEPAD, Feature.RUMBLE, Feature.MOTION)),
                batteries = mapOf("3" to BatterySample(level = 80, status = BatteryValidator.STATUS_CHARGING)),
                rates = mapOf("3" to SlotInputRates(controllerHz = 250, gyroHz = 0)),
            )
        val pad = controllerDiags(w, touchpadMode).single { it.slotId == "3" }
        assertNull(pad.host)
        assertEquals(listOf(Feature.MOTION, Feature.RUMBLE), pad.functions)
        assertEquals(80, pad.battery?.level)
        assertTrue(pad.battery?.charging == true)
        assertEquals(250, pad.pollRateHz)
        assertEquals(ControllerDiagState.CONNECTED, pad.state)
    }

    @Test
    fun `a satellite-bound pad carries slot index, applied state and streaming from the snapshot`() {
        val slots = mapOf("-5" to SatelliteConnection.SlotBinding(controllerIndex = 1, controllerType = 2, registered = true))
        val telemetry = SatelliteTelemetry(vigemAvailable = true, activeControllers = 1, epoch = 4, activeBitmap = 0b10)
        val w =
            world(
                devices = listOf(device(-5, "DualSense", synthetic = true)),
                bindings = mapOf("-5" to "sat"),
                summaries = listOf(summary("sat", ConnectionKind.SATELLITE, listOf("-5"), types = mapOf("-5" to 2))),
                satellites = mapOf("sat" to SatelliteSnapshot(live = true, slots = slots, telemetry = telemetry)),
            )
        val host = controllerDiags(w, touchpadMode).single { it.slotId == "-5" }.host
        assertEquals("sat", host?.connectionId)
        assertEquals(1, host?.slotIndex)
        assertEquals(2, host?.typeId)
        assertEquals("ds4", host?.touchpadMode)
        assertEquals(true, host?.registered)
        assertEquals(true, host?.streaming)
    }

    @Test
    fun `a bound pad on a host with no snapshot yet still names the host`() {
        val w =
            world(
                devices = listOf(device(4, "Pad")),
                bindings = mapOf("4" to "ml"),
                summaries = listOf(summary("ml", ConnectionKind.MOONLIGHT, listOf("4"), types = mapOf("4" to 1))),
            )
        val host = controllerDiags(w, touchpadMode).single { it.slotId == "4" }.host
        assertEquals(ConnectionKind.MOONLIGHT, host?.kind)
        assertEquals(1, host?.typeId)
        assertNull(host?.slotIndex)
        assertNull(host?.streaming)
    }

    @Test
    fun `a routed framework twin hides behind its synthetic`() {
        val w = world(devices = listOf(device(9, "DualSense"), device(-9, "DualSense", synthetic = true)))
        val ids = controllerDiags(w, touchpadMode).map { it.slotId }
        assertEquals(listOf(VIRTUAL_SLOT_ID, "-9"), ids)
    }

    @Test
    fun `host slots name the controller, carry wire truth and sort by slot index`() {
        val slots =
            mapOf(
                "-5" to SatelliteConnection.SlotBinding(controllerIndex = 1, controllerType = 2, registered = true),
                VIRTUAL_SLOT_ID to SatelliteConnection.SlotBinding(controllerIndex = 0, controllerType = 0, registered = false),
            )
        val telemetry = SatelliteTelemetry(vigemAvailable = true, activeControllers = 1, epoch = 4, activeBitmap = 0b10)
        val w =
            world(
                devices = listOf(device(-5, "DualSense", synthetic = true)),
                bindings = mapOf("-5" to "sat", VIRTUAL_SLOT_ID to "sat"),
                summaries = listOf(summary("sat", ConnectionKind.SATELLITE, listOf("-5", VIRTUAL_SLOT_ID))),
                satellites = mapOf("sat" to SatelliteSnapshot(live = true, slots = slots, telemetry = telemetry)),
            )
        val host = hostDiags(w, touchpadMode).single()
        assertEquals(listOf("Virtual Controller", "DualSense"), host.slots.map { it.controllerName })
        assertEquals(listOf(0, 1), host.slots.map { it.slotIndex })
        assertEquals(listOf(false, true), host.slots.map { it.streaming })
        assertEquals(telemetry, host.telemetry)
    }

    @Test
    fun `non-satellite hosts list their bound slots without wire truth`() {
        val w =
            world(
                devices = listOf(device(4, "Pad")),
                bindings = mapOf("4" to "bt"),
                summaries = listOf(summary("bt", ConnectionKind.BLUETOOTH, listOf("4"))),
            )
        val host = hostDiags(w, touchpadMode).single()
        assertEquals("Pad", host.slots.single().controllerName)
        assertNull(host.slots.single().slotIndex)
        assertNull(host.telemetry)
        assertNull(host.features)
    }

    @Test
    fun `streaming reads the controller index bit and treats a negative bitmap as unknown`() {
        assertTrue(streamingOn(activeBitmap = 0b101, controllerIndex = 2))
        assertFalse(streamingOn(activeBitmap = 0b101, controllerIndex = 1))
        assertFalse(streamingOn(activeBitmap = -1, controllerIndex = 0))
    }
}
