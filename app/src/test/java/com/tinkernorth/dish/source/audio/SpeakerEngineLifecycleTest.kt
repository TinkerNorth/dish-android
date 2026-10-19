// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.audio

import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.SpeakerPlayoutComposer
import com.tinkernorth.dish.hotpath.audio.SpeakerAudioBridge
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpeakerEngineLifecycleTest {
    private class Session(
        val endpoint: Int,
    ) : SpeakerPlayoutSession {
        val closes = AtomicInteger()
        val writes = AtomicInteger()

        override fun write(pcmStereo: ShortArray): Int {
            writes.incrementAndGet()
            return pcmStereo.size
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    private class Outputs : SpeakerPlayoutSink {
        val opening = PausedAudioOperation()
        val sessions = ConcurrentLinkedQueue<Session>()

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
            channels: Int,
            lane: PlayoutLane,
        ): SpeakerPlayoutSession {
            if (preferredDeviceId == OLD_ENDPOINT) opening.pause()
            val session = Session(preferredDeviceId)
            sessions += session
            return session
        }
    }

    private class Frames : SpeakerFrameSource {
        val installs = AtomicInteger()
        val uninstalls = AtomicInteger()
        val installed = CountDownLatch(1)

        @Volatile var sink: SpeakerAudioBridge.Sink? = null

        override fun install(sink: SpeakerAudioBridge.Sink) {
            this.sink = sink
            installs.incrementAndGet()
            installed.countDown()
        }

        override fun uninstall() {
            sink = null
            uninstalls.incrementAndGet()
        }
    }

    private class Harness : AutoCloseable {
        val owner = mockk<LifecycleOwner>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val plans = MutableStateFlow(speakerPlan(OLD_ENDPOINT))
        val outputs = Outputs()
        val frames = Frames()
        private val composer = mockk<SpeakerPlayoutComposer> { every { state } returns plans }
        val engine = SpeakerEngine(composer, outputs, frames, scope)

        fun collector(): Job = scope.coroutineContext[Job]!!.children.single()

        fun deliver() {
            val window = ShortArray(SpeakerEngine.FRAME_SAMPLES)
            engine.onAudioFrame(SPEAKER_SESSION_HANDLE, INDEX, SpeakerAudioBridge.LANE_SPEAKER, window, false)
        }

        override fun close() {
            engine.onStop(owner)
            outputs.opening.release()
            scope.cancel()
            awaitAudioJob(scope.coroutineContext[Job]!!)
        }
    }

    @Test
    fun `an output that finishes opening after stop is closed without installing playback`() {
        Harness().use { h ->
            h.engine.onStart(h.owner)
            h.outputs.opening.awaitEntered()
            val collector = h.collector()

            h.engine.onStop(h.owner)
            assertEquals(SpeakerPlayoutState.Idle, h.engine.state.value)
            h.outputs.opening.release()
            awaitAudioJob(collector)
            h.deliver()

            val session = h.outputs.sessions.single()
            assertEquals(1, session.closes.get())
            assertEquals(0, session.writes.get())
            assertEquals(0, h.frames.installs.get())
            assertNull(h.frames.sink)
            assertEquals(SpeakerPlayoutState.Idle, h.engine.state.value)
        }
    }

    @Test
    fun `stop during one open does not begin opening the remaining outputs`() {
        Harness().use { h ->
            h.plans.value = speakerPlan(OLD_ENDPOINT, NEW_ENDPOINT)
            h.engine.onStart(h.owner)
            h.outputs.opening.awaitEntered()
            val collector = h.collector()

            h.engine.onStop(h.owner)
            h.outputs.opening.release()
            awaitAudioJob(collector)

            assertEquals(1, h.outputs.sessions.size)
            assertEquals(
                OLD_ENDPOINT,
                h.outputs.sessions
                    .single()
                    .endpoint,
            )
            assertEquals(
                1,
                h.outputs.sessions
                    .single()
                    .closes
                    .get(),
            )
            assertEquals(0, h.frames.installs.get())
        }
    }

    @Test
    fun `an old output cannot replace the restarted lifecycle's current route`() {
        Harness().use { h ->
            h.engine.onStart(h.owner)
            h.outputs.opening.awaitEntered()
            val collector = h.collector()

            h.engine.onStop(h.owner)
            h.plans.value = speakerPlan(NEW_ENDPOINT)
            h.engine.onStart(h.owner)
            h.outputs.opening.release()
            awaitAudioJob(collector)
            assertTrue(h.frames.installed.await(AUDIO_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            h.deliver()

            val old = h.outputs.sessions.single { it.endpoint == OLD_ENDPOINT }
            val current = h.outputs.sessions.single { it.endpoint == NEW_ENDPOINT }
            assertEquals(1, old.closes.get())
            assertEquals(0, old.writes.get())
            assertEquals(0, current.closes.get())
            assertEquals(1, current.writes.get())
            assertEquals(1, h.frames.installs.get())
        }
    }

    @Test
    fun `stop closes retained and pending outputs exactly once each`() {
        Harness().use { h ->
            h.engine.apply(speakerPlan(NEW_ENDPOINT))
            val retained = h.outputs.sessions.single()
            h.plans.value = speakerPlan(NEW_ENDPOINT, OLD_ENDPOINT)
            h.engine.onStart(h.owner)
            h.outputs.opening.awaitEntered()
            val collector = h.collector()

            h.engine.onStop(h.owner)
            assertEquals(1, retained.closes.get())
            h.outputs.opening.release()
            awaitAudioJob(collector)
            h.deliver()

            assertEquals(2, h.outputs.sessions.size)
            assertTrue(h.outputs.sessions.all { it.closes.get() == 1 })
            assertTrue(h.outputs.sessions.all { it.writes.get() == 0 })
            assertEquals(1, h.frames.installs.get())
            assertEquals(1, h.frames.uninstalls.get())
            assertNull(h.frames.sink)
        }
    }

    @Test
    fun `a stopped engine ignores direct plans and a new lifecycle can play again`() {
        Harness().use { h ->
            h.engine.onStop(h.owner)
            h.engine.apply(speakerPlan(NEW_ENDPOINT))
            assertTrue(h.outputs.sessions.isEmpty())

            h.plans.value = speakerPlan(NEW_ENDPOINT)
            h.engine.onStart(h.owner)
            assertTrue(h.frames.installed.await(AUDIO_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            h.deliver()
            assertEquals(
                1,
                h.outputs.sessions
                    .single()
                    .writes
                    .get(),
            )
        }
    }

    private companion object {
        const val OLD_ENDPOINT = 11
        const val NEW_ENDPOINT = 22
        const val INDEX = 0
    }
}

private fun speakerPlan(vararg endpoints: Int): SpeakerPlayoutPlan {
    val targets = endpoints.mapIndexed { index, endpoint -> SpeakerTarget("slot-$index", SPEAKER_SESSION_HANDLE, index, endpoint) }
    return SpeakerPlayoutPlan(targets.associateBy { speakerRouteKey(it.sessionHandle, it.controllerIndex, it.lane) })
}

private const val SPEAKER_SESSION_HANDLE = 7
