// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A physical pad's own touch surface on the framework paths: sourced through pointer capture
// where Android exposed the surface, nothing where it did not. Split from
// CapabilityComposerTest so each suite stays under the class-size gate.
class CapabilityComposerTouchpadTest {
    private val ds4 = 0x054C to 0x09CC

    @Test
    fun `a framework DualShock 4 whose surface Android exposed sources its own touch`() =
        composerTest {
            val devices =
                MutableStateFlow(
                    mapOf(
                        7 to device(7, vendorId = ds4.first, productId = ds4.second, transport = Transport.Bluetooth, touchpadDeviceId = 7),
                    ),
                )
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    model =
                        ModelFacts(
                            modelHasTouchpad = true,
                            frameworkCaps = PhysicalGamepadRegistry.FrameworkCaps(hasGyro = false, hasRumble = true, hasTouchpad = true),
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            assertEquals(TouchpadSource.PAD, composer.touchpadSource("7"))
            val controller = composer.capabilityFor("7").controller
            assertTrue(Feature.TOUCHPAD in controller)
            assertTrue(Feature.MOUSE in controller)
            assertTrue(composer.inputFunctionsFor("7", direct = false).touchpad)
        }

    @Test
    fun `the same pad with no exposed surface sources nothing, not the phone screen`() =
        composerTest {
            // A trackpad-bearing pad never gets the phone overlay (two producers on one
            // stream), and without capture there is no way at its own surface either.
            val devices =
                MutableStateFlow(mapOf(7 to device(7, vendorId = ds4.first, productId = ds4.second, touchpadDeviceId = null)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    model =
                        ModelFacts(
                            modelHasTouchpad = true,
                            frameworkCaps = PhysicalGamepadRegistry.FrameworkCaps(hasGyro = false, hasRumble = true, hasTouchpad = false),
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            assertEquals(TouchpadSource.NONE, composer.touchpadSource("7"))
            assertFalse(Feature.TOUCHPAD in composer.capabilityFor("7").controller)
            assertFalse(composer.inputFunctionsFor("7", direct = false).touchpad)
        }

    @Test
    fun `an exposed surface on a pad whose model has no trackpad is not a touchpad`() =
        composerTest {
            // A pad that merges some pointer sub-device is still not a DualShock: the model
            // tables gate, so the app neither captures nor advertises.
            val devices = MutableStateFlow(mapOf(9 to device(9, vendorId = 0x045E, productId = 0x02EA, touchpadDeviceId = 9)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    model =
                        ModelFacts(
                            modelHasTouchpad = false,
                            frameworkCaps = PhysicalGamepadRegistry.FrameworkCaps(hasGyro = false, hasRumble = true, hasTouchpad = true),
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            // No trackpad at all falls back to the phone screen, as before.
            assertEquals(TouchpadSource.PHONE, composer.touchpadSource("9"))
            assertFalse(composer.inputFunctionsFor("9", direct = false).touchpad)
        }

    @Test
    fun `a Direct pad still sources its own touch from the raw report`() =
        composerTest {
            val devices =
                MutableStateFlow(mapOf(-1000 to device(-1000, vendorId = ds4.first, productId = ds4.second, isUsbSynthetic = true)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    model =
                        ModelFacts(
                            modelHasTouchpad = true,
                            knownFastLane = true,
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            assertEquals(TouchpadSource.PAD, composer.touchpadSource("-1000"))
            assertTrue(Feature.TOUCHPAD in composer.capabilityFor("-1000").controller)
        }
}
