// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.tinkernorth.dish.composer.SpeakerPlayoutComposer
import com.tinkernorth.dish.hotpath.audio.SpeakerAudioBridge
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The playback engine: which slots hold an output, when they let it go, and what happens to a frame
 * whose sink will not take it.
 *
 * The non-blocking property is asserted against a real thread rather than inferred from the code,
 * because it is the one that matters: the frames arrive on ONE native dispatch thread shared by
 * every stream, so a sink that blocks does not stall its own pad's audio, it stalls everybody's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerEngineTest {
    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** A sink that records what it was asked for and how much of it it took. */
    private class FakeSink : SpeakerPlayoutSink {
        val opens = ConcurrentLinkedQueue<Int>()
        val closes = AtomicInteger()
        val written = ConcurrentLinkedQueue<ShortArray>()
        val refuse = AtomicBoolean(false)
        val lastFrameSamples = AtomicInteger()

        /** How many samples a write accepts; below a whole window it is a partial write. */
        val accept = AtomicInteger(Int.MAX_VALUE)

        /** Held closed to park a writer inside the sink, so "does not block" can be measured. */
        val gate = CountDownLatch(1)
        val blockWrites = AtomicBoolean(false)
        val writersParked = AtomicInteger()

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): SpeakerPlayoutSession? {
            lastFrameSamples.set(frameSamples)
            if (refuse.get()) return null
            opens += preferredDeviceId
            return Session()
        }

        private inner class Session : SpeakerPlayoutSession {
            override fun write(pcmStereo: ShortArray): Int {
                if (blockWrites.get()) {
                    writersParked.incrementAndGet()
                    gate.await()
                }
                written += pcmStereo
                return minOf(accept.get(), pcmStereo.size)
            }

            override fun close() {
                closes.incrementAndGet()
            }
        }
    }

    private class FakeFrameSource : SpeakerFrameSource {
        val installs = AtomicInteger()
        val uninstalls = AtomicInteger()

        @Volatile var sink: SpeakerAudioBridge.Sink? = null

        override fun install(sink: SpeakerAudioBridge.Sink) {
            this.sink = sink
            installs.incrementAndGet()
        }

        override fun uninstall() {
            sink = null
            uninstalls.incrementAndGet()
        }
    }

    private val sink = FakeSink()
    private val frames = FakeFrameSource()
    private val scope = TestScope(StandardTestDispatcher())
    private val plans = MutableStateFlow(SpeakerPlayoutPlan.IDLE)
    private val composer = mockk<SpeakerPlayoutComposer> { every { state } returns plans }
    private val engine = SpeakerEngine(composer, sink, frames, scope)

    private fun target(
        slotId: String = VIRTUAL_SLOT_ID,
        handle: Int = HANDLE,
        index: Int = CTRL_IDX,
        playbackDeviceId: Int = NO_AUDIO_DEVICE,
    ) = SpeakerTarget(slotId, handle, index, playbackDeviceId)

    private fun plan(vararg targets: SpeakerTarget) =
        SpeakerPlayoutPlan(targets.associateBy { SpeakerPlayoutPlan.routeKey(it.sessionHandle, it.controllerIndex) })

    private fun window(fill: Short = TONE) = ShortArray(SpeakerEngine.FRAME_SAMPLES) { fill }

    private fun deliver(
        handle: Int = HANDLE,
        index: Int = CTRL_IDX,
        pcm: ShortArray = window(),
        concealed: Boolean = false,
    ) = engine.onSpeakerFrame(handle, index, pcm, concealed)

    // ---- lifecycle ----

    @Test
    fun `an eligible plan opens one output and plays every window into it`() {
        engine.apply(plan(target()))
        assertEquals(1, sink.opens.size)
        assertEquals(SpeakerPlayoutState.Playing, engine.state.value)
        assertEquals("one 20 ms window of interleaved stereo", SpeakerEngine.FRAME_SAMPLES, sink.lastFrameSamples.get())

        repeat(3) { deliver() }
        assertEquals(3, sink.written.size)
        assertTrue("the buffer is played verbatim", sink.written.all { it.size == SpeakerEngine.FRAME_SAMPLES })
        assertTrue("played audio, not silence", sink.written.all { w -> w.all { it == TONE } })
        assertEquals(0L, engine.droppedSamples.get())
    }

    @Test
    fun `an ineligible plan opens nothing and plays nothing`() {
        // Ineligibility arrives already reduced by SpeakerPlayoutPolicy (not streaming, the toggle
        // off, the cap absent, the descriptor not applied), so what the engine honours is the empty
        // plan, whatever produced it.
        engine.apply(SpeakerPlayoutPlan.IDLE)
        deliver()
        assertEquals(0, sink.opens.size)
        assertEquals(0, sink.written.size)
        assertEquals(SpeakerPlayoutState.Idle, engine.state.value)
        assertEquals(0, frames.installs.get())
    }

    @Test
    fun `going ineligible closes the output and drops later frames`() {
        engine.apply(plan(target()))
        deliver()
        engine.apply(SpeakerPlayoutPlan.IDLE)
        deliver()
        assertEquals("the output is released, not left open", 1, sink.closes.get())
        assertEquals("nothing may play after the plan went idle", 1, sink.written.size)
        assertEquals(SpeakerPlayoutState.Idle, engine.state.value)
    }

    @Test
    fun `two eligible slots each get their own output and their own frames`() {
        val a = target()
        val b = target(slotId = "-1000", index = CTRL_IDX + 1)
        engine.apply(plan(a, b))
        assertEquals(2, sink.opens.size)

        deliver(index = CTRL_IDX, pcm = window(TONE))
        deliver(index = CTRL_IDX + 1, pcm = window(OTHER_TONE))
        assertEquals(2, sink.written.size)
        assertEquals(setOf(TONE, OTHER_TONE), sink.written.map { it.first() }.toSet())

        // One slot leaving does not disturb the other.
        engine.apply(plan(a))
        assertEquals(1, sink.closes.get())
        deliver(index = CTRL_IDX + 1)
        deliver(index = CTRL_IDX)
        assertEquals(3, sink.written.size)
    }

    @Test
    fun `a frame for a slot that is not playing is dropped rather than throwing`() {
        engine.apply(plan(target()))
        deliver(handle = HANDLE + 1)
        deliver(index = CTRL_IDX + 3)
        assertEquals(0, sink.written.size)
    }

    @Test
    fun `a device that gives no output reports unavailable`() {
        sink.refuse.set(true)
        engine.apply(plan(target()))
        assertEquals(SpeakerPlayoutState.Unavailable, engine.state.value)
        deliver()
        assertEquals(0, sink.written.size)
        assertEquals("no sink means no reason to start the native dispatch thread", 0, frames.installs.get())
    }

    @Test
    fun `process stop closes the output even while the plan still says play`() {
        val owner = TestOwner()
        owner.registry.addObserver(engine)
        owner.registry.currentState = Lifecycle.State.STARTED
        engine.apply(plan(target()))
        assertEquals(1, sink.opens.size)

        // ON_STOP with the plan untouched: the actuator's contract says its inputs keep moving
        // while it is stopped, and an audio output must not outlive the session behind it.
        owner.registry.currentState = Lifecycle.State.CREATED
        assertEquals(1, sink.closes.get())
        assertEquals(SpeakerPlayoutState.Idle, engine.state.value)
        deliver()
        assertEquals(0, sink.written.size)
    }

    // ---- routing ----

    @Test
    fun `the slot's own endpoint is what the output is opened against`() {
        engine.apply(plan(target(playbackDeviceId = PAD_ENDPOINT)))
        assertEquals(listOf(PAD_ENDPOINT), sink.opens.toList())
    }

    @Test
    fun `a route change reopens the output against the new endpoint`() {
        // An AudioTrack's preferred device is settled when it is built, so a pad whose endpoint
        // appeared, moved or vanished needs a new track and not a new preference.
        engine.apply(plan(target()))
        engine.apply(plan(target(playbackDeviceId = PAD_ENDPOINT)))
        assertEquals(listOf(NO_AUDIO_DEVICE, PAD_ENDPOINT), sink.opens.toList())
        assertEquals(1, sink.closes.get())

        // The same route again is not a reopen.
        engine.apply(plan(target(playbackDeviceId = PAD_ENDPOINT)))
        assertEquals(2, sink.opens.size)
        assertEquals(1, sink.closes.get())
    }

    // ---- the native sink ----

    @Test
    fun `the native sink is installed only while something is playing`() {
        assertEquals(0, frames.installs.get())
        engine.apply(plan(target()))
        assertEquals(1, frames.installs.get())
        assertEquals(0, frames.uninstalls.get())

        // Re-applying does not re-install: the dispatch thread starts once.
        engine.apply(plan(target()))
        assertEquals(1, frames.installs.get())

        engine.apply(SpeakerPlayoutPlan.IDLE)
        assertEquals(1, frames.uninstalls.get())
        engine.apply(plan(target()))
        assertEquals(2, frames.installs.get())
    }

    @Test
    fun `frames arriving through the installed sink reach the slot's output`() {
        engine.apply(plan(target()))
        val installed = frames.sink!!
        installed.onSpeakerFrame(HANDLE, CTRL_IDX, window(), false)
        assertEquals(1, sink.written.size)
    }

    // ---- drop accounting ----

    @Test
    fun `a sink that takes only part of a window accounts the rest as dropped`() {
        engine.apply(plan(target()))
        sink.accept.set(SpeakerEngine.FRAME_SAMPLES / 2)
        deliver()
        assertEquals((SpeakerEngine.FRAME_SAMPLES / 2).toLong(), engine.droppedSamples.get())

        // A full sink drops the whole window; that is what pulls a stream running ahead back.
        sink.accept.set(0)
        deliver()
        assertEquals((SpeakerEngine.FRAME_SAMPLES / 2 + SpeakerEngine.FRAME_SAMPLES).toLong(), engine.droppedSamples.get())
    }

    @Test
    fun `concealed frames play like any other, since concealment already produced the samples`() {
        engine.apply(plan(target()))
        deliver(concealed = true)
        assertEquals(1, sink.written.size)
        assertEquals(0L, engine.droppedSamples.get())
    }

    // ---- the property the dispatch thread depends on ----

    @Test
    fun `a stuck sink stalls only its own stream, never the engine's other voices`() {
        val a = target()
        val b = target(slotId = "-1000", index = CTRL_IDX + 1)
        engine.apply(plan(a, b))

        // The real sink cannot stall: an AudioTrack written with WRITE_NON_BLOCKING returns a short
        // count instead of waiting. This pins the engine's half of that promise, which is that it
        // holds nothing shared across a write: no lock, no queue, no shared buffer. Park a writer
        // inside the first slot's sink and the second slot must still play, on a device where the
        // one dispatch thread carries every stream.
        sink.blockWrites.set(true)
        val stuck = Thread({ deliver(index = CTRL_IDX) }, "stuck-writer").also { it.start() }
        if (!awaitTrue { sink.writersParked.get() == 1 }) fail("the fake sink never parked a writer")

        sink.blockWrites.set(false)
        val done = CountDownLatch(1)
        Thread({
            deliver(index = CTRL_IDX + 1)
            done.countDown()
        }, "other-stream").start()
        assertTrue(
            "the second stream must play while the first is stuck",
            done.await(PARK_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )

        sink.gate.countDown()
        stuck.join(PARK_TIMEOUT_MS)
        assertFalse("the parked writer must finish once its sink drains", stuck.isAlive)
    }

    // ---- wiring ----

    @Test
    fun `the engine collects the playout composer once started`() =
        runTest(scope.testScheduler) {
            val owner = TestOwner()
            owner.registry.addObserver(engine)
            owner.registry.currentState = Lifecycle.State.STARTED
            scope.testScheduler.runCurrent()

            plans.value = plan(target())
            scope.testScheduler.runCurrent()
            assertEquals(1, sink.opens.size)
            assertNotEquals(SpeakerPlayoutState.Idle, engine.state.value)

            plans.value = SpeakerPlayoutPlan.IDLE
            scope.testScheduler.runCurrent()
            assertEquals(1, sink.closes.get())
        }

    private fun awaitTrue(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + PARK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private companion object {
        const val HANDLE = 7
        const val CTRL_IDX = 0
        const val PAD_ENDPOINT = 11
        const val TONE: Short = 4242
        const val OTHER_TONE: Short = -1234
        const val PARK_TIMEOUT_MS = 2_000L
        const val POLL_MS = 2L
    }
}
