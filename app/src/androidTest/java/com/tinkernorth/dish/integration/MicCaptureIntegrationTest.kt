// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.MicCaptureComposer
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.audio.MicCaptureLoop
import com.tinkernorth.dish.source.audio.MicCaptureLoopFactory
import com.tinkernorth.dish.source.audio.MicCapturePlan
import com.tinkernorth.dish.source.audio.MicCaptureSession
import com.tinkernorth.dish.source.audio.MicCaptureSource
import com.tinkernorth.dish.source.audio.MicCaptureTarget
import com.tinkernorth.dish.source.audio.MicEngine
import com.tinkernorth.dish.source.audio.SlotAudioRoutes
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.store.MicMuteStore
import com.tinkernorth.dish.source.system.MicPermissionGate
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * The capture pipeline end to end against a real session: a paced fake microphone in, real Opus
 * frames out of the native encoder, real ChaCha20-Poly1305 on the wire, and the fake satellite
 * decrypting and layout-checking what lands.
 *
 * The point of running it here rather than only host-side is the privacy invariant. Host tests can
 * prove the engine did not CALL the sender; only this can prove nothing reached the network, which
 * is the claim the store listing makes.
 */
@RunWith(AndroidJUnit4::class)
class MicCaptureIntegrationTest {
    private val manager get() = AppSingletons.satellite

    private var fake: FakeSatellite? = null
    private var engine: MicEngine? = null
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A microphone that paces itself like a real recorder: one 20 ms window per read. */
    private class PacedMic : MicCaptureSource {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        private val phase = AtomicInteger()
        private val open = AtomicBoolean(false)

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): MicCaptureSession? {
            opens.incrementAndGet()
            open.set(true)
            return object : MicCaptureSession {
                override val voiceProcessed = true

                override fun read(out: ShortArray): Int {
                    Thread.sleep(WINDOW_MS)
                    val base = phase.getAndIncrement() * out.size
                    for (i in out.indices) {
                        val t = (base + i) / SAMPLE_RATE.toDouble()
                        out[i] = (sin(2.0 * PI * 220.0 * t) * 8000.0).toInt().toShort()
                    }
                    return out.size
                }

                override fun close() {
                    open.set(false)
                    closes.incrementAndGet()
                }
            }
        }
    }

    private class TestLoop : MicCaptureLoop {
        @Volatile private var thread: Thread? = null

        override fun start(body: () -> Unit) {
            thread = Thread(body, "mic-integration").also { it.start() }
        }

        override fun join() {
            thread?.join(JOIN_MS)
            thread = null
        }

        private companion object {
            const val JOIN_MS = 2_000L
        }
    }

    private val mic = PacedMic()
    private val loop = TestLoop()

    @Before
    fun setUp() {
        AppSingletons.resetConnections()
    }

    @After
    fun tearDown() {
        engine?.apply(MicCapturePlan.IDLE)
        loop.join()
        engine = null
        engineScope.cancel()
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
     * The engine, wired to the app's real connection manager. The composer is real but never
     * collected: these tests drive the plan directly so every row of the eligibility matrix can be
     * held still, which is what makes "zero frames" an assertion rather than a race.
     */
    private fun newEngine(): MicEngine {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val composer =
            MicCaptureComposer(
                AppSingletons.hub,
                AppSingletons.capabilityComposer,
                MicPermissionGate(context),
                MicMuteStore(),
                engineScope,
            )
        // No pad endpoints in this suite: the phone's own microphone is what a virtual pad uses,
        // and per-route capture has its own coverage host-side.
        return MicEngine(composer, manager, mic, MicCaptureLoopFactory { loop }, SlotAudioRoutes.NONE, engineScope)
            .also { engine = it }
    }

    private fun targetFor(server: DiscoveredServer) = MicCaptureTarget(VIRTUAL_SLOT_ID, SatelliteConnection.idFor(server))

    private fun eligible(server: DiscoveredServer) = targetFor(server).let { MicCapturePlan(setOf(it), setOf(it)) }

    private fun muted(server: DiscoveredServer) = MicCapturePlan(armed = setOf(targetFor(server)), delivering = emptySet())

    @Test
    fun micFrames_reachTheSatelliteOnlyWhileEveryGateIsOpen() {
        val server = bindVirtualAndGoLive()
        val satellite = fake!!
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val ctrlIdx = conn.slots.value[VIRTUAL_SLOT_ID]!!.controllerIndex
        val engine = newEngine()

        // Ineligible, three ways. Each one gets long enough to have sent dozens of windows.
        for (plan in listOf(MicCapturePlan.IDLE, muted(server))) {
            engine.apply(plan)
            Thread.sleep(SETTLE_MS)
            assertEquals("no recorder may open while ineligible", 0, mic.opens.get())
            assertTrue("not one packet may leave the device", satellite.micAudioFrames.isEmpty())
        }

        // Eligible: the same engine, the same session, audio flowing.
        engine.apply(eligible(server))
        assertTrue("mic frames must reach the satellite", satellite.awaitMicAudioFrames(3))
        assertEquals("no frame may violate the wire layout", emptyList<String>(), satellite.micAudioViolations)
        assertTrue(
            "frames must name the bound slot",
            satellite.micAudioFrames.all { it.ctrlIdx == ctrlIdx && it.opus.isNotEmpty() },
        )
        assertEquals("one microphone for the phone", 1, mic.opens.get())
    }

    @Test
    fun mutingMidStreamStopsFramesWithinOneWindow() {
        val server = bindVirtualAndGoLive()
        val satellite = fake!!
        val engine = newEngine()

        engine.apply(eligible(server))
        assertTrue("the stream must be running before it can be stopped", satellite.awaitMicAudioFrames(4))

        engine.apply(muted(server))
        val atMute = satellite.micAudioFrames.size
        // A dozen windows' worth of wall clock. Anything the recorder had in hand when the mute
        // landed is dropped, so at most the one already handed to the sender can still arrive.
        Thread.sleep(SETTLE_MS)
        val after = satellite.micAudioFrames.size
        assertTrue(
            "muting must stop the stream within one 20 ms window, got ${after - atMute} more frames",
            after - atMute <= 1,
        )
        assertEquals("the recorder must be released, not left open", 1, mic.closes.get())

        // And it comes back: mute is a control, not a teardown.
        satellite.micAudioFrames.clear()
        engine.apply(eligible(server))
        assertTrue("unmuting must resume capture", satellite.awaitMicAudioFrames(2))
        assertEquals(2, mic.opens.get())
    }

    @Test
    fun losingTheSessionStopsTheMicrophone() {
        val server = bindVirtualAndGoLive()
        val satellite = fake!!
        val engine = newEngine()

        engine.apply(eligible(server))
        assertTrue(satellite.awaitMicAudioFrames(3))

        // What a disconnect, an unbind, a revoked grant and a switched-off toggle all look like
        // from the engine's side.
        engine.apply(MicCapturePlan.IDLE)
        loop.join()
        satellite.micAudioFrames.clear()
        Thread.sleep(SETTLE_MS)
        assertTrue("nothing may be sent once the plan went idle", satellite.micAudioFrames.isEmpty())
        assertEquals(1, mic.closes.get())
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val WINDOW_MS = 20L

        // ~12 capture windows: long enough that a leak would be several packets, not a maybe.
        const val SETTLE_MS = 250L
    }
}
