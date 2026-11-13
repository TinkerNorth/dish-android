// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_MOUSE
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_OFF
import com.tinkernorth.dish.repository.TrackpadPick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

// A Direct Steam Controller bound to a satellite: the trackpad pick decides the descriptor's
// touchpadMode, and the native router is told the same pick.
class CapabilityComposerTrackpadTest {
    private fun TestScope.steamSlot(
        model: Int,
        type: Int,
        stores: StoreStates = StoreStates(),
    ): CapabilityComposer {
        stores.satTypes.value = mapOf((HOST to SLOT) to type)
        val composer =
            composerFor(
                phoneAvailable = false,
                devices = MutableStateFlow(mapOf(DEVICE to device(DEVICE, vendorId = VALVE, productId = PRODUCT, isUsbSynthetic = true))),
                bindings = MutableStateFlow(mapOf(SLOT to HOST)),
                connections = MutableStateFlow(listOf(summary(HOST, satelliteControllerTypes = mapOf(SLOT to type)))),
                scope = backgroundScope,
                stores = stores,
                model = ModelFacts(steamTrackpads = model),
            )
        composer.probe(this)
        testScheduler.runCurrent()
        return composer
    }

    @Test
    fun `the 2015 pad is the right stick by default, so its descriptor carries no surface`() =
        composerTest {
            val composer = steamSlot(model = PAD_2015, type = CONTROLLER_TYPE_PLAYSTATION)
            assertEquals(TrackpadPick.STICK, composer.trackpadPickFor(SLOT))
            assertEquals(TOUCHPAD_MODE_OFF, composer.touchpadWireMode(SLOT))
            assertEquals(mapOf(DEVICE to TrackpadPick.STICK), composer.trackpadPicks.first())
        }

    @Test
    fun `a pad pick on a type with a surface is the DS4 pad mode and reaches the native router`() =
        composerTest {
            val stores = StoreStates(trackpadPicks = MutableStateFlow(mapOf(SLOT to TrackpadPick.PAD)))
            val composer = steamSlot(model = PAD_2015, type = CONTROLLER_TYPE_PLAYSTATION, stores = stores)
            assertEquals(TrackpadPick.PAD, composer.trackpadPickFor(SLOT))
            assertEquals(TOUCHPAD_MODE_DS4, composer.touchpadWireMode(SLOT))
            assertEquals(mapOf(DEVICE to TrackpadPick.PAD), composer.trackpadPicks.first())
        }

    @Test
    fun `a pad pick on a type without a surface is held to off`() =
        composerTest {
            val stores = StoreStates(trackpadPicks = MutableStateFlow(mapOf(SLOT to TrackpadPick.PAD)))
            val composer = steamSlot(model = PAD_2015, type = CONTROLLER_TYPE_XBOX, stores = stores)
            assertEquals(TrackpadPick.OFF, composer.trackpadPickFor(SLOT))
            assertEquals(TOUCHPAD_MODE_OFF, composer.touchpadWireMode(SLOT))
        }

    @Test
    fun `a mouse pick is the mouse mode where the satellite grants a mouse`() =
        composerTest {
            val stores = StoreStates(trackpadPicks = MutableStateFlow(mapOf(SLOT to TrackpadPick.MOUSE)))
            val composer = steamSlot(model = PAD_2026, type = CONTROLLER_TYPE_XBOX, stores = stores)
            assertEquals(TrackpadPick.MOUSE, composer.trackpadPickFor(SLOT))
            assertEquals(TOUCHPAD_MODE_MOUSE, composer.touchpadWireMode(SLOT))
        }

    @Test
    fun `the 2026 pad defaults to the surface when the type has one and to off when it does not`() =
        composerTest {
            assertEquals(TrackpadPick.PAD, steamSlot(model = PAD_2026, type = CONTROLLER_TYPE_PLAYSTATION).trackpadPickFor(SLOT))
            assertEquals(TrackpadPick.OFF, steamSlot(model = PAD_2026, type = CONTROLLER_TYPE_XBOX).trackpadPickFor(SLOT))
        }

    @Test
    fun `a pad without Steam trackpads offers no pick and is not told one`() =
        composerTest {
            val composer = steamSlot(model = NO_TRACKPADS, type = CONTROLLER_TYPE_PLAYSTATION)
            assertEquals(emptyList<TrackpadPick>(), composer.trackpadPickChoicesFor(SLOT))
            assertEquals(emptyMap<Int, TrackpadPick>(), composer.trackpadPicks.first())
        }

    private companion object {
        const val DEVICE = -1000
        const val SLOT = "-1000"
        const val HOST = "sat-A"
        const val VALVE = 0x28DE
        const val PRODUCT = 0x1102
        const val NO_TRACKPADS = 0
        const val PAD_2015 = 1
        const val PAD_2026 = 2
    }
}
