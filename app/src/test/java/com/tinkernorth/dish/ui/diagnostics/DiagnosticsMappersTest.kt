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
import com.tinkernorth.dish.source.store.StickTestHistoryStore
import com.tinkernorth.dish.source.store.StickTestRecord
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NINTENDO_VID = 0x057E

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

    private fun stateOf(device: PhysicalGamepadRegistry.Device): ControllerDiagState =
        controllerDiags(world(devices = listOf(device)), touchpadMode).single { it.slotId == device.id.toString() }.state

    @Test
    fun `a pad needing a replug outranks a transition`() {
        val device = device(3, "Pad").copy(needsReplug = true, transitioning = true, disconnectingTimeLeftSec = 2)
        assertEquals(ControllerDiagState.NEEDS_REPLUG, stateOf(device))
    }

    @Test
    fun `a restore-stuck or transitioning pad reads transitioning over disconnecting`() {
        assertEquals(ControllerDiagState.TRANSITIONING, stateOf(device(3, "Pad").copy(restoreStuck = true, disconnectingTimeLeftSec = 2)))
        assertEquals(ControllerDiagState.TRANSITIONING, stateOf(device(3, "Pad").copy(transitioning = true)))
    }

    @Test
    fun `a pad counting down its disconnect reads disconnecting`() {
        assertEquals(ControllerDiagState.DISCONNECTING, stateOf(device(3, "Pad").copy(disconnectingTimeLeftSec = 2)))
    }

    @Test
    fun `a synthetic pad reports no quirks while its framework twin does`() {
        val quirky = device(9, "Pro Controller", vid = NINTENDO_VID, pid = 0x2009)
        val synthetic = quirky.copy(id = -9, isUsbSynthetic = true)
        assertEquals(0, padFacts(synthetic, world()).quirkBits)
        assertTrue(padFacts(quirky, world()).quirkBits != 0)
    }

    @Test
    fun `stick history is looked up by the model key`() {
        val device = device(3, "DualSense")
        val record = StickTestRecord(driftAtMs = 5L, driftLeft = 0.1f)
        val key = StickTestHistoryStore.keyFor(device.vendorId, device.productId, device.name)
        val w = world().copy(pads = PadWorld(stickHistory = mapOf(key to record)))
        assertEquals(record, padFacts(device, w).stickHistory)
        assertNull(padFacts(device.copy(productId = 0x0DF2), w).stickHistory)
    }

    @Test
    fun `no capabilities means no functions`() {
        assertTrue(functionsOf(null).isEmpty())
    }

    private fun withWifi(
        w: DiagnosticsWorld,
        ipv4: String?,
    ): DiagnosticsWorld =
        w.copy(
            radios =
                RadioFacts.NONE.copy(
                    wifi = WifiLink(rssiDbm = -50, linkSpeedMbps = 100, frequencyMhz = 5200, ipv4 = ipv4, prefixLength = 24),
                ),
        )

    @Test
    fun `a Bluetooth host has no subnet verdict`() {
        val w = withWifi(world(), "192.168.1.10")
        assertNull(sameSubnet(w, summary("bt", ConnectionKind.BLUETOOTH, emptyList())))
    }

    @Test
    fun `a host is not judged without a wifi link or without an address in its detail`() {
        val satellite = summary("sat", ConnectionKind.SATELLITE, emptyList()).copy(detail = "192.168.1.5:9876")
        assertNull(sameSubnet(world(), satellite))
        assertNull(sameSubnet(withWifi(world(), "192.168.1.10"), satellite.copy(detail = "no address here")))
    }

    @Test
    fun `a host on the phone's prefix reads same network and one elsewhere does not`() {
        val w = withWifi(world(), "192.168.1.10")
        val near = summary("sat", ConnectionKind.SATELLITE, emptyList()).copy(detail = "192.168.1.5:9876")
        assertEquals(true, sameSubnet(w, near))
        assertEquals(false, sameSubnet(w, near.copy(detail = "10.0.0.5:9876")))
    }

    @Test
    fun `streaming reads the controller index bit and treats a negative bitmap as unknown`() {
        assertTrue(streamingOn(activeBitmap = 0b101, controllerIndex = 2))
        assertFalse(streamingOn(activeBitmap = 0b101, controllerIndex = 1))
        assertFalse(streamingOn(activeBitmap = -1, controllerIndex = 0))
    }
}
