// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.net.moonlight.AUTO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.source.store.SatelliteHostRuntime
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The layers a bound slot takes from its host kind, and the projections read on top of the
// composed map. Split from CapabilityComposerTest to stay under the class-size gate.
class CapabilityComposerLayersTest {
    // ── bound Moonlight and Bluetooth slots: the layers a satellite never takes ──

    @Test
    fun `a bound moonlight slot on Auto resolves like the session, from the pad's own motion`() =
        composerTest {
            val onAuto = mapOf(VIRTUAL_SLOT_ID to AUTO)
            val withGyro =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "ml-A")),
                    connections =
                        MutableStateFlow(
                            listOf(summary("ml-A", kind = ConnectionKind.MOONLIGHT, satelliteControllerTypes = onAuto)),
                        ),
                    scope = backgroundScope,
                )
            val withoutGyro =
                composerFor(
                    phoneAvailable = false,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "ml-A")),
                    connections =
                        MutableStateFlow(
                            listOf(summary("ml-A", kind = ConnectionKind.MOONLIGHT, satelliteControllerTypes = onAuto)),
                        ),
                    scope = backgroundScope,
                )
            withGyro.probe(this)
            withoutGyro.probe(this)
            testScheduler.runCurrent()
            assertEquals(moonlightTypeCapabilities(PLAYSTATION), withGyro.capabilityFor(VIRTUAL_SLOT_ID).type)
            assertEquals(moonlightTypeCapabilities(XBOX), withoutGyro.capabilityFor(VIRTUAL_SLOT_ID).type)
        }

    @Test
    fun `an explicit moonlight pick is never re-resolved by the composer`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "ml-A")),
                    connections =
                        MutableStateFlow(
                            listOf(
                                summary("ml-A", kind = ConnectionKind.MOONLIGHT, satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to XBOX)),
                            ),
                        ),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            assertEquals(moonlightTypeCapabilities(XBOX), composer.capabilityFor(VIRTUAL_SLOT_ID).type)
        }

    @Test
    fun `a moonlight candidate on Auto resolves from the pad's own motion too`() =
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
            val caps =
                composer.capabilityForCandidate(
                    slotId = VIRTUAL_SLOT_ID,
                    candidateType = AUTO,
                    candidateHostKind = ConnectionKind.MOONLIGHT,
                    candidateHostId = "ml-A",
                )
            assertEquals(moonlightTypeCapabilities(PLAYSTATION), caps.type)
            assertTrue(caps.isAvailable(Feature.MOTION))
        }

    @Test
    fun `a bound moonlight slot takes the moonlight host layer`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "ml-A")),
                    connections = MutableStateFlow(listOf(summary("ml-A", kind = ConnectionKind.MOONLIGHT))),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps = composer.capabilityFor(VIRTUAL_SLOT_ID)
            assertEquals(HOST_LAYER, caps.host)
            assertEquals(transportProfileFor(ConnectionKind.MOONLIGHT), caps.transport)
        }

    @Test
    fun `a bound bluetooth slot is gated by the transport alone`() =
        composerTest {
            val every = CapabilitySet(Feature.entries.toSet())
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "bt-A")),
                    connections =
                        MutableStateFlow(
                            listOf(
                                summary(
                                    "bt-A",
                                    kind = ConnectionKind.BLUETOOTH,
                                    satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_XBOX),
                                ),
                            ),
                        ),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps = composer.capabilityFor(VIRTUAL_SLOT_ID)
            assertEquals(every, caps.type)
            assertEquals(every, caps.host)
            assertEquals(transportProfileFor(ConnectionKind.BLUETOOTH), caps.transport)
            assertFalse(caps.isAvailable(Feature.MOUSE))
        }

    @Test
    fun `a bound satellite slot with no stored type has a permissive type layer`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A")),
                    connections = MutableStateFlow(listOf(summary("sat-A"))),
                    scope = backgroundScope,
                )
            composer.probe(this)
            testScheduler.runCurrent()
            assertEquals(CapabilitySet(Feature.entries.toSet()), composer.capabilityFor(VIRTUAL_SLOT_ID).type)
        }

    @Test
    fun `a type id missing from the cached catalog falls back to the bundled set`() =
        composerTest {
            val xboxOnlyCatalog = CatalogDto(controllerTypes = listOf(CatalogTypeDto(id = CONTROLLER_TYPE_XBOX, slug = "xbox360")))
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A")),
                    connections =
                        MutableStateFlow(
                            listOf(summary("sat-A", satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION))),
                        ),
                    scope = backgroundScope,
                    stores = StoreStates(cachedCatalog = xboxOnlyCatalog),
                )
            composer.probe(this)
            testScheduler.runCurrent()
            assertEquals(typeCapabilitiesById(CONTROLLER_TYPE_PLAYSTATION), composer.capabilityFor(VIRTUAL_SLOT_ID).type)
        }

    @Test
    fun `a healthy backend crosses nothing out of live`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A")),
                    connections =
                        MutableStateFlow(
                            listOf(summary("sat-A", satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION))),
                        ),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            backendStatus =
                                MutableStateFlow(
                                    mapOf(
                                        ("sat-A" to VIRTUAL_SLOT_ID) to
                                            SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = true),
                                    ),
                                ),
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()
            val caps = composer.capabilityFor(VIRTUAL_SLOT_ID)
            assertEquals(CapabilitySet.EMPTY, caps.runtimeDown)
            assertTrue(Feature.MOTION in caps.live)
        }

    @Test
    fun `inputFunctionsFor reports the phone for the virtual slot`() =
        composerTest {
            val withGyro =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                )
            val withoutGyro =
                composerFor(
                    phoneAvailable = false,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                )
            assertEquals(
                InputFunctions(known = true, rumble = false, gyro = true, touchpad = true),
                withGyro.inputFunctionsFor(VIRTUAL_SLOT_ID, direct = null),
            )
            assertEquals(
                InputFunctions(known = true, rumble = false, gyro = false, touchpad = true),
                withoutGyro.inputFunctionsFor(VIRTUAL_SLOT_ID, direct = null),
            )
        }

    @Test
    fun `inputFunctionsFor is known and empty for a slot no pad backs`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                )
            assertEquals(
                InputFunctions(known = true, rumble = false, gyro = false, touchpad = false),
                composer.inputFunctionsFor("404", direct = null),
            )
            assertEquals(
                InputFunctions(known = true, rumble = false, gyro = false, touchpad = false),
                composer.inputFunctionsFor("not-a-slot", direct = true),
            )
        }

    @Test
    fun `touchpadWireMode assumes a satellite when the summary is missing`() =
        composerTest {
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-gone")),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            satTypes = MutableStateFlow(mapOf(("sat-gone" to VIRTUAL_SLOT_ID) to CONTROLLER_TYPE_PLAYSTATION)),
                        ),
                )
            assertEquals("ds4", composer.touchpadWireMode(VIRTUAL_SLOT_ID))
        }

    // ── wireProjection: the descriptor's own change detector ──

    @Test
    fun `wireProjection re-emits when the mouse surface opens and not for a no-op flip`() =
        composerTest {
            val mouseSurface = MutableStateFlow<Set<String>>(emptySet())
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A")),
                    connections = MutableStateFlow(listOf(summary("sat-A"))),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            mouseSurface = mouseSurface,
                            satTypes = MutableStateFlow(mapOf(("sat-A" to VIRTUAL_SLOT_ID) to CONTROLLER_TYPE_PLAYSTATION)),
                        ),
                )
            composer.probe(this)
            val projections = mutableListOf<Map<String, WireProjection>>()
            composer.wireProjection.onEach { projections += it }.launchIn(backgroundScope)
            testScheduler.runCurrent()
            val before = projections.size
            assertEquals("ds4", projections.last().getValue(VIRTUAL_SLOT_ID).touchpadMode)

            mouseSurface.value = setOf(VIRTUAL_SLOT_ID)
            testScheduler.runCurrent()
            assertEquals(before + 1, projections.size)
            assertEquals("mouse", projections.last().getValue(VIRTUAL_SLOT_ID).touchpadMode)

            mouseSurface.value = setOf(VIRTUAL_SLOT_ID, "some-other-slot")
            testScheduler.runCurrent()
            assertEquals(before + 1, projections.size)
        }

    @Test
    fun `wireProjection is silent for a change that moves no descriptor field`() =
        composerTest {
            val hostRuntime = MutableStateFlow<Map<String, SatelliteHostRuntime>>(emptyMap())
            val backendStatus = MutableStateFlow<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(emptyMap())
            val composer =
                composerFor(
                    phoneAvailable = true,
                    devices = MutableStateFlow(emptyMap()),
                    bindings = MutableStateFlow(mapOf(VIRTUAL_SLOT_ID to "sat-A")),
                    connections =
                        MutableStateFlow(
                            listOf(summary("sat-A", satelliteControllerTypes = mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_PLAYSTATION))),
                        ),
                    scope = backgroundScope,
                    stores = StoreStates(hostRuntime = hostRuntime, backendStatus = backendStatus),
                )
            composer.probe(this)
            val projections = mutableListOf<Map<String, WireProjection>>()
            composer.wireProjection.onEach { projections += it }.launchIn(backgroundScope)
            testScheduler.runCurrent()
            val before = projections.size

            // The backend going down moves runtimeDown, which the descriptor deliberately ignores.
            backendStatus.value =
                mapOf(("sat-A" to VIRTUAL_SLOT_ID) to SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = false))
            testScheduler.runCurrent()
            assertFalse(Feature.MOTION in composer.capabilityFor(VIRTUAL_SLOT_ID).live)
            assertEquals(before, projections.size)
        }
}
