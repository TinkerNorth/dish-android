// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkernorth.dish.DishApplication
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.SpeakerPlayoutComposer
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.FeedbackRouter
import com.tinkernorth.dish.integration.AppSingletons.fieldValue
import com.tinkernorth.dish.source.audio.NativeSpeakerFrameSource
import com.tinkernorth.dish.source.audio.SlotAudioRoutes
import com.tinkernorth.dish.source.audio.SpeakerEngine
import com.tinkernorth.dish.source.audio.SpeakerPlayoutSession
import com.tinkernorth.dish.source.audio.SpeakerPlayoutSink
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.MIC_LED_OFF
import com.tinkernorth.dish.source.store.MIC_LED_ON
import com.tinkernorth.dish.source.store.MIC_LED_PULSE
import com.tinkernorth.dish.source.store.SpeakerEnabledStore
import com.tinkernorth.dish.source.store.VirtualPadFeedbackStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Controller sound end to end against a real session: a host emitting MSG_SPEAKER_AUDIO, the real
 * native reorder window and libopus decoder behind it, the real capability composition deciding
 * which slot may play, and the playback engine putting whole 20 ms windows into an output.
 *
 * The output is a fake, because an emulator's AudioTrack proves nothing about the pipeline and
 * would make the assertion "audio was audible" rather than "audio arrived". Everything above it is
 * production code, including the composer that decides the slot is eligible and the toggle that
 * takes it away again.
 */
@RunWith(AndroidJUnit4::class)
class SpeakerPlaybackIntegrationTest {
    private val manager get() = AppSingletons.satellite

