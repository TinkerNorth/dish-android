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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
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
     * recorded" and "the mute landed" can be ordered deliberately instead of raced.
     */
    private class FakeMic : MicCaptureSource {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val readsEntered = AtomicInteger()
        val refuse = AtomicBoolean(false)
        val dieMidStream = AtomicBoolean(false)
        val lastFrameSamples = AtomicInteger()
        private val windows = Semaphore(0)

        override fun open(frameSamples: Int): MicCaptureSession? {
            lastFrameSamples.set(frameSamples)
            if (refuse.get()) return null
            opens.incrementAndGet()
            return Session(frameSamples)
        }

        /** Let exactly one blocking read complete. */
        fun releaseWindow() = windows.release()

        /** Unblock anything still parked, so a finished test never leaves a thread behind. */
        fun drain() = windows.release(DRAIN_PERMITS)

        private inner class Session(
            private val frameSamples: Int,
        ) : MicCaptureSession {
            override val voiceProcessed = true

            override fun read(out: ShortArray): Int {
                assertEquals("the engine must read whole 20 ms windows", frameSamples, out.size)
                readsEntered.incrementAndGet()
                windows.acquire()
                if (dieMidStream.get()) return out.size - 1
                out.fill(TONE)
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

    /** A real thread, because the invariant is about what a thread does, not about a decision. */
    private class TestLoop : MicCaptureLoop {
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

    private val frames = ConcurrentLinkedQueue<Pair<String, ShortArray>>()
    private val mic = FakeMic()
    private val loop = TestLoop()
    private val scope = TestScope(StandardTestDispatcher())
    private val plans = MutableStateFlow(MicCapturePlan.IDLE)

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

    private val engine = MicEngine(composer, manager, mic, loop, scope)

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
        loop.join()
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

        await("the capture thread to finish") { !loop.alive() }
        assertEquals("the in-flight window must be dropped, not sent", 1, frames.size)
        assertEquals("the recorder is released, not left open", 1, mic.closes.get())
        assertEquals(MicCaptureState.Idle, engine.state.value)
    }

    @Test
    fun `unmuting after a mute captures again from a fresh recorder`() {
        engine.apply(eligible())
        await("the first recorder") { mic.opens.get() == 1 }
        engine.apply(mutedPlan())
        mic.releaseWindow()
        await("the first capture to end") { !loop.alive() }
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
        await("the capture thread to finish") { !loop.alive() }
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
        await("the capture thread to finish") { !loop.alive() }
        assertEquals("a stopped engine is a closed recorder", 1, mic.closes.get())
        assertEquals(MicCaptureState.Idle, engine.state.value)
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
    fun `a recorder that dies mid-stream sends no partial window`() {
        engine.apply(eligible())
        await("the recorder to open") { mic.opens.get() == 1 }
        mic.dieMidStream.set(true)
        mic.releaseWindow()

        await("the capture thread to finish") { !loop.alive() }
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
            await("the capture thread to finish") { !loop.alive() }
            assertEquals(0, frames.size)
        }

    private companion object {
        const val SLOT = "virtual"
        const val CONN = "satellite:abc"
        val TARGET = MicCaptureTarget(SLOT, CONN)
        const val TONE: Short = 4242
        const val POLL_MS = 2L

        // Long enough for a capture thread that should not exist to have opened a recorder.
        const val SETTLE_MS = 60L
    }
}
