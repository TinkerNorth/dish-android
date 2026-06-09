// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionCapabilityComposerTest {
    private fun summary(
        id: String,
        kind: ConnectionKind = ConnectionKind.SATELLITE,
        live: LinkState = LinkState.Connected,
        satelliteControllerTypes: Map<String, Int> = emptyMap(),
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
        satelliteControllerTypes = satelliteControllerTypes,
    )

    private fun device(
        id: Int,
        hasGyro: Boolean,
    ) = PhysicalGamepadRegistry.Device(id, "Pad-$id", hasGyro = hasGyro)

    private fun composerFor(
        phoneAvailable: MutableStateFlow<Boolean>,
        devices: MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>,
        bindings: MutableStateFlow<Map<String, String>>,
        connections: MutableStateFlow<List<ConnectionSummary>>,
        scope: CoroutineScope,
        motionEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
        satelliteBackendStatus: MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>> =
            MutableStateFlow(emptyMap()),
    ): MotionCapabilityComposer {
        // hasGyro is a constant hardware fact; snapshot the flow's current value at construction.
        val availability: PhoneMotionAvailability =
            mockk { every { hasGyro } returns phoneAvailable.value }
        val registry: PhysicalGamepadRegistry =
            mockk { every { this@mockk.devices } returns devices }
        val hub: ConnectionCoordinator =
            mockk {
                every { this@mockk.bindings } returns bindings
                every { this@mockk.connections } returns connections
            }
        val store: MotionEnabledStore =
            mockk { every { state } returns motionEnabled }
        val backendStatusStore: SatelliteMotionBackendStatusStore =
            mockk { every { state } returns satelliteBackendStatus }
        return MotionCapabilityComposer(availability, registry, hub, store, backendStatusStore, scope)
    }

    @Test
    fun `effective requires every axis true`() {
        assertTrue(
            MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = true).effective,
        )
        assertFalse(
            MotionCapability(hasGyro = false, carriesOnConnection = true, userEnabled = true).effective,
        )
        assertFalse(
            MotionCapability(hasGyro = true, carriesOnConnection = false, userEnabled = true).effective,
        )
        assertFalse(
            MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = false).effective,
        )
    }

    @Test
    fun `toCapBits returns CAP_MOTION when the dish CAN emit motion`() {
        val cap = MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = true)
        assertEquals(MotionCapability.CAP_MOTION_BIT, cap.toCapBits())
    }

    @Test
    fun `toCapBits ignores carriesOnConnection — link-down is not a capability change`() {
        val cap = MotionCapability(hasGyro = true, carriesOnConnection = false, userEnabled = true)
        assertEquals(MotionCapability.CAP_MOTION_BIT, cap.toCapBits())
    }

    @Test
    fun `toCapBits is zero when the user has disabled motion`() {
        val cap = MotionCapability(hasGyro = true, carriesOnConnection = true, userEnabled = false)
        assertEquals(0, cap.toCapBits())
    }

    @Test
    fun `toCapBits is zero on hardware without a gyro — even if the user enabled it`() {
        val cap = MotionCapability(hasGyro = false, carriesOnConnection = true, userEnabled = true)
        assertEquals(0, cap.toCapBits())
    }

    @Test
    fun `virtual slot is always present in the map`() =
        composerTest {
            val phoneAvail = MutableStateFlow(false)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            val virtual = probe.latest[VIRTUAL_SLOT_ID]
            assertEquals(
                MotionCapability(hasGyro = false, carriesOnConnection = false, userEnabled = true),
                virtual,
            )
        }

    // hasGyro is fixed hardware, resolved once: assert the constant rides through, both values.
    @Test
    fun `virtual slot hasGyro mirrors PhoneMotionAvailability`() =
        composerTest {
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val present =
                composerFor(MutableStateFlow(true), devices, bindings, conns, backgroundScope).probe(this)
            val absent =
                composerFor(MutableStateFlow(false), devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, present.latest[VIRTUAL_SLOT_ID]?.hasGyro)
            assertEquals(false, absent.latest[VIRTUAL_SLOT_ID]?.hasGyro)
        }

    @Test
    fun `virtual slot carriesOnConnection is true when bound to a Connected satellite`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertTrue(probe.latest[VIRTUAL_SLOT_ID]?.carriesOnConnection == true)
        }

    @Test
    fun `virtual slot carriesOnConnection is false on a Bluetooth-HID binding`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "bt-A"))
            val conns = MutableStateFlow(listOf(summary("bt-A", kind = ConnectionKind.BLUETOOTH)))

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertFalse(probe.latest[VIRTUAL_SLOT_ID]?.carriesOnConnection == true)
        }

    @Test
    fun `virtual slot carriesOnConnection is false while still Connecting`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A", live = LinkState.Connecting)))

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertFalse(probe.latest[VIRTUAL_SLOT_ID]?.carriesOnConnection == true)
        }

    @Test
    fun `physical slot uses Device hasGyro verbatim — no re-probe`() =
        composerTest {
            val phoneAvail = MutableStateFlow(false)
            val devices =
                MutableStateFlow(
                    mapOf(
                        9 to device(9, hasGyro = true),
                        11 to device(11, hasGyro = false),
                    ),
                )
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertEquals(true, probe.latest["9"]?.hasGyro)
            assertEquals(false, probe.latest["11"]?.hasGyro)
        }

    @Test
    fun `physical slot drops from the map when the device leaves the registry`() =
        composerTest {
            val phoneAvail = MutableStateFlow(false)
            val devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = true)))
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertEquals(true, probe.latest["9"]?.hasGyro)

            devices.value = emptyMap()
            testScheduler.runCurrent()
            assertNull(probe.latest["9"])
        }

    @Test
    fun `userEnabled defaults to true for an unwritten slot`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())
            val motionEnabled = MutableStateFlow<Map<String, Boolean>>(emptyMap())

            val probe =
                composerFor(phoneAvail, devices, bindings, conns, backgroundScope, motionEnabled).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.userEnabled)
        }

    @Test
    fun `userEnabled flips when MotionEnabledStore writes false for a slot`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())
            val motionEnabled = MutableStateFlow<Map<String, Boolean>>(emptyMap())

            val composer =
                composerFor(phoneAvail, devices, bindings, conns, backgroundScope, motionEnabled)
            val probe = composer.probe(this)
            testScheduler.runCurrent()
            assertTrue(probe.latest[VIRTUAL_SLOT_ID]?.userEnabled == true)

            motionEnabled.value = mapOf(VIRTUAL_SLOT_ID to false)
            testScheduler.runCurrent()
            assertFalse(probe.latest[VIRTUAL_SLOT_ID]?.userEnabled == true)
        }

    @Test
    fun `toCapBits is zero on a slot the user has disabled — even if hasGyro is true`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val motionEnabled = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to false))

            val probe =
                composerFor(phoneAvail, devices, bindings, conns, backgroundScope, motionEnabled).probe(this)
            testScheduler.runCurrent()

            assertEquals(0, probe.latest[VIRTUAL_SLOT_ID]?.toCapBits())
        }

    @Test
    fun `capabilityFor returns the latest derived value for a slot`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))

            val composer =
                composerFor(phoneAvail, devices, bindings, conns, backgroundScope)
            composer.probe(this)
            testScheduler.runCurrent()

            val cap = composer.capabilityFor(VIRTUAL_SLOT_ID)
            assertTrue(cap.hasGyro)
            assertTrue(cap.carriesOnConnection)
            assertEquals(MotionCapability.CAP_MOTION_BIT, cap.toCapBits())
        }

    @Test
    fun `capabilityFor returns Off for a slot not in the map`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val conns = MutableStateFlow<List<ConnectionSummary>>(emptyList())

            val composer = composerFor(phoneAvail, devices, bindings, conns, backgroundScope)
            composer.probe(this)
            testScheduler.runCurrent()

            assertEquals(MotionCapability.Off, composer.capabilityFor("ghost-slot"))
        }

    @Test
    fun `the map re-emits when the routing flips kind from satellite to bluetooth`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = true)))
            val bindings = MutableStateFlow(mapOf("9" to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertTrue(probe.latest["9"]?.carriesOnConnection == true)

            bindings.value = mapOf("9" to "bt-A")
            conns.value = listOf(summary("bt-A", kind = ConnectionKind.BLUETOOTH))
            testScheduler.runCurrent()
            assertFalse(probe.latest["9"]?.carriesOnConnection == true)
        }

    @Test
    fun `hostHasSinkForType is true for a PlayStation-typed satellite slot`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary(
                            "sat-A",
                            satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION),
                        ),
                    ),
                )
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    @Test
    fun `hostHasSinkForType is false for an Xbox-typed satellite slot — the headline B3 case`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary(
                            "sat-A",
                            satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_XBOX),
                        ),
                    ),
                )
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(false, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    @Test
    fun `hostHasSinkForType flips when the user changes the slot type mid-session`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary(
                            "sat-A",
                            satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION),
                        ),
                    ),
                )

            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()
            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)

            conns.value =
                listOf(
                    summary(
                        "sat-A",
                        satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_XBOX),
                    ),
                )
            testScheduler.runCurrent()
            assertEquals(false, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    @Test
    fun `hostHasSinkForType is true for a slot with unknown type — no false warnings`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    @Test
    fun `hostHasSinkForType is true for a Bluetooth-HID-bound slot — limit is connection kind`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "bt-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary(
                            "bt-A",
                            kind = ConnectionKind.BLUETOOTH,
                            satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_XBOX),
                        ),
                    ),
                )
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertEquals(true, probe.latest[VIRTUAL_SLOT_ID]?.hostHasSinkForType)
        }

    @Test
    fun `satelliteBackendStatus is null when no observation has landed`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val probe = composerFor(phoneAvail, devices, bindings, conns, backgroundScope).probe(this)
            testScheduler.runCurrent()

            assertNull(probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)
        }

    @Test
    fun `satelliteBackendStatus propagates from the store for the bound connection`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val status = SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false)
            val backendStatus =
                MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(
                    mapOf(("sat-A" to VIRTUAL_SLOT_ID) to status),
                )
            val probe =
                composerFor(
                    phoneAvail,
                    devices,
                    bindings,
                    conns,
                    backgroundScope,
                    satelliteBackendStatus = backendStatus,
                ).probe(this)
            testScheduler.runCurrent()

            assertEquals(status, probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)
        }

    @Test
    fun `satelliteBackendStatus is keyed on the bound connection — wrong-connection entries are ignored`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val wrongStatus = SatelliteMotionBackendStatus(sinkSupportedForType = false, backendOk = false)
            val backendStatus =
                MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(
                    mapOf(("sat-B" to VIRTUAL_SLOT_ID) to wrongStatus),
                )
            val probe =
                composerFor(
                    phoneAvail,
                    devices,
                    bindings,
                    conns,
                    backgroundScope,
                    satelliteBackendStatus = backendStatus,
                ).probe(this)
            testScheduler.runCurrent()

            assertNull(probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)
        }

    @Test
    fun `satelliteBackendStatus updates reactively when the store re-emits`() =
        composerTest {
            val phoneAvail = MutableStateFlow(true)
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns = MutableStateFlow(listOf(summary("sat-A")))
            val broken = SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false)
            val fixed = SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = true)
            val backendStatus =
                MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(
                    mapOf(("sat-A" to VIRTUAL_SLOT_ID) to broken),
                )
            val probe =
                composerFor(
                    phoneAvail,
                    devices,
                    bindings,
                    conns,
                    backgroundScope,
                    satelliteBackendStatus = backendStatus,
                ).probe(this)
            testScheduler.runCurrent()
            assertEquals(broken, probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)

            backendStatus.value = mapOf(("sat-A" to VIRTUAL_SLOT_ID) to fixed)
            testScheduler.runCurrent()
            assertEquals(fixed, probe.latest[VIRTUAL_SLOT_ID]?.satelliteBackendStatus)
        }

    @Test
    fun `toCapBits is unaffected by satelliteBackendStatus — dish honesty is independent of receiver health`() {
        val cap =
            MotionCapability(
                hasGyro = true,
                carriesOnConnection = true,
                userEnabled = true,
                satelliteBackendStatus =
                    SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false),
            )
        assertEquals(MotionCapability.CAP_MOTION_BIT, cap.toCapBits())
    }

    @Test
    fun `effective is unaffected by satelliteBackendStatus — local listener gating doesn't change`() {
        val cap =
            MotionCapability(
                hasGyro = true,
                carriesOnConnection = true,
                userEnabled = true,
                satelliteBackendStatus =
                    SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false),
            )
        assertTrue(cap.effective)
    }
}
