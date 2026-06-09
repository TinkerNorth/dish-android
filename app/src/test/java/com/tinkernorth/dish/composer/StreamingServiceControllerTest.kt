// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingServiceControllerTest {
    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val scope = TestScope(StandardTestDispatcher())
    private val slots = MutableStateFlow(0)
    private val wakeState =
        mockk<WakeStateController> {
            every { streamingSlotCount } returns slots
        }

    private fun start(context: Context) {
        val controller = StreamingServiceController(context, wakeState, scope)
        val owner = TestOwner()
        owner.registry.addObserver(controller)
        owner.registry.currentState = Lifecycle.State.STARTED
        scope.testScheduler.runCurrent()
    }

    @Test
    fun `a refused service start is swallowed instead of crashing the collector`() =
        runTest(scope.testScheduler) {
            val context =
                mockk<Context>(relaxed = true) {
                    every { startService(any()) } throws IllegalStateException("fgs refused")
                    every { startForegroundService(any()) } throws IllegalStateException("fgs refused")
                }
            start(context)

            slots.value = 1
            scope.testScheduler.runCurrent()

            // Reaching the assert at all proves the thrown IllegalStateException did not escape apply().
            verify { context.startService(any()) }
        }

    @Test
    fun `a positive slot count starts the service`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            start(context)

            slots.value = 1
            scope.testScheduler.runCurrent()

            verify { context.startService(any()) }
        }
}