    private var fake: FakeSatellite? = null
    private var engine: SpeakerEngine? = null
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val owner = TestOwner()

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** An output that records what it was handed, and takes all of it. */
    private class RecordingSink : SpeakerPlayoutSink {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val played = ConcurrentLinkedQueue<ShortArray>()

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): SpeakerPlayoutSession {
            opens.incrementAndGet()
            return object : SpeakerPlayoutSession {
                override fun write(pcmStereo: ShortArray): Int {
                    played += pcmStereo
                    return pcmStereo.size
                }

                override fun close() {
                    closes.incrementAndGet()
                }
            }
        }
    }

    private val sink = RecordingSink()

    private val speakerEnabled: SpeakerEnabledStore
        get() = AppSingletons.capabilityComposer.fieldValue("speakerEnabled") as SpeakerEnabledStore

    /** What every layer of the model has settled on for the bound slot, the engine's own input. */
    private fun slotCapabilities(): SlotCapabilities =
        AppSingletons.capabilityComposer.state.value[VIRTUAL_SLOT_ID] ?: SlotCapabilities.NONE

    private val virtualFeedback: VirtualPadFeedbackStore
        get() {
            val app =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext.applicationContext as DishApplication
            val router: FeedbackRouter = app.feedbackRouter
            return router.fieldValue("virtualFeedback") as VirtualPadFeedbackStore
        }

    @Before
    fun setUp() {
        AppSingletons.resetConnections()
        speakerEnabled.setEnabled(VIRTUAL_SLOT_ID, true)
    }

    @After
    fun tearDown() {
        owner.registry.currentState = Lifecycle.State.CREATED
        engine = null
        engineScope.cancel()
        speakerEnabled.setEnabled(VIRTUAL_SLOT_ID, true)
        AppSingletons.resetConnections()
        fake?.close()
        fake = null
    }

    private fun bindVirtualAndGoLive(): DiscoveredServer {
        val satellite = FakeSatellite().also { fake = it }
        val server = satellite.server()
        val id = SatelliteConnection.idFor(server)
        manager.pairWithPin(server, "1234")
        assertTrue(
            "session should reach Live",
            AppSingletons.await { manager.get(id)?.state?.value == SatelliteSessionState.Live },
        )
        // A DualSense: the only identity a host can materialize with audio endpoints, so it is the
        // one a speaker slot would really bind to. Bound through the hub as well as applied to the
        // session, because the capability composition reads the binding and the wire reads the
        // descriptor, and this suite needs both.
        AppSingletons.hub.bind(VIRTUAL_SLOT_ID, id, CONTROLLER_TYPE_DUALSENSE)
        manager.get(id)!!.applyDesired(mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_DUALSENSE))
        assertTrue(
            "the virtual slot must register before streams flow",
            AppSingletons.await {
                manager
                    .get(id)
                    ?.slots
                    ?.value
                    ?.get(VIRTUAL_SLOT_ID)
                    ?.registered == true
            },
        )
        return server
    }

    /**
     * The real composer and the real native frame source, with only the output faked. Started
     * through its own lifecycle owner rather than the process one, so the collection is running for
     * exactly as long as the test is.
     */
    private fun startEngine(): SpeakerEngine {
        val composer =
            SpeakerPlayoutComposer(
                AppSingletons.hub,
                AppSingletons.capabilityComposer,
                manager,
                // No Direct-claimed pad in an instrumentation run, so no pad endpoints: the phone's
                // own output is what the virtual pad plays through.
                SlotAudioRoutes.NONE,
                engineScope,
            )
        return SpeakerEngine(composer, sink, NativeSpeakerFrameSource, engineScope).also {
            engine = it
            owner.registry.addObserver(it)
            owner.registry.currentState = Lifecycle.State.STARTED
        }
    }

    private fun speakerPackets(
        server: DiscoveredServer,
        count: Int,
    ): List<ByteArray> {
        // Real Opus packets, minted by the client's own encoder and collected at the fake, then
        // replayed down the speaker path: hand-rolling Opus is not possible and a canned fixture
        // would pin the library version. Mono where a host's would be stereo, which a stereo
        // decoder upmixes, so the shape the sink receives is identical.
        val satellite = fake!!
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex
        for (f in 0 until count + 2) {
            SatelliteNative.sendMicFrame(conn.handle, ctrlIdx, tone(f))
            Thread.sleep(FRAME_MS)
        }
        assertTrue("the fixture run must reach the fake", satellite.awaitMicAudioFrames(count + 2))
        val packets = satellite.micAudioFrames.drop(2).map { it.opus }
        assertTrue("need $count fixture packets, got ${packets.size}", packets.size >= count)
        satellite.micAudioFrames.clear()
        return packets.take(count)
    }

    @Test
    fun speakerFrames_playThroughAnEligibleSlotAndStopWhenItIsSwitchedOff() {
        val server = bindVirtualAndGoLive()
        val satellite = fake!!
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex
        val packets = speakerPackets(server, count = 8)

        // Two waits, not one, so a failure names its own cause. The first is the host's
        // controller-audio verdict reaching the capability model: it rides GET
        // /api/server/capabilities and nothing else, and HostCapabilitiesProbe is what reads it
        // when the link goes Live. The second is the engine acting on that.
        assertTrue(
            "the host's controller-audio verdict must reach the capability model",
            AppSingletons.await { Feature.SPEAKER in slotCapabilities().live },
        )
        startEngine()
        assertTrue(
            "the composer must find the bound DualSense slot eligible for controller sound",
            AppSingletons.await { sink.opens.get() == 1 },
        )

        for ((seq, packet) in packets.withIndex()) {
            satellite.sendSpeakerAudio(ctrlIdx, seq, packet)
            Thread.sleep(FRAME_MS)
        }
        assertTrue("decoded audio must reach the output", AppSingletons.await { sink.played.size >= 4 })
        val window = sink.played.first()
        assertEquals("one 20 ms window of interleaved stereo", SpeakerEngine.FRAME_SAMPLES, window.size)
        assertTrue("a decoded window is not silence", window.any { it.toInt() != 0 })

        // Switching controller sound off for the slot withdraws the cap, and with it the output:
        // the whole point of the toggle is that the host stops being sent audio at all.
        speakerEnabled.setEnabled(VIRTUAL_SLOT_ID, false)
        assertTrue(
            "the output must be released when the slot stops carrying a speaker",
            AppSingletons.await { sink.closes.get() == 1 },
        )
        val playedAtToggle = sink.played.size
        for ((seq, packet) in packets.withIndex()) {
            satellite.sendSpeakerAudio(ctrlIdx, packets.size + seq, packet)
            Thread.sleep(FRAME_MS)
        }
        // Eight more windows arrive; a slot still playing would take all of them. At most the one
        // already inside the sink when the track closed may land, the same single-window tolerance
        // the mute path carries.
        val leaked = sink.played.size - playedAtToggle
        assertTrue("nothing may play into a slot with controller sound off, got $leaked windows", leaked <= 1)
    }

    @Test
    fun micLed_reachesTheVirtualPadsSkinInEveryStateTheWireCarries() {
        val server = bindVirtualAndGoLive()
        val satellite = fake!!
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex
        val store = virtualFeedback

        // The lamp resolves through the app's own FeedbackRouter (installed at process start), so
        // what this pins is the whole path: decrypt, length-check, validate, resolve the slot, and
        // land on the sink that slot has. A Direct-claimed pad's own report is byte-pinned
        // host-side in usb_parsers_test, where the bytes can be asserted rather than inferred.
        for (state in listOf(MIC_LED_ON, MIC_LED_PULSE, MIC_LED_OFF)) {
            satellite.sendMicLed(ctrlIdx, state)
            assertTrue(
                "lamp state $state must reach the virtual pad",
                AppSingletons.await(timeoutMs = 5_000) { store.state.value.micLedState == state },
            )
        }

        // A state this client does not know is dropped natively rather than rendered as a guess.
        store.setMicLed(MIC_LED_ON)
        satellite.sendMicLed(ctrlIdx, UNKNOWN_MIC_LED_STATE)
        Thread.sleep(SETTLE_MS)
        assertEquals(MIC_LED_ON, store.state.value.micLedState)
    }

    // A 220 Hz tone with a per-frame phase offset, so successive windows differ and a decoder that
    // returned the previous frame would be visible.
    private fun tone(frameIndex: Int): ShortArray =
        ShortArray(MIC_FRAME_SAMPLES) { i ->
            val t = (frameIndex * MIC_FRAME_SAMPLES + i) / SAMPLE_RATE.toDouble()
            (kotlin.math.sin(2.0 * kotlin.math.PI * 220.0 * t) * 8000.0).toInt().toShort()
        }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val MIC_FRAME_SAMPLES = 960
        const val FRAME_MS = 20L
        const val SETTLE_MS = 300L

        // One past MIC_LED_STATE_PULSE: what a satellite speaking something newer would send.
        const val UNKNOWN_MIC_LED_STATE = 3
    }
}
