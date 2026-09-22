// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.PadAudioRoute
import com.tinkernorth.dish.source.audio.PadAudioRoutes
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The physical pad's controller-audio layer: what the route table names for a USB pad on
// either HID path, and nothing for a Bluetooth one. Split from CapabilityComposerTest so
// each suite stays under the class-size gate; the harness is shared.
class CapabilityComposerPadAudioTest {
    @Test
    fun `a USB-direct pad with no audio route advertises neither endpoint`() =
        composerTest {
            // Every model probe says yes; the OS route table is the one that matters, and
            // it is empty, so the caps stay off.
            val devices = MutableStateFlow(mapOf(-1000 to device(-1000, vendorId = 0x054C, productId = 0x0CE6, isUsbSynthetic = true)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    model =
                        ModelFacts(
                            modelHasLightbar = true,
                            modelHasPlayerLeds = true,
                            modelHasTriggerEffects = true,
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val controller = composer.capabilityFor("-1000").controller
            assertFalse(Feature.MIC in controller)
            assertFalse(Feature.SPEAKER in controller)
            // The surfaces the model DB does own are unaffected.
            assertTrue(Feature.LIGHTBAR in controller)
        }

    @Test
    fun `a USB-direct pad advertises exactly the endpoints the route table reports`() =
        composerTest {
            val routes =
                MutableStateFlow(
                    mapOf(
                        PadAudioRoutes.key(0x054C, 0x0CE6) to PadAudioRoute(microphone = true, speaker = true),
                    ),
                )
            val devices = MutableStateFlow(mapOf(-1000 to device(-1000, vendorId = 0x054C, productId = 0x0CE6, isUsbSynthetic = true)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            padAudioRoutes = routes,
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val both = composer.capabilityFor("-1000").controller
            assertTrue(Feature.MIC in both)
            assertTrue(Feature.SPEAKER in both)

            // A headset-less pad that only plays: one endpoint, not the pair.
            routes.value =
                mapOf(PadAudioRoutes.key(0x054C, 0x0CE6) to PadAudioRoute(microphone = false, speaker = true))
            testScheduler.runCurrent()
            val speakerOnly = composer.capabilityFor("-1000").controller
            assertFalse(Feature.MIC in speakerOnly)
            assertTrue(Feature.SPEAKER in speakerOnly)
        }

    @Test
    fun `a route for another pad never lands on this one`() =
        composerTest {
            val routes =
                MutableStateFlow(
                    mapOf(PadAudioRoutes.key(0x054C, 0x09CC) to PadAudioRoute(microphone = true, speaker = true)),
                )
            val devices = MutableStateFlow(mapOf(-1000 to device(-1000, vendorId = 0x054C, productId = 0x0CE6, isUsbSynthetic = true)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            padAudioRoutes = routes,
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val controller = composer.capabilityFor("-1000").controller
            assertFalse(Feature.MIC in controller)
            assertFalse(Feature.SPEAKER in controller)
        }

    @Test
    fun `a framework-path USB pad advertises the same endpoints a claim would`() =
        composerTest {
            // Same pad, same endpoints, not claimed: the audio function is the OS's on either
            // path, so the route table answers for a framework pad exactly as for a Direct one.
            val routes =
                MutableStateFlow(
                    mapOf(
                        PadAudioRoutes.key(0x054C, 0x0CE6) to
                            PadAudioRoute(microphone = true, speaker = true, haptics = true, playbackChannels = 4),
                    ),
                )
            val devices = MutableStateFlow(mapOf(9 to device(9, vendorId = 0x054C, productId = 0x0CE6)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            padAudioRoutes = routes,
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val controller = composer.capabilityFor("9").controller
            assertTrue(Feature.MIC in controller)
            assertTrue(Feature.SPEAKER in controller)
            assertTrue(Feature.HAPTIC_AUDIO in controller)
        }

    @Test
    fun `a Bluetooth pad never advertises audio, even with a USB twin's route published`() =
        composerTest {
            // No audio function over Bluetooth, and the same vendor:product as a USB DualSense.
            val routes =
                MutableStateFlow(
                    mapOf(PadAudioRoutes.key(0x054C, 0x0CE6) to PadAudioRoute(microphone = true, speaker = true)),
                )
            val devices =
                MutableStateFlow(
                    mapOf(9 to device(9, vendorId = 0x054C, productId = 0x0CE6, transport = Transport.Bluetooth)),
                )
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            padAudioRoutes = routes,
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val controller = composer.capabilityFor("9").controller
            assertFalse(Feature.MIC in controller)
            assertFalse(Feature.SPEAKER in controller)
            assertFalse(Feature.HAPTIC_AUDIO in controller)
        }

    @Test
    fun `haptics are advertised only where the route names the lanes`() =
        composerTest {
            val routes =
                MutableStateFlow(
                    mapOf(
                        PadAudioRoutes.key(0x054C, 0x0CE6) to
                            PadAudioRoute(microphone = true, speaker = true, haptics = false, playbackChannels = 2),
                    ),
                )
            val devices =
                MutableStateFlow(mapOf(9 to device(9, vendorId = 0x054C, productId = 0x0CE6, isUsbSynthetic = true)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(emptyMap()),
                    connections = MutableStateFlow(emptyList()),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            padAudioRoutes = routes,
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val controller = composer.capabilityFor("9").controller
            assertTrue(Feature.SPEAKER in controller)
            assertFalse(Feature.HAPTIC_AUDIO in controller)
        }
}
