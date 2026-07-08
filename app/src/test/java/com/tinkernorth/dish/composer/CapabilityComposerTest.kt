// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.CatalogFeatureDto
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SatelliteHostRuntime
import com.tinkernorth.dish.source.store.SatelliteHostRuntimeStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import com.tinkernorth.dish.source.store.TouchpadModeStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityComposerTest {
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
        hasGyro: Boolean = false,
        hasRumble: Boolean = false,
        vendorId: Int = 0,
        productId: Int = 0,
        isUsbSynthetic: Boolean = false,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "Pad-$id",
        hasGyro = hasGyro,
        hasRumble = hasRumble,
        vendorId = vendorId,
        productId = productId,
        isUsbSynthetic = isUsbSynthetic,
    )

    @Suppress("LongParameterList")
    private fun composerFor(
        phoneAvailable: Boolean,
        devices: MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>,
        bindings: MutableStateFlow<Map<String, String>>,
        connections: MutableStateFlow<List<ConnectionSummary>>,
        scope: CoroutineScope,
        motionEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
        rumbleEnabled: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
        touchpadMode: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
        hostFeaturesState: MutableStateFlow<Map<String, HostFeatureSet>> = MutableStateFlow(emptyMap()),
        backendStatus: MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>> =
            MutableStateFlow(emptyMap()),
        hostRuntime: MutableStateFlow<Map<String, SatelliteHostRuntime>> = MutableStateFlow(emptyMap()),
        cachedCatalog: CatalogDto? = null,
        modelHasImu: Boolean = false,
        modelHasRumble: Boolean = false,
        modelHasTouchpad: Boolean = false,
        satTypes: MutableStateFlow<Map<Pair<String, String>, Int>> = MutableStateFlow(emptyMap()),
    ): CapabilityComposer {
        val availability: PhoneMotionAvailability = mockk { every { hasGyro } returns phoneAvailable }
        val registry: PhysicalGamepadRegistry = mockk { every { this@mockk.devices } returns devices }
        val hub: ConnectionCoordinator =
            mockk {
                every { this@mockk.bindings } returns bindings
                every { this@mockk.connections } returns connections
                every { this@mockk.satTypes } returns satTypes
            }
        val native: PhysicalInputNative =
            mockk {
                every { modelHasImu(any(), any()) } returns modelHasImu
                every { modelHasRumble(any(), any()) } returns modelHasRumble
                every { modelHasTouchpad(any(), any()) } returns modelHasTouchpad
            }
        val motionStore: MotionEnabledStore = mockk { every { state } returns motionEnabled }
        val rumbleStore: RumbleEnabledStore = mockk { every { state } returns rumbleEnabled }
        val touchpadStore: TouchpadModeStore = mockk { every { state } returns touchpadMode }
        val hostStore: SatelliteHostFeaturesStore =
            mockk {
                every { state } returns hostFeaturesState
                every { featuresFor(any()) } answers { hostFeaturesState.value[firstArg()] }
            }
        val backendStore: SatelliteMotionBackendStatusStore = mockk { every { state } returns backendStatus }
        val hostRuntimeStore: SatelliteHostRuntimeStore =
            mockk { every { runtimeFor(any()) } answers { hostRuntime.value[firstArg()] } }
        // Default no cached catalog: the type layer falls back to BundledCatalog. Tests that
        // exercise the catalog-driven path pass a cachedCatalog explicitly.
        val catalogRepo: SatelliteCatalogRepository = mockk { every { cached(any()) } returns cachedCatalog }
        return CapabilityComposer(
            availability,
            registry,
            hub,
            native,
            motionStore,
            rumbleStore,
            touchpadStore,
            hostStore,
            backendStore,
            hostRuntimeStore,
            catalogRepo,
            scope,
        )
    }

    @Test
    fun `virtual slot is always present`() =
        composerTest {
            val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val virtual = composer.capabilityFor(VIRTUAL_SLOT_ID)
            assertTrue(Feature.GAMEPAD in virtual.controller)
            assertTrue(Feature.TOUCHPAD in virtual.controller)
            assertTrue(Feature.MOTION in virtual.controller)
        }

    @Test
    fun `virtual controller layer omits MOTION when the phone has no gyro`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            assertFalse(Feature.MOTION in composer.capabilityFor(VIRTUAL_SLOT_ID).controller)
        }

    @Test
    fun `physical slot controller layer unions Device caps with native model lookups`() =
        composerTest {
            val devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = false, hasRumble = false)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    modelHasImu = true,
                    modelHasRumble = true,
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val controller = composer.capabilityFor("9").controller
            // Device reports neither, but the native model DB does: the union wins.
            assertTrue(Feature.MOTION in controller)
            assertTrue(Feature.RUMBLE in controller)
            // The phone screen sources touchpad and mouse alongside any physical pad.
            assertTrue(Feature.TOUCHPAD in controller)
            assertTrue(Feature.MOUSE in controller)
        }

    @Test
    fun `available drops MOTION for an Xbox-typed satellite slot`() =
        composerTest {
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary("sat-A", satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_XBOX)),
                    ),
                )
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = bindings,
                    connections = conns,
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            // Phone has a gyro and the host is a satellite, but the Xbox type has no motion sink.
            assertFalse(composer.capabilityFor(VIRTUAL_SLOT_ID).isAvailable(Feature.MOTION))
        }

    @Test
    fun `available keeps MOTION for a PlayStation-typed satellite slot`() =
        composerTest {
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary("sat-A", satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION)),
                    ),
                )
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = bindings,
                    connections = conns,
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps = composer.capabilityFor(VIRTUAL_SLOT_ID)
            assertTrue(caps.isAvailable(Feature.MOTION))
            // The PlayStation type layer also carries touchpad and rumble (not just transport/host).
            assertTrue(caps.isAvailable(Feature.TOUCHPAD))
            assertTrue(caps.isAvailable(Feature.RUMBLE))
        }

    @Test
    fun `recomputes when host features change`() =
        composerTest {
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary("sat-A", satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION)),
                    ),
                )
            // Start with a host that explicitly withholds mouse control so MOUSE is gated off.
            val hostState =
                MutableStateFlow<Map<String, HostFeatureSet>>(
                    mapOf(
                        "sat-A" to
                            HostFeatureSet(
                                hasCatalog = true,
                                mouseControl = false,
                                keyboardControl = false,
                                rumbleReturn = true,
                            ),
                    ),
                )
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = bindings,
                    connections = conns,
                    scope = backgroundScope,
                    hostFeaturesState = hostState,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            // The host withholds mouse control, so MOUSE is unavailable even though the type and input carry it.
            assertFalse(composer.capabilityFor(VIRTUAL_SLOT_ID).isAvailable(Feature.MOUSE))

            hostState.value =
                mapOf(
                    "sat-A" to
                        HostFeatureSet(
                            hasCatalog = true,
                            mouseControl = true,
                            keyboardControl = false,
                            rumbleReturn = true,
                        ),
                )
            testScheduler.runCurrent()
            // Host now grants mouse; the type passes MOUSE through and the phone screen sources it, so it is available.
            assertTrue(composer.capabilityFor(VIRTUAL_SLOT_ID).isAvailable(Feature.MOUSE))
            assertTrue(Feature.MOUSE in composer.capabilityFor(VIRTUAL_SLOT_ID).host)
        }

    @Test
    fun `recomputes enabled when a user toggle changes`() =
        composerTest {
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary("sat-A", satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION)),
                    ),
                )
            val motionEnabled = MutableStateFlow<Map<String, Boolean>>(emptyMap())
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = bindings,
                    connections = conns,
                    scope = backgroundScope,
                    motionEnabled = motionEnabled,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            assertTrue(composer.capabilityFor(VIRTUAL_SLOT_ID).isEnabled(Feature.MOTION))

            motionEnabled.value = mapOf(VIRTUAL_SLOT_ID to false)
            testScheduler.runCurrent()
            assertFalse(composer.capabilityFor(VIRTUAL_SLOT_ID).isEnabled(Feature.MOTION))
            // Still inherently available: the user toggle gates enabled, not available.
            assertTrue(composer.capabilityFor(VIRTUAL_SLOT_ID).isAvailable(Feature.MOTION))
        }

    @Test
    fun `runtimeDown removes MOTION from live when the backend reports not ok`() =
        composerTest {
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary("sat-A", satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION)),
                    ),
                )
            val backend =
                MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(
                    mapOf(("sat-A" to VIRTUAL_SLOT_ID) to SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false)),
                )
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = bindings,
                    connections = conns,
                    scope = backgroundScope,
                    backendStatus = backend,
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val caps = composer.capabilityFor(VIRTUAL_SLOT_ID)
            assertTrue(caps.isEnabled(Feature.MOTION))
            assertFalse(Feature.MOTION in caps.live)
        }

    @Test
    fun `capabilityForCandidate differs between Xbox and PlayStation for an unbound slot`() =
        composerTest {
            val devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = true)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val asXbox =
                composer.capabilityForCandidate(
                    slotId = "9",
                    candidateType = CONTROLLER_TYPE_XBOX,
                    candidateHostKind = ConnectionKind.SATELLITE,
                    candidateHostId = "sat-A",
                )
            val asPlayStation =
                composer.capabilityForCandidate(
                    slotId = "9",
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.SATELLITE,
                    candidateHostId = "sat-A",
                )
            // The device reports a gyro; only the PlayStation type has a motion sink.
            assertFalse(asXbox.isAvailable(Feature.MOTION))
            assertTrue(asPlayStation.isAvailable(Feature.MOTION))
        }

    @Test
    fun `capabilityForCandidate ignores the current binding for the type layer`() =
        composerTest {
            // Slot 9 is bound Xbox live, but a PlayStation candidate must still report motion-capable.
            val devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = true)))
            val bindings = MutableStateFlow(mapOf("9" to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(summary("sat-A", satelliteControllerTypes = mapOf("9" to CONTROLLER_TYPE_XBOX))),
                )
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = bindings,
                    connections = conns,
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            assertFalse(composer.capabilityFor("9").isAvailable(Feature.MOTION))

            val candidate =
                composer.capabilityForCandidate(
                    slotId = "9",
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.SATELLITE,
                    candidateHostId = "sat-A",
                )
            assertTrue(candidate.isAvailable(Feature.MOTION))
        }

    @Test
    fun `virtual slot on a satellite-default host carries rumble and mouse`() =
        composerTest {
            val bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(
                        summary("sat-A", satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION)),
                    ),
                )
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = bindings,
                    connections = conns,
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps = composer.capabilityFor(VIRTUAL_SLOT_ID)
            // The phone vibrator backs rumble and the default satellite host accepts mouse control.
            assertTrue(caps.isAvailable(Feature.RUMBLE))
            assertTrue(caps.isAvailable(Feature.MOUSE))
        }

    @Test
    fun `a physical pad with no motor on a satellite has touchpad and mouse but no rumble`() =
        composerTest {
            val devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = false, hasRumble = false)))
            val bindings = MutableStateFlow(mapOf("9" to "sat-A"))
            val conns =
                MutableStateFlow(
                    listOf(summary("sat-A", satelliteControllerTypes = mapOf("9" to CONTROLLER_TYPE_PLAYSTATION))),
                )
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = bindings,
                    connections = conns,
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps = composer.capabilityFor("9")
            // Rumble needs the pad's own motor (no phone fallback for a physical controller); the phone screen still sources touch.
            assertFalse(caps.isAvailable(Feature.RUMBLE))
            assertTrue(caps.isAvailable(Feature.TOUCHPAD))
            assertTrue(caps.isAvailable(Feature.MOUSE))
        }

    @Test
    fun `a bluetooth candidate carries only the gamepad, not mouse or rumble or touchpad`() =
        composerTest {
            val devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = true, hasRumble = true)))
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val bt =
                composer.capabilityForCandidate(
                    slotId = "9",
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.BLUETOOTH,
                    candidateHostId = null,
                )
            // The Bluetooth transport advertises a fixed HID gamepad with no return channel.
            assertFalse(bt.isAvailable(Feature.MOUSE))
            assertFalse(bt.isAvailable(Feature.RUMBLE))
            assertFalse(bt.isAvailable(Feature.TOUCHPAD))
            assertTrue(bt.isAvailable(Feature.GAMEPAD))
        }

    @Test
    fun `capabilityFor returns NONE for an unknown slot`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            assertFalse(composer.capabilityFor("ghost").isAvailable(Feature.GAMEPAD))
        }

    @Test
    fun `a candidate drops MOTION from live when the pre-bind host runtime reports the backend down`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    hostRuntime = MutableStateFlow(mapOf("sat-A" to SatelliteHostRuntime(motionBackendOk = false))),
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps =
                composer.capabilityForCandidate(
                    slotId = VIRTUAL_SLOT_ID,
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.SATELLITE,
                    candidateHostId = "sat-A",
                )
            // Present and inherently available, but the probe says the backend is down right now.
            assertTrue(caps.isAvailable(Feature.MOTION))
            assertTrue(caps.isEnabled(Feature.MOTION))
            assertFalse(Feature.MOTION in caps.live)
        }

    @Test
    fun `a candidate keeps MOTION live when the pre-bind host runtime reports the backend ok`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    hostRuntime = MutableStateFlow(mapOf("sat-A" to SatelliteHostRuntime(motionBackendOk = true))),
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps =
                composer.capabilityForCandidate(
                    slotId = VIRTUAL_SLOT_ID,
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.SATELLITE,
                    candidateHostId = "sat-A",
                )
            assertTrue(Feature.MOTION in caps.live)
        }

    @Test
    fun `a candidate ignores host runtime for a non-satellite host`() =
        composerTest {
            // A BLUETOOTH candidate must not pick up a satellite's runtime-down state.
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(mapOf(9 to device(9, hasGyro = true))),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    hostRuntime = MutableStateFlow(mapOf("sat-A" to SatelliteHostRuntime(motionBackendOk = false))),
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps =
                composer.capabilityForCandidate(
                    slotId = "9",
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.BLUETOOTH,
                    candidateHostId = "sat-A",
                )
            assertTrue(caps.runtimeDown.features.isEmpty())
        }

    @Test
    fun `a candidate gates the DS4 pad off when the cached catalog touchpad lacks the ds4 mode`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    cachedCatalog = catalogWithDs4Touchpad(modes = listOf("mouse")),
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps =
                composer.capabilityForCandidate(
                    slotId = VIRTUAL_SLOT_ID,
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.SATELLITE,
                    candidateHostId = "sat-A",
                )
            assertFalse(caps.isAvailable(Feature.TOUCHPAD))
        }

    @Test
    fun `a candidate keeps the DS4 pad when the cached catalog touchpad advertises the ds4 mode`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    cachedCatalog = catalogWithDs4Touchpad(modes = listOf("ds4")),
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps =
                composer.capabilityForCandidate(
                    slotId = VIRTUAL_SLOT_ID,
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.SATELLITE,
                    candidateHostId = "sat-A",
                )
            assertTrue(caps.isAvailable(Feature.TOUCHPAD))
        }

    private fun catalogWithDs4Touchpad(modes: List<String>): CatalogDto =
        CatalogDto(
            controllerTypes =
                listOf(
                    CatalogTypeDto(
                        id = CONTROLLER_TYPE_PLAYSTATION,
                        slug = "ds4",
                        features = mapOf("touchpad" to CatalogFeatureDto(supported = true, modes = modes)),
                    ),
                ),
        )

    // ── touch sourcing: pad-first, phone only for trackpad-less inputs ──────

    @Test
    fun `a trackpad-bearing pad on a framework path sources no touch`() =
        composerTest {
            val devices = MutableStateFlow(mapOf(9 to device(9, vendorId = 0x054C, productId = 0x09CC)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    modelHasTouchpad = true,
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val controller = composer.capabilityFor("9").controller
            assertFalse(Feature.TOUCHPAD in controller)
            assertFalse(Feature.MOUSE in controller)
            assertEquals(TouchpadSource.NONE, composer.touchpadSource("9"))
        }

    @Test
    fun `a trackpad-bearing pad claimed USB-direct sources its own touch`() =
        composerTest {
            val devices =
                MutableStateFlow(
                    mapOf(9 to device(9, vendorId = 0x054C, productId = 0x09CC, isUsbSynthetic = true)),
                )
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    modelHasTouchpad = true,
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val controller = composer.capabilityFor("9").controller
            assertTrue(Feature.TOUCHPAD in controller)
            assertTrue(Feature.MOUSE in controller)
            assertEquals(TouchpadSource.PAD, composer.touchpadSource("9"))
        }

    @Test
    fun `a trackpad-less pad keeps the phone screen as its touch source`() =
        composerTest {
            val devices = MutableStateFlow(mapOf(9 to device(9)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    modelHasTouchpad = false,
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val controller = composer.capabilityFor("9").controller
            assertTrue(Feature.TOUCHPAD in controller)
            assertTrue(Feature.MOUSE in controller)
            assertEquals(TouchpadSource.PHONE, composer.touchpadSource("9"))
        }

    @Test
    fun `the virtual slot is phone-sourced and an unknown slot has no source`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                )
            assertEquals(TouchpadSource.PHONE, composer.touchpadSource(VIRTUAL_SLOT_ID))
            assertEquals(TouchpadSource.NONE, composer.touchpadSource("404"))
        }

    // ── touchpadWireMode: the descriptor projection ─────────────────────────

    @Test
    fun `touchpadWireMode declares the pick when every gate carries`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A")),
                    connections = MutableStateFlow(listOf(summary("sat-A"))),
                    scope = backgroundScope,
                    touchpadMode = MutableStateFlow(mapOf("sat-A" to "ds4")),
                    satTypes = MutableStateFlow(mapOf(("sat-A" to VIRTUAL_SLOT_ID) to CONTROLLER_TYPE_PLAYSTATION)),
                )
            assertEquals("ds4", composer.touchpadWireMode(VIRTUAL_SLOT_ID))
        }

    @Test
    fun `touchpadWireMode collapses a ds4 pick on an xbox-typed slot`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A")),
                    connections = MutableStateFlow(listOf(summary("sat-A"))),
                    scope = backgroundScope,
                    touchpadMode = MutableStateFlow(mapOf("sat-A" to "ds4")),
                    satTypes = MutableStateFlow(mapOf(("sat-A" to VIRTUAL_SLOT_ID) to CONTROLLER_TYPE_XBOX)),
                )
            assertEquals("off", composer.touchpadWireMode(VIRTUAL_SLOT_ID))
        }

    @Test
    fun `touchpadWireMode is off for an unbound slot and for a never-picked satellite`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A")),
                    connections = MutableStateFlow(listOf(summary("sat-A"))),
                    scope = backgroundScope,
                    satTypes = MutableStateFlow(mapOf(("sat-A" to VIRTUAL_SLOT_ID) to CONTROLLER_TYPE_PLAYSTATION)),
                )
            assertEquals("off", composer.touchpadWireMode("unbound-slot"))
            assertEquals("off", composer.touchpadWireMode(VIRTUAL_SLOT_ID))
        }

    @Test
    fun `touchpadWireMode gates a mouse pick on the host grant`() =
        composerTest {
            val withheld =
                MutableStateFlow(
                    mapOf(
                        "sat-A" to
                            HostFeatureSet(
                                hasCatalog = true,
                                mouseControl = false,
                                keyboardControl = false,
                                rumbleReturn = true,
                            ),
                    ),
                )
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A")),
                    connections = MutableStateFlow(listOf(summary("sat-A"))),
                    scope = backgroundScope,
                    touchpadMode = MutableStateFlow(mapOf("sat-A" to "mouse")),
                    hostFeaturesState = withheld,
                    satTypes = MutableStateFlow(mapOf(("sat-A" to VIRTUAL_SLOT_ID) to CONTROLLER_TYPE_XBOX)),
                )
            assertEquals("off", composer.touchpadWireMode(VIRTUAL_SLOT_ID))

            // An unfetched host keeps the optimistic default: mouse has always been grantable.
            withheld.value = emptyMap()
            assertEquals("mouse", composer.touchpadWireMode(VIRTUAL_SLOT_ID))
        }

    @Test
    fun `touchpadWireMode drops a ds4 pick for a pad whose touch is uncapturable`() =
        composerTest {
            val devices = MutableStateFlow(mapOf(9 to device(9, vendorId = 0x054C, productId = 0x09CC)))
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = devices,
                    bindings = MutableStateFlow(mapOf("9" to "sat-A")),
                    connections = MutableStateFlow(listOf(summary("sat-A"))),
                    scope = backgroundScope,
                    touchpadMode = MutableStateFlow(mapOf("sat-A" to "ds4")),
                    satTypes = MutableStateFlow(mapOf(("sat-A" to "9") to CONTROLLER_TYPE_PLAYSTATION)),
                    modelHasTouchpad = true,
                )
            assertEquals("off", composer.touchpadWireMode("9"))

            // The same pad claimed USB-direct captures its trackpad, so the pick carries.
            devices.value = mapOf(9 to device(9, vendorId = 0x054C, productId = 0x09CC, isUsbSynthetic = true))
            assertEquals("ds4", composer.touchpadWireMode("9"))
        }
}
