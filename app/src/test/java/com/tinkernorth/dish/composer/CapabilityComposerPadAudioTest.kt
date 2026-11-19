// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.composerTest
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.core.input.vidPidKey
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.net.ControllerDescriptor
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.PadAudioRoute
import com.tinkernorth.dish.source.audio.PlayoutLane
import com.tinkernorth.dish.source.store.PadAudioRefusal
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
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
                        vidPidKey(0x054C, 0x0CE6) to PadAudioRoute(microphone = true, speaker = true),
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
                mapOf(vidPidKey(0x054C, 0x0CE6) to PadAudioRoute(microphone = false, speaker = true))
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
                    mapOf(vidPidKey(0x054C, 0x09CC) to PadAudioRoute(microphone = true, speaker = true)),
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
                        vidPidKey(0x054C, 0x0CE6) to
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
                    mapOf(vidPidKey(0x054C, 0x0CE6) to PadAudioRoute(microphone = true, speaker = true)),
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
                        vidPidKey(0x054C, 0x0CE6) to
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

    @Test
    fun `a host granting the microphone alone gets the microphone alone, on the card and on the wire`() =
        composerTest {
            // A USB Direct DualSense whose route names all three endpoints at four channels, every
            // switch on, bound to a satellite that grants the microphone and nothing else: the
            // pills and the descriptor read one answer, so the host is never promised a lane the
            // phone will not open.
            val routes =
                MutableStateFlow(
                    mapOf(
                        vidPidKey(0x054C, 0x0CE6) to
                            PadAudioRoute(microphone = true, speaker = true, haptics = true, playbackChannels = 4),
                    ),
                )
            val devices =
                MutableStateFlow(mapOf(9 to device(9, vendorId = 0x054C, productId = 0x0CE6, isUsbSynthetic = true)))
            val hostFeatures = MutableStateFlow(mapOf("sat-A" to hostGranting(mic = true, speaker = false, haptics = false)))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(mapOf("9" to "sat-A")),
                    connections = MutableStateFlow(listOf(summary("sat-A"))),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            micEnabled = MutableStateFlow(mapOf("9" to true)),
                            padAudioRoutes = routes,
                            hostFeaturesState = hostFeatures,
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val micGrantedAlone = composer.capabilityFor("9")
            assertTrue(Feature.MIC in micGrantedAlone.enabled)
            assertFalse(Feature.SPEAKER in micGrantedAlone.enabled)
            assertFalse(Feature.HAPTIC_AUDIO in micGrantedAlone.enabled)
            assertEquals(ControllerDescriptor.CAP_MIC, wireCaps(micGrantedAlone) and AUDIO_BITS)

            // The grant arriving, as the probe of the next session delivers it, puts both lanes on
            // the wire without anything else moving.
            hostFeatures.value = mapOf("sat-A" to hostGranting(mic = true, speaker = true, haptics = true))
            testScheduler.runCurrent()
            assertEquals(AUDIO_BITS, wireCaps(composer.capabilityFor("9")) and AUDIO_BITS)
        }

    @Test
    fun `an output the phone refused takes its lane off the wire until the endpoint changes`() =
        composerTest {
            val padRoute = PadAudioRoute(microphone = false, speaker = true, haptics = true, playbackDeviceId = 11, playbackChannels = 4)
            val routes = MutableStateFlow(mapOf(vidPidKey(0x054C, 0x0CE6) to padRoute))
            val devices =
                MutableStateFlow(mapOf(9 to device(9, vendorId = 0x054C, productId = 0x0CE6, isUsbSynthetic = true)))
            val hapticsRefusedAtEleven =
                PadAudioRefusal("9", PlayoutLane.HAPTICS, playbackDeviceId = 11, deviceChannels = 4)
            val refusals = MutableStateFlow(mapOf(("9" to PlayoutLane.HAPTICS) to hapticsRefusedAtEleven))
            val composer =
                composerFor(
                    phoneAvailable = false,
                    devices = devices,
                    bindings = MutableStateFlow(mapOf("9" to "sat-A")),
                    connections = MutableStateFlow(listOf(summary("sat-A"))),
                    scope = backgroundScope,
                    stores =
                        StoreStates(
                            padAudioRoutes = routes,
                            padAudioRefusals = refusals,
                            hostFeaturesState =
                                MutableStateFlow(mapOf("sat-A" to hostGranting(mic = false, speaker = true, haptics = true))),
                        ),
                )
            composer.probe(this)
            testScheduler.runCurrent()

            val refused = composer.capabilityFor("9")
            assertTrue(Feature.HAPTIC_AUDIO in refused.enabled)
            assertFalse(Feature.HAPTIC_AUDIO in refused.live)
            assertEquals(ControllerDescriptor.CAP_SPEAKER, wireCaps(refused) and AUDIO_BITS)

            // The pad enumerates afresh under another device id: the refusal is spent.
            routes.value = mapOf(vidPidKey(0x054C, 0x0CE6) to padRoute.copy(playbackDeviceId = 12))
            testScheduler.runCurrent()
            val replugged = composer.capabilityFor("9")
            assertTrue(Feature.HAPTIC_AUDIO in replugged.live)
            assertEquals(ControllerDescriptor.CAP_SPEAKER or ControllerDescriptor.CAP_HAPTIC_AUDIO, wireCaps(replugged) and AUDIO_BITS)
        }
}

private const val AUDIO_BITS =
    ControllerDescriptor.CAP_MIC or ControllerDescriptor.CAP_SPEAKER or ControllerDescriptor.CAP_HAPTIC_AUDIO

private fun hostGranting(
    mic: Boolean,
    speaker: Boolean,
    haptics: Boolean,
) = HostFeatureSet(
    hasCatalog = true,
    mouseControl = true,
    keyboardControl = false,
    rumbleReturn = true,
    controllerMic = mic,
    controllerSpeaker = speaker,
    controllerHapticAudio = haptics,
    protocolVersion = 4,
)
