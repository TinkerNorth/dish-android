// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.tinkernorth.dish.composer.MicCaptureComposer
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The capture engine, and above all the privacy invariant it carries: muted (or the toggle off, or
 * the permission gone, or nothing streaming) means ZERO MSG_MIC_AUDIO packets leave the device.
 *
 * Every "no capture" row below asserts the frame count through a fake connection rather than
 * asserting that some flag is false. A flag can be right while a thread still holds a buffer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MicEngineTest {
    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * A microphone whose windows the test hands out one at a time, so "one more window was
     * recorded" and "the mute landed" can be ordered deliberately instead of raced. One set of
     * permits per endpoint, since the engine may hold several recorders at once.
     */
    private class FakeMic : MicCaptureSource {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val readsEntered = AtomicInteger()
        val refuse = AtomicBoolean(false)
        val dieMidStream = AtomicBoolean(false)
        val lastFrameSamples = AtomicInteger()
        val openedEndpoints = ConcurrentLinkedQueue<Int>()
        private val windows = ConcurrentHashMap<Int, Semaphore>()

        private fun permits(endpoint: Int) = windows.computeIfAbsent(endpoint) { Semaphore(0) }

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): MicCaptureSession? {
            lastFrameSamples.set(frameSamples)
            if (refuse.get()) return null
            // Endpoint first, count second: the tests await on the count and then read the
            // endpoint list, so the increment is the publication barrier. The other order let
            // an await release between the two writes and read one endpoint too few.
            openedEndpoints += preferredDeviceId
            opens.incrementAndGet()
            return Session(frameSamples, preferredDeviceId)
        }

        /** Let exactly one blocking read complete on an endpoint. */
        fun releaseWindow(endpoint: Int = NO_AUDIO_DEVICE) = permits(endpoint).release()

        /** Unblock anything still parked, so a finished test never leaves a thread behind. */
        fun drain() {
            windows.values.forEach { it.release(DRAIN_PERMITS) }
        }

        private inner class Session(
            private val frameSamples: Int,
            private val endpoint: Int,
        ) : MicCaptureSession {
            override val voiceProcessed = true

            override fun read(out: ShortArray): Int {
                assertEquals("the engine must read whole 20 ms windows", frameSamples, out.size)
                readsEntered.incrementAndGet()
                permits(endpoint).acquire()
                if (dieMidStream.get()) return out.size - 1
                out.fill(if (endpoint == NO_AUDIO_DEVICE) TONE else PAD_TONE)
                return out.size
            }

            override fun close() {
                closes.incrementAndGet()
            }
        }

        private companion object {
            const val DRAIN_PERMITS = 64
        }
    }

    /** Real threads, because the invariant is about what a thread does, not about a decision. */
    private class TestLoopFactory : MicCaptureLoopFactory {
        val loops = ConcurrentHashMap<Int, TestLoop>()

        override fun create(preferredDeviceId: Int): MicCaptureLoop = loops.computeIfAbsent(preferredDeviceId) { TestLoop() }

        fun anyAlive(): Boolean = loops.values.any { it.alive() }

        fun joinAll() = loops.values.forEach { it.join() }

        class TestLoop : MicCaptureLoop {
            @Volatile private var thread: Thread? = null

            override fun start(body: () -> Unit) {
                thread = Thread(body, "test-mic").also { it.start() }
            }

            override fun join() {
                thread?.join(JOIN_MS)
                thread = null
            }

            fun alive(): Boolean = thread?.isAlive == true

            private companion object {
                const val JOIN_MS = 2_000L
            }
        }
    }

    private val frames = ConcurrentLinkedQueue<Pair<String, ShortArray>>()
    private val mic = FakeMic()
    private val loops = TestLoopFactory()
    private val scope = TestScope(StandardTestDispatcher())
    private val plans = MutableStateFlow(MicCapturePlan.IDLE)
    private val routeTable = MutableStateFlow<Map<Int, PadAudioRoute>>(emptyMap())

    /** Per-slot capture endpoints, so a pad with its own microphone can be moved under the engine. */
    private val slotRoutes = ConcurrentHashMap<String, PadAudioRoute>()

    private val routing =
        object : SlotAudioRoutes {
            override val changes get() = routeTable

            override fun forSlot(slotId: String) = slotRoutes[slotId] ?: PadAudioRoute.NONE
        }

    private val connection =
        mockk<SatelliteConnection> {
            every { sendMicFrame(any(), any()) } answers
                {
                    frames += firstArg<String>() to secondArg<ShortArray>().copyOf()
                    true
                }
        }

    private val manager = mockk<SatelliteConnectionManager>()

    private val composer = mockk<MicCaptureComposer> { every { state } returns plans }

    private val engine = MicEngine(composer, manager, mic, loops, routing, scope)

    @Before
    fun setUp() {
        // Anything but the bound session resolves to nothing, so a stale target sends nowhere.
        every { manager.get(any()) } returns null
        every { manager.get(CONN) } returns connection
    }

    @After
    fun tearDown() {
        engine.apply(MicCapturePlan.IDLE)
        mic.drain()
        loops.joinAll()
    }

    private fun plan(
        armed: Set<MicCaptureTarget> = emptySet(),
        delivering: Set<MicCaptureTarget> = emptySet(),
    ) = MicCapturePlan(armed, delivering)

    private fun eligible() = plan(armed = setOf(TARGET), delivering = setOf(TARGET))

    private fun mutedPlan() = plan(armed = setOf(TARGET), delivering = emptySet())

    private fun await(
        what: String,
        timeoutMs: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        fail("timed out waiting for $what")
    }

    // ---- the eligible state ----

    @Test
    fun `an eligible plan opens one recorder and sends every window to the bound slot`() {
        engine.apply(eligible())
        await("the recorder to open") { mic.opens.get() == 1 }
        assertEquals(MicCaptureState.Capturing, engine.state.value)

        repeat(3) { mic.releaseWindow() }
        await("three windows") { frames.size == 3 }

        assertEquals(listOf(SLOT, SLOT, SLOT), frames.map { it.first })
        assertTrue("a window is one 20 ms frame", frames.all { it.second.size == MicEngine.FRAME_SAMPLES })
        assertTrue("captured audio, not silence", frames.all { f -> f.second.all { it == TONE } })
        assertEquals("exactly 960 samples were asked for", MicEngine.FRAME_SAMPLES, mic.lastFrameSamples.get())
        assertEquals("the phone's own microphone, no preferred endpoint", listOf(NO_AUDIO_DEVICE), mic.openedEndpoints.toList())
    }

    @Test
    fun `two eligible slots share the one microphone the phone has`() {
        val second = MicCaptureTarget("-1000", CONN)
        engine.apply(plan(armed = setOf(TARGET, second), delivering = setOf(TARGET, second)))
        await("the recorder to open") { mic.opens.get() == 1 }
        mic.releaseWindow()
        await("both slots to receive the window") { frames.size == 2 }
        assertEquals(setOf(SLOT, "-1000"), frames.map { it.first }.toSet())
        assertEquals("one microphone, not one per slot", 1, mic.opens.get())
    }

    // ---- the privacy invariant ----

    @Test
    fun `every ineligible plan opens no recorder and sends nothing`() {
        // Ineligibility arrives already reduced by MicCapturePolicy, so what the engine has to
        // honour is the empty delivering set, whatever produced it.
        val ineligible =
            listOf(
                "nothing bound" to MicCapturePlan.IDLE,
                "armed but muted" to mutedPlan(),
                "armed but muted, two slots" to
                    plan(armed = setOf(TARGET, MicCaptureTarget("-1000", CONN)), delivering = emptySet()),
            )
        for ((label, p) in ineligible) {
            engine.apply(p)
            mic.releaseWindow()
            Thread.sleep(SETTLE_MS)
            assertEquals("$label must open no recorder", 0, mic.opens.get())
            assertEquals("$label must send nothing", 0, frames.size)
            assertEquals("$label leaves the engine idle", MicCaptureState.Idle, engine.state.value)
        }
    }

    @Test
    fun `muting mid-stream drops the window already in the recorder`() {
        engine.apply(eligible())
        await("the recorder to open") { mic.opens.get() == 1 }
        mic.releaseWindow()
        await("the first window") { frames.size == 1 }

        // Park the capture thread inside a read, then mute. The window it is about to return was
        // recorded while unmuted, and it must still not be sent: that is what bounds mute latency
        // to one 20 ms frame instead of to whatever the recorder had buffered.
        await("the next read to start") { mic.readsEntered.get() == 2 }
        engine.apply(mutedPlan())
        mic.releaseWindow()

        await("the capture thread to finish") { !loops.anyAlive() }
        assertEquals("the in-flight window must be dropped, not sent", 1, frames.size)
        assertEquals("the recorder is released, not left open", 1, mic.closes.get())
        assertEquals(MicCaptureState.Idle, engine.state.value)
    }

    @Test
    fun `quiescence means stopped, not told to stop`() {
        // The two are a whole blocking read apart, and the difference is exactly what an
        // integration test asserting "no packets" has to be able to wait out instead of sleeping
        // through. Idle lands with the plan; quiescent lands when the body returns.
        assertTrue("an engine that never started is quiescent", engine.quiescent)

        engine.apply(eligible())
        await("the recorder to open") { mic.opens.get() == 1 }
        assertFalse("a running capture is not quiescent", engine.quiescent)

        // Park the capture thread inside a read and take the plan away. The engine says Idle at
        // once, because that is what it was told; it is not quiescent until the body is out.
        mic.releaseWindow()
        await("the first window") { frames.size == 1 }
        await("the next read to start") { mic.readsEntered.get() == 2 }
        engine.apply(MicCapturePlan.IDLE)
        assertEquals(MicCaptureState.Idle, engine.state.value)
        assertFalse("a body still inside a read is not quiescent", engine.quiescent)

        mic.releaseWindow()
        await("the engine to go quiescent") { engine.quiescent }
        assertEquals("quiescent means the microphone is closed", 1, mic.closes.get())
        assertEquals("and the window it was holding was dropped, not sent", 1, frames.size)
    }

    @Test
    fun `a refused recorder is quiescent once its body gives up`() {
        mic.refuse.set(true)
        engine.apply(eligible())
        await("the refusal to land") { engine.state.value == MicCaptureState.Unavailable }
        await("the engine to go quiescent") { engine.quiescent }
    }

    @Test
    fun `quiescence covers every route, not just the phone's`() {
        val padSlot = MicCaptureTarget(PAD_SLOT, CONN)
        slotRoutes[PAD_SLOT] = PadAudioRoute(microphone = true, speaker = false, captureDeviceId = PAD_ENDPOINT)
        engine.apply(plan(armed = setOf(TARGET, padSlot), delivering = setOf(TARGET, padSlot)))
        await("both recorders to open") { mic.opens.get() == 2 }
        assertFalse(engine.quiescent)

        // One route going away is not quiescence: the other is still holding a microphone.
        engine.apply(eligible())
        mic.releaseWindow(PAD_ENDPOINT)
        await("the pad's recorder to close") { mic.closes.get() == 1 }
        assertFalse("the phone's recorder is still running", engine.quiescent)

        engine.apply(MicCapturePlan.IDLE)
        mic.drain()
        await("the engine to go quiescent") { engine.quiescent }
        assertEquals(2, mic.closes.get())
    }

    @Test
    fun `unmuting after a mute captures again from a fresh recorder`() {
        engine.apply(eligible())
        await("the first recorder") { mic.opens.get() == 1 }
        engine.apply(mutedPlan())
        mic.releaseWindow()
        await("the first capture to end") { !loops.anyAlive() }
        frames.clear()

        engine.apply(eligible())
        await("a second recorder") { mic.opens.get() == 2 }
        mic.releaseWindow()
        await("audio again") { frames.size == 1 }
        assertEquals(MicCaptureState.Capturing, engine.state.value)
    }

    @Test
    fun `losing the session mid-stream stops the microphone`() {
        engine.apply(eligible())
        await("the recorder to open") { mic.opens.get() == 1 }
        mic.releaseWindow()
        await("the first window") { frames.size == 1 }

        // The plan collapsing to IDLE is what a disconnect, an unbind, a revoked permission and a
        // switched-off toggle all look like from here.
        engine.apply(MicCapturePlan.IDLE)
        mic.drain()
        await("the capture thread to finish") { !loops.anyAlive() }
        assertEquals("nothing may be sent after the plan went idle", 1, frames.size)
        assertEquals(1, mic.closes.get())
    }

    @Test
    fun `process stop closes the recorder even while the plan still says capture`() {
        val owner = TestOwner()
        owner.registry.addObserver(engine)
        owner.registry.currentState = Lifecycle.State.STARTED
        engine.apply(eligible())
        await("the recorder to open") { mic.opens.get() == 1 }

        // ON_STOP with the plan untouched: the actuator's contract says its inputs keep moving
        // while it is stopped, and a microphone is the one input that must not.
        owner.registry.currentState = Lifecycle.State.CREATED
        mic.drain()
        await("the capture thread to finish") { !loops.anyAlive() }
        assertEquals("a stopped engine is a closed recorder", 1, mic.closes.get())
        assertEquals(MicCaptureState.Idle, engine.state.value)
    }

    // ---- per-route capture ----

    @Test
    fun `a slot whose pad has its own microphone gets its own recorder`() {
        // An AudioRecord's preferred device is fixed when it is built, so two slots on two
        // endpoints cannot share one: the phone's microphone and the pad's headset are two sources.
        val padSlot = MicCaptureTarget(PAD_SLOT, CONN)
        slotRoutes[PAD_SLOT] = PadAudioRoute(microphone = true, speaker = false, captureDeviceId = PAD_ENDPOINT)
        engine.apply(plan(armed = setOf(TARGET, padSlot), delivering = setOf(TARGET, padSlot)))
        await("both recorders to open") { mic.opens.get() == 2 }
        assertEquals(setOf(NO_AUDIO_DEVICE, PAD_ENDPOINT), mic.openedEndpoints.toSet())

        // And each window goes only to the slots that share its endpoint.
        mic.releaseWindow(PAD_ENDPOINT)
        await("the pad's window") { frames.size == 1 }
        assertEquals(PAD_SLOT, frames.first().first)
        assertTrue("the pad's own microphone, not the phone's", frames.first().second.all { it == PAD_TONE })

        mic.releaseWindow(NO_AUDIO_DEVICE)
        await("the phone's window") { frames.size == 2 }
        assertEquals(SLOT, frames.last().first)
        assertTrue(frames.last().second.all { it == TONE })
    }

    @Test
    fun `two slots on the same pad endpoint still share one recorder`() {
        val padA = MicCaptureTarget(PAD_SLOT, CONN)
        val padB = MicCaptureTarget(OTHER_PAD_SLOT, CONN)
        val route = PadAudioRoute(microphone = true, speaker = false, captureDeviceId = PAD_ENDPOINT)
        slotRoutes[PAD_SLOT] = route
        slotRoutes[OTHER_PAD_SLOT] = route
        engine.apply(plan(armed = setOf(padA, padB), delivering = setOf(padA, padB)))
        await("one recorder") { mic.opens.get() == 1 }
        mic.releaseWindow(PAD_ENDPOINT)
        await("both slots to receive it") { frames.size == 2 }
        assertEquals("one endpoint, one recorder", 1, mic.opens.get())
    }

    @Test
    fun `a pad endpoint appearing moves the slot onto its own recorder`() {
        // The plan does not change when a route does, so the engine has to notice the table itself.
        val padSlot = MicCaptureTarget(PAD_SLOT, CONN)
        engine.apply(plan(armed = setOf(padSlot), delivering = setOf(padSlot)))
        await("the phone's recorder") { mic.opens.get() == 1 }
        assertEquals(listOf(NO_AUDIO_DEVICE), mic.openedEndpoints.toList())

        slotRoutes[PAD_SLOT] = PadAudioRoute(microphone = true, speaker = false, captureDeviceId = PAD_ENDPOINT)
        engine.apply(plan(armed = setOf(padSlot), delivering = setOf(padSlot)))
        await("the pad's recorder") { mic.opens.get() == 2 }
        assertEquals(listOf(NO_AUDIO_DEVICE, PAD_ENDPOINT), mic.openedEndpoints.toList())

        // The old recorder is released rather than left holding the phone's microphone.
        mic.releaseWindow(NO_AUDIO_DEVICE)
        await("the phone's recorder to close") { mic.closes.get() == 1 }
    }

    @Test
    fun `a pad endpoint vanishing puts the slot back on the phone`() {
        val padSlot = MicCaptureTarget(PAD_SLOT, CONN)
        slotRoutes[PAD_SLOT] = PadAudioRoute(microphone = true, speaker = false, captureDeviceId = PAD_ENDPOINT)
        engine.apply(plan(armed = setOf(padSlot), delivering = setOf(padSlot)))
        await("the pad's recorder") { mic.opens.get() == 1 }

        slotRoutes.remove(PAD_SLOT)
        engine.apply(plan(armed = setOf(padSlot), delivering = setOf(padSlot)))
        await("the phone's recorder") { mic.opens.get() == 2 }
        assertEquals(listOf(PAD_ENDPOINT, NO_AUDIO_DEVICE), mic.openedEndpoints.toList())
        mic.releaseWindow(NO_AUDIO_DEVICE)
        await("audio from the phone") { frames.any { it.second.all { s -> s == TONE } } }
    }

    // ---- device refusal ----

    @Test
    fun `a device that refuses the microphone reports unavailable and does not spin`() {
        mic.refuse.set(true)
        engine.apply(eligible())
        await("the refusal to land") { engine.state.value == MicCaptureState.Unavailable }
        // Re-applying the same plan must not hammer a device that already said no.
        engine.apply(eligible())
        engine.apply(eligible())
        Thread.sleep(SETTLE_MS)
        assertEquals(0, frames.size)
        assertEquals(MicCaptureState.Unavailable, engine.state.value)
    }

    @Test
    fun `a refusal is forgotten once the microphone is disarmed and armed again`() {
        // A busy microphone is usually another app's, and that app may well have finished.
        mic.refuse.set(true)
        engine.apply(eligible())
        await("the refusal") { engine.state.value == MicCaptureState.Unavailable }

        engine.apply(MicCapturePlan.IDLE)
        mic.refuse.set(false)
        engine.apply(eligible())
        await("a second attempt") { mic.opens.get() == 1 }
        mic.releaseWindow()
        await("audio") { frames.size == 1 }
    }

    @Test
    fun `one endpoint refusing does not stop another that works`() {
        // Only the pad's endpoint is refused here, by opening it while the source says no and the
        // phone's while it says yes.
        val padSlot = MicCaptureTarget(PAD_SLOT, CONN)
        slotRoutes[PAD_SLOT] = PadAudioRoute(microphone = true, speaker = false, captureDeviceId = PAD_ENDPOINT)
        engine.apply(eligible())
        await("the phone's recorder") { mic.opens.get() == 1 }

        // Only the new endpoint is refused: the phone's recorder is already open and is not
        // reopened, so the flag reaches nothing but the pad's attempt.
        mic.refuse.set(true)
        engine.apply(plan(armed = setOf(TARGET, padSlot), delivering = setOf(TARGET, padSlot)))
        Thread.sleep(SETTLE_MS)
        assertEquals("the refused endpoint opened nothing", 1, mic.opens.get())
        assertEquals("a live recorder outranks a refused one", MicCaptureState.Capturing, engine.state.value)

        mic.refuse.set(false)
        mic.releaseWindow(NO_AUDIO_DEVICE)
        await("the phone's audio to keep flowing") { frames.size == 1 }
    }

    @Test
    fun `a recorder that dies mid-stream sends no partial window`() {
        engine.apply(eligible())
        await("the recorder to open") { mic.opens.get() == 1 }
        mic.dieMidStream.set(true)
        mic.releaseWindow()

        await("the capture thread to finish") { !loops.anyAlive() }
        assertEquals("a short read is a dead recorder, never a short packet", 0, frames.size)
        assertEquals(MicCaptureState.Unavailable, engine.state.value)
        assertEquals(1, mic.closes.get())
    }

    // ---- wiring ----

    @Test
    fun `an unknown connection id sends nothing rather than throwing`() {
        val orphan = MicCaptureTarget(SLOT, "satellite:gone")
        engine.apply(plan(armed = setOf(orphan), delivering = setOf(orphan)))
        await("the recorder to open") { mic.opens.get() == 1 }
        mic.releaseWindow()
        await("the window to be read") { mic.readsEntered.get() >= 2 }
        assertEquals(0, frames.size)
    }

    @Test
    fun `the engine collects the capture composer once started`() =
        runTest(scope.testScheduler) {
            val owner = TestOwner()
            owner.registry.addObserver(engine)
            owner.registry.currentState = Lifecycle.State.STARTED
            scope.testScheduler.runCurrent()

            plans.value = eligible()
            scope.testScheduler.runCurrent()
            await("the composer's plan to reach the engine") { mic.opens.get() == 1 }

            plans.value = MicCapturePlan.IDLE
            scope.testScheduler.runCurrent()
            mic.drain()
            await("the capture thread to finish") { !loops.anyAlive() }
            assertEquals(0, frames.size)
        }

    @Test
    fun `the engine regroups when the route table moves under an unchanged plan`() =
        runTest(scope.testScheduler) {
            val owner = TestOwner()
            owner.registry.addObserver(engine)
            owner.registry.currentState = Lifecycle.State.STARTED
            scope.testScheduler.runCurrent()

            plans.value = eligible()
            scope.testScheduler.runCurrent()
            await("the phone's recorder") { mic.opens.get() == 1 }

            slotRoutes[SLOT] = PadAudioRoute(microphone = true, speaker = false, captureDeviceId = PAD_ENDPOINT)
            routeTable.value = mapOf(1 to PadAudioRoute(microphone = true, speaker = false, captureDeviceId = PAD_ENDPOINT))
            scope.testScheduler.runCurrent()
            await("the pad's recorder") { mic.opens.get() == 2 }
            assertEquals(listOf(NO_AUDIO_DEVICE, PAD_ENDPOINT), mic.openedEndpoints.toList())
        }

    private companion object {
        const val SLOT = "virtual"
        const val PAD_SLOT = "-1000"
        const val OTHER_PAD_SLOT = "-1001"
        const val CONN = "satellite:abc"
        val TARGET = MicCaptureTarget(SLOT, CONN)
        const val TONE: Short = 4242
        const val PAD_TONE: Short = 1234
        const val PAD_ENDPOINT = 12
        const val POLL_MS = 2L

        // Long enough for a capture thread that should not exist to have opened a recorder.
        const val SETTLE_MS = 60L
    }
}
