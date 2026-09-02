// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.hotpath.audio.SpeakerAudioBridge
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/**
 * The controller-audio data plane end to end against a real session key: mic
 * frames encoded natively and decrypted at the fake with the layout the
 * contract pins, speaker frames decoded back to PCM through the native reorder
 * window, and the mic-mute lamp arriving as a validated state byte.
 *
 * Everything below the JNI surface is the real thing (libopus, the jitter
 * window, ChaCha20-Poly1305), which is what makes this the counterpart to the
 * host-side suites rather than a repeat of them.
 */
@RunWith(AndroidJUnit4::class)
class ControllerAudioWireTest {
    private val manager get() = AppSingletons.satellite
    private var fake: FakeSatellite? = null

    private class SpeakerFrame(
        val handle: Int,
        val ctrlIdx: Int,
        val pcm: ShortArray,
        val concealed: Boolean,
    )

    private val speakerFrames = CopyOnWriteArrayList<SpeakerFrame>()

    @Before
    fun setUp() {
        AppSingletons.resetConnections()
        speakerFrames.clear()
        SpeakerAudioBridge.install { handle, ctrlIdx, pcm, concealed ->
            speakerFrames += SpeakerFrame(handle, ctrlIdx, pcm, concealed)
        }
    }

    @After
    fun tearDown() {
        SpeakerAudioBridge.uninstall()
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
        // A DualSense: the only identity a host can materialize with audio
        // endpoints, so it is the one a mic/speaker slot would really bind to.
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

    @Test
    fun micFrames_reachTheSatelliteWithTheContractsLayout() {
        val server = bindVirtualAndGoLive()
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val satellite = fake!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex

        // UDP is lossy by design, so send a run and assert on what lands. Ten
        // frames is enough to see the sequence advance and to leave Opus's
        // start-up transient behind.
        for (f in 0 until 10) {
            SatelliteNative.sendMicFrame(conn.handle, ctrlIdx, tone(f))
            Thread.sleep(20)
        }
        assertTrue("mic frames must decrypt at the satellite", satellite.awaitMicAudioFrames(3))
        assertEquals("no frame may violate the wire layout", emptyList<String>(), satellite.micAudioViolations)

        val frames = satellite.micAudioFrames.toList()
        for (frame in frames) {
            assertEquals("frames name the bound slot", ctrlIdx, frame.ctrlIdx)
            assertTrue("a frame always carries at least one Opus byte", frame.opus.isNotEmpty())
            // ~32 kbps at 20 ms is ~80 bytes; the ceiling is the datagram's.
            assertTrue("an Opus packet of ${frame.opus.size} bytes is not a 20 ms mic frame", frame.opus.size < 400)
        }
        // seq is per controller, starts at 0 and advances by one per frame, so a
        // run of arrivals is strictly increasing whatever the network dropped.
        assertEquals(0, frames.first().seq)
        for (i in 1 until frames.size) {
            assertTrue("seq must advance: ${frames[i - 1].seq} then ${frames[i].seq}", frames[i].seq > frames[i - 1].seq)
        }
    }

    @Test
    fun micFrames_areRefusedUnlessTheWindowIsExactlyTwentyMilliseconds() {
        val server = bindVirtualAndGoLive()
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex

        // A mis-framed buffer must not become a packet the satellite cannot
        // place in its timeline, so nothing leaves the device at all.
        assertTrue(!SatelliteNative.sendMicFrame(conn.handle, ctrlIdx, ShortArray(FRAME_SAMPLES - 1)))
        assertTrue(!SatelliteNative.sendMicFrame(conn.handle, ctrlIdx, ShortArray(FRAME_SAMPLES + 1)))
        assertTrue(!SatelliteNative.sendMicFrame(conn.handle, ctrlIdx, ShortArray(0)))
        assertTrue(!SatelliteNative.sendMicFrame(-1, ctrlIdx, tone(0)))
        Thread.sleep(300)
        assertEquals(emptyList<String>(), fake!!.micAudioViolations)
        assertTrue("nothing may reach the wire from a mis-framed window", fake!!.micAudioFrames.isEmpty())

        // And the encoder is not wedged by the refusals.
        assertTrue(SatelliteNative.sendMicFrame(conn.handle, ctrlIdx, tone(0)))
    }

    @Test
    fun speakerFrames_arriveAsInOrderStereoPcm() {
        val server = bindVirtualAndGoLive()
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val satellite = fake!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex
        val packets = speakerPackets(count = 8)

        for ((seq, packet) in packets.withIndex()) {
            satellite.sendSpeakerAudio(ctrlIdx, seq, packet)
            Thread.sleep(20)
        }
        assertTrue("decoded speaker PCM must reach Kotlin", AppSingletons.await { speakerFrames.size >= 4 })

        val frame = speakerFrames.first()
        assertEquals("the bound session", conn.handle, frame.handle)
        assertEquals("the bound slot", ctrlIdx, frame.ctrlIdx)
        // One 20 ms window of interleaved stereo, always, whatever the packet
        // held: AND-4's AudioTrack writes this buffer verbatim.
        assertEquals(FRAME_SAMPLES * 2, frame.pcm.size)
        assertTrue("a decoded frame is not silence", frame.pcm.any { it.toInt() != 0 })
        assertTrue("a packet that arrived is not concealment", speakerFrames.none { it.concealed })
    }

    @Test
    fun aGapInTheSpeakerStreamIsConcealedRatherThanSkipped() {
        val server = bindVirtualAndGoLive()
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val satellite = fake!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex
        val packets = speakerPackets(count = 10)

        // Drop seq 4 outright. The window declares it lost once seq 6 lands (two
        // ahead), and the decoder fills the hole from the FEC copy seq 5 carries
        // or, failing that, from its own history. Either way audio keeps coming
        // and the stream does not jump.
        for ((seq, packet) in packets.withIndex()) {
            if (seq == LOST_SEQ) continue
            satellite.sendSpeakerAudio(ctrlIdx, seq, packet)
            Thread.sleep(20)
        }
        assertTrue("the stream must keep flowing across the gap", AppSingletons.await { speakerFrames.size >= 6 })
        val concealed = speakerFrames.firstOrNull { it.concealed }
        assertNotNull("the missing frame must be concealed, not skipped", concealed)
        assertEquals(FRAME_SAMPLES * 2, concealed!!.pcm.size)
    }

    @Test
    fun micLed_arrivesForEveryStateAndNeverForAnUnknownOne() {
        val server = bindVirtualAndGoLive()
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val satellite = fake!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex

        // The lamp has no sink until the playback wave lands, so what this pins
        // is the receive path: three valid states and one this client must drop
        // are decrypted, length-checked and dispatched without wedging the drain
        // loop. Which state maps to which lamp is byte-pinned host-side
        // (wire_encoders_test), where it can be asserted rather than inferred.
        for (state in 0..2) {
            satellite.sendMicLed(ctrlIdx, state)
            Thread.sleep(20)
        }
        satellite.sendMicLed(ctrlIdx, UNKNOWN_MIC_LED_STATE)
        Thread.sleep(100)

        assertTrue(SatelliteNative.sendMicFrame(conn.handle, ctrlIdx, tone(0)))
        assertTrue("the session survives an unknown lamp state", satellite.awaitMicAudioFrames(1))
    }

    // A 220 Hz tone with a per-frame phase offset, so successive windows differ
    // and a decoder that returned the previous frame would be visible.
    private fun tone(frameIndex: Int): ShortArray =
        ShortArray(FRAME_SAMPLES) { i ->
            val t = (frameIndex * FRAME_SAMPLES + i) / SAMPLE_RATE.toDouble()
            (sin(2.0 * PI * 220.0 * t) * 8000.0).toInt().toShort()
        }

    /**
     * Real Opus packets carrying real audio, minted by the client's own mic
     * encoder and collected at the fake, then replayed back down the speaker
     * path. Hand-rolling Opus is not possible and a canned fixture would pin the
     * library version; this instead leans on the one thing both directions
     * share, which is Opus itself. The packets are mono where a host's would be
     * stereo, and a stereo decoder upmixes them, so the shape the sink receives
     * is identical. They also carry in-band FEC, which is what makes the gap
     * test a recovery test rather than a survival test.
     */
    private fun speakerPackets(count: Int): List<ByteArray> {
        val satellite = fake!!
        val conn = manager.get(SatelliteConnection.idFor(satellite.server()))!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex
        for (f in 0 until count + 2) {
            SatelliteNative.sendMicFrame(conn.handle, ctrlIdx, tone(f))
            Thread.sleep(20)
        }
        assertTrue("the fixture run must reach the fake", satellite.awaitMicAudioFrames(count + 2))
        // Drop the first two: Opus needs a few frames to leave its start-up
        // transient, and a near-silent opener would make "not silence" flaky.
        val packets = satellite.micAudioFrames.drop(2).map { it.opus }
        assertTrue("need $count fixture packets, got ${packets.size}", packets.size >= count)
        satellite.micAudioFrames.clear()
        return packets.take(count)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        const val LOST_SEQ = 4

        // One past MIC_LED_STATE_PULSE: what a satellite speaking something
        // newer than this client would send.
        const val UNKNOWN_MIC_LED_STATE = 3
    }
}
