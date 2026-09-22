// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.ControllerDescriptor
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlProtocol
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.PadAudioRoute
import com.tinkernorth.dish.source.audio.PadAudioRoutes
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MouseSurfaceStore
import com.tinkernorth.dish.source.store.SatelliteHostFacts
import com.tinkernorth.dish.source.store.SlotToggleStores
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The framework light bar's place in the capability model: it rides the Bluetooth transport only
// (the Android lights API cannot write a USB pad's LED nodes), and folds into the satellite
// descriptor bit and the Moonlight RGB-LED bit like any other actuator capability.
class CapabilityComposerLightbarTest {
    private fun btPad(
        id: Int,
        hasLightbar: Boolean,
        transport: Transport = Transport.Bluetooth,
        vendorId: Int = 0,
        productId: Int = 0,
    ) = PhysicalGamepadRegistry.Device(
        id = id,
        name = "Pad-$id",
        hasLightbar = hasLightbar,
        transport = transport,
        vendorId = vendorId,
        productId = productId,
    )

    private fun satellite(id: String) =
        ConnectionSummary(
            id = id,
            kind = ConnectionKind.SATELLITE,
            label = id,
            detail = "",
            live = LinkState.Connected,
            boundSlotIds = emptyList(),
        )

    private fun composer(
        devices: Map<Int, PhysicalGamepadRegistry.Device>,
        bindings: Map<String, String> = emptyMap(),
        connections: List<ConnectionSummary> = emptyList(),
        scope: CoroutineScope,
        modelHasLightbar: Boolean = false,
    ): CapabilityComposer {
        val availability: PhoneMotionAvailability = mockk { every { hasGyro } returns false }
        val registry: PhysicalGamepadRegistry =
            mockk {
                every { this@mockk.devices } returns MutableStateFlow(devices)
                every { frameworkCapsFor(any(), any()) } returns null
            }
        val hub: ConnectionCoordinator =
            mockk {
                every { this@mockk.bindings } returns MutableStateFlow(bindings)
                every { this@mockk.connections } returns MutableStateFlow(connections)
                every { satTypes } returns MutableStateFlow(emptyMap())
            }
        val native: PhysicalInputNative =
            mockk {
                every { modelHasImu(any(), any()) } returns false
                every { modelHasRumble(any(), any()) } returns false
                every { modelHasTouchpad(any(), any()) } returns false
                every { modelHasLightbar(any(), any()) } returns modelHasLightbar
                every { modelHasPlayerLeds(any(), any()) } returns false
                every { modelHasTriggerEffects(any(), any()) } returns false
                every { modelHasTriggerRumble(any(), any()) } returns false
                every { isKnownFastLaneModel(any(), any()) } returns true
            }
        val routes: PadAudioRoutes =
            mockk {
                every { state } returns MutableStateFlow(emptyMap())
                every { routeFor(any(), any()) } returns PadAudioRoute.NONE
            }
        val mouseSurface: MouseSurfaceStore =
            mockk {
                every { state } returns MutableStateFlow(emptySet())
                every { isOpen(any()) } returns false
            }
        val hostFacts =
            SatelliteHostFacts(
                features =
                    mockk {
                        every { state } returns MutableStateFlow(emptyMap())
                        every { featuresFor(any()) } returns null
                    },
                runtime = mockk { every { runtimeFor(any()) } returns null },
                motionBackend = mockk { every { state } returns MutableStateFlow(emptyMap()) },
                catalog = mockk { every { cached(any()) } returns null },
                capabilities = mockk(),
            )
        val toggles =
            SlotToggleStores(
                motion = mockk { every { state } returns MutableStateFlow(emptyMap()) },
                rumble = mockk { every { state } returns MutableStateFlow(emptyMap()) },
                mic = mockk { every { state } returns MutableStateFlow(emptyMap()) },
                speaker = mockk { every { state } returns MutableStateFlow(emptyMap()) },
            )
        return CapabilityComposer(
            availability,
            registry,
            hub,
            native,
            toggles,
            routes,
            mouseSurface,
            hostFacts,
            scope,
        )
    }

