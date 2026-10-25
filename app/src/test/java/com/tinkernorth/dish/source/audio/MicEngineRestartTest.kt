// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.audio

import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.MicCaptureComposer
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MicEngineRestartTest {
    private class Capture : MicCaptureSource {
        val oldRead = PausedAudioOperation()
        val currentRead = PausedAudioOperation()
        val reopened = CountDownLatch(1)
        val opens = AtomicInteger()
        val closes = AtomicInteger()

        override fun open(
            frameSamples: Int,
            preferredDeviceId: Int,
        ): MicCaptureSession {
            val first = opens.incrementAndGet() == 1
            if (!first) reopened.countDown()
            return Session(if (first) oldRead else currentRead)
        }

        private inner class Session(
            private val read: PausedAudioOperation,
        ) : MicCaptureSession {
            override val voiceProcessed = true
            private var delivered = false

            override fun read(out: ShortArray): Int {
                if (delivered) return 0
                delivered = true
                read.pause()
                out.fill(TONE)
                return out.size
            }

            override fun close() {
                closes.incrementAndGet()
            }
        }
    }

    private class Loops : MicCaptureLoopFactory {
        val restarting = CountDownLatch(1)
        private val joins = AtomicInteger()
        private val loop = ThreadMicCaptureLoop("mic-restart-test")

        override fun create(preferredDeviceId: Int): MicCaptureLoop = Loop()

        private inner class Loop : MicCaptureLoop {
            override fun start(body: () -> Unit) = loop.start(body)

            override fun join() {
                if (joins.incrementAndGet() > 1) restarting.countDown()
                loop.join()
            }
        }

        fun drain() = loop.join()
    }

    @Test
    fun `restart waits for the old capture body and never forwards its held window`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = mockk<LifecycleOwner>()
        val plans = MutableStateFlow(capturePlan(OLD_SLOT))
        val composer = mockk<MicCaptureComposer> { every { state } returns plans }
        val connection = mockk<SatelliteConnection>()
        every { connection.sendMicFrame(any(), any()) } returns true
        val manager = mockk<SatelliteConnectionManager>()
        every { manager.get(CONNECTION) } returns connection
        val routing = MapSlotAudioRoutes(MutableStateFlow(emptyMap()), emptyMap())
        val capture = Capture()
        val loops = Loops()
        val engine = MicEngine(composer, manager, capture, loops, routing, scope)

        try {
            engine.onStart(owner)
            capture.oldRead.awaitEntered()
            engine.onStop(owner)
            plans.value = capturePlan(NEW_SLOT)
            engine.onStart(owner)
            assertTrue(loops.restarting.await(AUDIO_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            assertFalse(capture.reopened.await(PAST_OLD_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(1, capture.opens.get())
            assertFalse(engine.quiescent)

            capture.oldRead.release()
            capture.currentRead.awaitEntered()
            assertEquals(1, capture.closes.get())
            verify(exactly = 0) { connection.sendMicFrame(any(), any()) }
            assertFalse(engine.quiescent)

            engine.onStop(owner)
            capture.currentRead.release()
            loops.drain()
            assertTrue(engine.quiescent)
            assertEquals(2, capture.closes.get())
            verify(exactly = 0) { connection.sendMicFrame(any(), any()) }
        } finally {
            engine.onStop(owner)
            capture.oldRead.release()
            capture.currentRead.release()
            scope.cancel()
            awaitAudioJob(scope.coroutineContext[Job]!!)
            loops.drain()
        }
    }

    private companion object {
        const val OLD_SLOT = "old-slot"
        const val NEW_SLOT = "new-slot"
        const val CONNECTION = "connection"
        const val TONE: Short = 4242
        const val PAST_OLD_JOIN_TIMEOUT_MS = 750L
    }
}

private fun capturePlan(slotId: String): MicCapturePlan {
    val target = MicCaptureTarget(slotId, "connection")
    return MicCapturePlan(setOf(target), setOf(target))
}
