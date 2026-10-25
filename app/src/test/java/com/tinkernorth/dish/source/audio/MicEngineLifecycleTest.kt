// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.audio

import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.MicCaptureComposer
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MicEngineLifecycleTest {
    private class Routes : SlotAudioRoutes {
        override val changes = MutableStateFlow(emptyMap<Int, PadAudioRoute>())
        val lookup = PausedAudioOperation()
        var pauseLookup = true
        private val first = AtomicBoolean(true)

        override fun forSlot(slotId: String): PadAudioRoute {
            if (pauseLookup && first.getAndSet(false)) lookup.pause()
            val endpoint = if (slotId == OLD_SLOT) OLD_ENDPOINT else NEW_ENDPOINT
            return PadAudioRoute(microphone = true, speaker = false, captureDeviceId = endpoint)
        }
    }

    private class Loops : MicCaptureLoopFactory {
        val joined = PausedAudioOperation()
        var pauseJoin = false
        val starts = ConcurrentLinkedQueue<Int>()
        val started = CountDownLatch(1)
        private val pending = ConcurrentLinkedQueue<() -> Unit>()
        private val firstJoin = AtomicBoolean(true)

        override fun create(preferredDeviceId: Int): MicCaptureLoop = Loop(preferredDeviceId)

        fun runPending() {
            for (body in pending) body()
        }

        private inner class Loop(
            private val endpoint: Int,
        ) : MicCaptureLoop {
            override fun start(body: () -> Unit) {
                pending += body
                starts += endpoint
                started.countDown()
            }

            override fun join() {
                if (pauseJoin && firstJoin.getAndSet(false)) joined.pause()
            }
        }
    }

    private class Harness : AutoCloseable {
        val owner = mockk<LifecycleOwner>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val plans = MutableStateFlow(plan(OLD_SLOT))
        val routes = Routes()
        val loops = Loops()
        val opens = AtomicInteger()
        private val composer = mockk<MicCaptureComposer> { every { state } returns plans }
        private val manager = mockk<SatelliteConnectionManager>()
        val engine = MicEngine(composer, manager, MicCaptureSource(::openCapture), loops, routes, scope)

        private fun openCapture(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): MicCaptureSession? {
            check(frameSamples == MicEngine.FRAME_SAMPLES)
            check(preferredDeviceId == OLD_ENDPOINT || preferredDeviceId == NEW_ENDPOINT)
            opens.incrementAndGet()
            return null
        }

        fun collector(): Job = scope.coroutineContext[Job]!!.children.single()

        override fun close() {
            engine.onStop(owner)
            routes.lookup.release()
            loops.joined.release()
            scope.cancel()
            awaitAudioJob(scope.coroutineContext[Job]!!)
        }
    }

    @Test
    fun `a collector paused in routing cannot start capture after stop`() {
        Harness().use { h ->
            h.engine.onStart(h.owner)
            h.routes.lookup.awaitEntered()
            val collector = h.collector()

            h.engine.onStop(h.owner)
            assertEquals(MicCaptureState.Idle, h.engine.state.value)
            h.routes.lookup.release()
            awaitAudioJob(collector)

            assertTrue(h.loops.starts.isEmpty())
            assertTrue(h.engine.quiescent)
            assertEquals(MicCaptureState.Idle, h.engine.state.value)
        }
    }

    @Test
    fun `a routing result from before stop cannot arm a restarted engine`() {
        Harness().use { h ->
            h.engine.onStart(h.owner)
            h.routes.lookup.awaitEntered()
            val collector = h.collector()

            h.engine.onStop(h.owner)
            h.plans.value = plan(NEW_SLOT)
            h.engine.onStart(h.owner)
            h.routes.lookup.release()
            awaitAudioJob(collector)
            assertTrue(h.loops.started.await(AUDIO_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            assertEquals(listOf(NEW_ENDPOINT), h.loops.starts.toList())
        }
    }

    @Test
    fun `stop does not wait for a recorder join and its late completion cannot start capture`() {
        Harness().use { h ->
            h.routes.pauseLookup = false
            h.loops.pauseJoin = true
            h.engine.onStart(h.owner)
            h.loops.joined.awaitEntered()
            val collector = h.collector()

            h.engine.onStop(h.owner)
            h.loops.joined.release()
            awaitAudioJob(collector)

            assertTrue(h.loops.starts.isEmpty())
            assertTrue(h.engine.quiescent)
            assertEquals(MicCaptureState.Idle, h.engine.state.value)
        }
    }

    @Test
    fun `a capture body first scheduled after stop never opens the microphone`() {
        Harness().use { h ->
            h.routes.pauseLookup = false
            h.engine.apply(plan(OLD_SLOT))
            assertEquals(listOf(OLD_ENDPOINT), h.loops.starts.toList())

            h.engine.onStop(h.owner)
            h.loops.runPending()

            assertEquals(0, h.opens.get())
            assertTrue(h.engine.quiescent)
            assertEquals(MicCaptureState.Idle, h.engine.state.value)
        }
    }

    @Test
    fun `a stopped engine ignores direct plans until a new lifecycle starts`() {
        Harness().use { h ->
            h.routes.pauseLookup = false
            h.engine.onStop(h.owner)
            h.engine.apply(plan(OLD_SLOT))
            assertTrue(h.loops.starts.isEmpty())

            h.plans.value = plan(NEW_SLOT)
            h.engine.onStart(h.owner)
            assertTrue(h.loops.started.await(AUDIO_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(listOf(NEW_ENDPOINT), h.loops.starts.toList())
        }
    }

    private companion object {
        const val OLD_SLOT = "old-slot"
        const val NEW_SLOT = "new-slot"
        const val OLD_ENDPOINT = 11
        const val NEW_ENDPOINT = 22
    }
}

private fun plan(slotId: String): MicCapturePlan {
    val target = MicCaptureTarget(slotId, "connection")
    return MicCapturePlan(setOf(target), setOf(target))
}