    @Test
    fun `a Bluetooth framework pad with an RGB light advertises the light bar`() =
        composerTest {
            val composer = composer(mapOf(9 to btPad(9, hasLightbar = true)), scope = backgroundScope)
            composer.probe(this)
            testScheduler.runCurrent()
            assertTrue(Feature.LIGHTBAR in composer.capabilityFor("9").controller)
        }

    @Test
    fun `a USB framework pad with an RGB light does not advertise the light bar`() =
        composerTest {
            // The Android lights API cannot write a USB pad's LED nodes (SELinux), so its bar stays
            // a Direct-only surface even though the framework lists the light.
            val composer =
                composer(mapOf(9 to btPad(9, hasLightbar = true, transport = Transport.Usb)), scope = backgroundScope)
            composer.probe(this)
            testScheduler.runCurrent()
            assertFalse(Feature.LIGHTBAR in composer.capabilityFor("9").controller)
        }

    @Test
    fun `a Bluetooth framework pad without an RGB light has no light bar`() =
        composerTest {
            val composer = composer(mapOf(9 to btPad(9, hasLightbar = false)), scope = backgroundScope)
            composer.probe(this)
            testScheduler.runCurrent()
            assertFalse(Feature.LIGHTBAR in composer.capabilityFor("9").controller)
        }

    @Test
    fun `wireCapsFor sets CAP_LIGHTBAR for a Bluetooth framework pad bound to a satellite`() =
        composerTest {
            val composer =
                composer(
                    devices = mapOf(9 to btPad(9, hasLightbar = true)),
                    bindings = mapOf("9" to "sat-A"),
                    connections = listOf(satellite("sat-A")),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps = composer.wireCapsFor("9")
            assertEquals(ControllerDescriptor.CAP_LIGHTBAR, caps and ControllerDescriptor.CAP_LIGHTBAR)
        }

    @Test
    fun `a Bluetooth pad on a Moonlight PlayStation host folds the light bar into the RGB LED bit`() =
        composerTest {
            val composer = composer(mapOf(9 to btPad(9, hasLightbar = true)), scope = backgroundScope)
            composer.probe(this)
            testScheduler.runCurrent()
            val caps =
                composer.capabilityForCandidate(
                    slotId = "9",
                    candidateType = MoonlightEmulatedType.PLAYSTATION,
                    candidateHostKind = ConnectionKind.MOONLIGHT,
                    candidateHostId = "moon-A",
                )
            assertTrue(Feature.LIGHTBAR in caps.available)
            val bits = MoonlightCatalog.capabilityBits(MoonlightEmulatedType.PLAYSTATION, caps.available)
            assertEquals(MoonlightControlProtocol.CAP_RGB_LED, bits and MoonlightControlProtocol.CAP_RGB_LED)
        }

    @Test
    fun `the Direct draft preview unlocks the light bar a USB pad cannot drive on Standard`() =
        composerTest {
            // A USB DualSense: Standard has no writable LED route, but Direct claims the raw HID and
            // drives the bar, so the draft toggle flips the light-bar verdict.
            val composer =
                composer(
                    devices =
                        mapOf(9 to btPad(9, hasLightbar = true, transport = Transport.Usb, vendorId = 0x054C, productId = 0x0CE6)),
                    scope = backgroundScope,
                    modelHasLightbar = true,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val standard =
                composer.capabilityForCandidate(
                    slotId = "9",
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.SATELLITE,
                    candidateHostId = "sat-A",
                    candidateDirect = false,
                )
            val direct =
                composer.capabilityForCandidate(
                    slotId = "9",
                    candidateType = CONTROLLER_TYPE_PLAYSTATION,
                    candidateHostKind = ConnectionKind.SATELLITE,
                    candidateHostId = "sat-A",
                    candidateDirect = true,
                )
            assertFalse(Feature.LIGHTBAR in standard.controller)
            assertTrue(Feature.LIGHTBAR in direct.controller)
        }
}
