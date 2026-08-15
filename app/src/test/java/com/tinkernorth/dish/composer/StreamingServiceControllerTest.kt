// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.tinkernorth.dish.source.usb.UsbController
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.UsbPhase
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
    private val claims = MutableStateFlow<Map<Int, UsbController>>(emptyMap())
    private val wakeState =
        mockk<WakeStateController> {
            every { streamingSlotCount } returns slots
        }
    private val usbGamepadManager =
        mockk<UsbGamepadManager> {
            every { controllers } returns claims
        }

    private fun directClaim(): Map<Int, UsbController> =
        mapOf(
            1 to
                UsbController(
                    vendorId = 0x28DE,
                    productId = 0x1102,
                    name = "Valve Steam Controller",
                    phase = UsbPhase.Direct,
                    syntheticId = -1000,
                ),
        )

    private fun start(context: Context): TestOwner {
        val controller = StreamingServiceController(context, wakeState, usbGamepadManager, scope)
        val owner = TestOwner()
        owner.registry.addObserver(controller)
        owner.registry.currentState = Lifecycle.State.STARTED
        scope.testScheduler.runCurrent()
        return owner
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

    @Test
    fun `a direct claim alone starts the service`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            start(context)

            claims.value = directClaim()
            scope.testScheduler.runCurrent()

            verify { context.startService(any()) }
        }

    // The claimed pad has been reconfigured at the device level; only a live process can run the
    // restore a later release performs, so backgrounding must not drop the service that keeps it.
    @Test
    fun `process stop with a held claim keeps the service running`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            val owner = start(context)

            slots.value = 1
            claims.value = directClaim()
            scope.testScheduler.runCurrent()

            owner.registry.currentState = Lifecycle.State.CREATED
            scope.testScheduler.runCurrent()

            verify(exactly = 0) { context.stopService(any()) }
        }

    @Test
    fun `process stop without claims stops the service`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            val owner = start(context)

            slots.value = 1
            scope.testScheduler.runCurrent()

            owner.registry.currentState = Lifecycle.State.CREATED
            scope.testScheduler.runCurrent()

            verify { context.stopService(any()) }
        }

    // The service can stop itself while collection is down (claims released in the background), so
    // a foreground return must re-derive instead of trusting the stale running flag.
    @Test
    fun `a foreground return re-asserts the service for work still held`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            val owner = start(context)

            claims.value = directClaim()
            scope.testScheduler.runCurrent()
            verify(exactly = 1) { context.startService(any()) }

            owner.registry.currentState = Lifecycle.State.CREATED
            scope.testScheduler.runCurrent()
            owner.registry.currentState = Lifecycle.State.STARTED
            scope.testScheduler.runCurrent()

            // A second start against a live service is a harmless refresh; against one that
            // self-stopped in the background it is the restart that keeps the claim protected.
            verify(exactly = 2) { context.startService(any()) }
        }
}
