// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import com.tinkernorth.dish.architecture.testing.ControllerProbe
import com.tinkernorth.dish.architecture.testing.probe
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
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingServiceControllerTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val slots = MutableStateFlow(0)
    private val claims = MutableStateFlow<Map<Int, UsbController>>(emptyMap())
    private val liveness = StreamingServiceLiveness()
    private val crashReporting = mockk<CrashReportingController>(relaxed = true)
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

    private fun start(context: Context): ControllerProbe<StreamingServiceController.Input> {
        val probe =
            StreamingServiceController(context, wakeState, usbGamepadManager, liveness, crashReporting, scope).probe()
        probe.start()
        settle()
        return probe
    }

    private fun settle() = scope.testScheduler.runCurrent()

    private fun serviceComesUp() {
        liveness.markLive()
        settle()
    }

    private fun serviceGoesAway() {
        liveness.markGone()
        settle()
    }

    @Test
    fun `a positive slot count starts the service`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            start(context)
            slots.value = 1
            settle()
            verify(exactly = 1) { context.startService(any()) }
        }

    @Test
    fun `a direct claim alone starts the service`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            start(context)
            claims.value = directClaim()
            settle()
            verify(exactly = 1) { context.startService(any()) }
        }

    @Test
    fun `a refused start is swallowed and recorded as a non-fatal`() =
        runTest(scope.testScheduler) {
            val refusal = IllegalStateException("fgs refused")
            val context =
                mockk<Context>(relaxed = true) {
                    every { startService(any()) } throws refusal
                }
            start(context)
            slots.value = 1
            settle()
            verify(exactly = 1) { context.startService(any()) }
            verify(exactly = 1) { crashReporting.recordNonFatal(refusal) }
        }

    @Test
    fun `work falling back to zero never stops the service`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            start(context)
            slots.value = 1
            settle()
            slots.value = 0
            settle()
            serviceComesUp()
            slots.value = 2
            settle()
            slots.value = 0
            settle()
            verify(exactly = 0) { context.stopService(any()) }
        }

    @Test
    fun `a process stop never stops the service, with or without a held claim`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            val probe = start(context)
            slots.value = 1
            settle()
            serviceComesUp()
            probe.stop()
            settle()
            probe.start()
            settle()
            claims.value = directClaim()
            settle()
            probe.stop()
            settle()
            verify(exactly = 0) { context.stopService(any()) }
        }

    @Test
    fun `a live service is not started again on a foreground return`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            val probe = start(context)
            claims.value = directClaim()
            settle()
            serviceComesUp()
            probe.stop()
            settle()
            probe.start()
            settle()
            verify(exactly = 1) { context.startService(any()) }
        }

    @Test
    fun `a foreground return starts a service that is not live while work is held`() =
        runTest(scope.testScheduler) {
            var attempts = 0
            val context =
                mockk<Context>(relaxed = true) {
                    every { startService(any()) } answers {
                        attempts += 1
                        if (attempts == 1) error("fgs refused")
                        null
                    }
                }
            val probe = start(context)
            slots.value = 1
            settle()
            probe.stop()
            settle()
            probe.start()
            settle()
            assertEquals(2, attempts)
        }

    @Test
    fun `a start that never reported live is not repeated before the next foreground return`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            val probe = start(context)
            slots.value = 1
            settle()
            claims.value = directClaim()
            settle()
            slots.value = 0
            settle()
            slots.value = 1
            settle()
            verify(exactly = 1) { context.startService(any()) }
            probe.stop()
            settle()
            probe.start()
            settle()
            verify(exactly = 2) { context.startService(any()) }
        }

    @Test
    fun `a service that went away is started again when work rises`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            start(context)
            slots.value = 1
            settle()
            serviceComesUp()
            slots.value = 0
            settle()
            serviceGoesAway()
            slots.value = 1
            settle()
            verify(exactly = 2) { context.startService(any()) }
        }

    @Test
    fun `a service that stopped itself under held work waits for work to rise again`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            start(context)
            slots.value = 1
            settle()
            serviceComesUp()
            serviceGoesAway()
            verify(exactly = 1) { context.startService(any()) }
            slots.value = 0
            settle()
            slots.value = 1
            settle()
            verify(exactly = 2) { context.startService(any()) }
        }

    @Test
    fun `more work while the service is up never starts it again`() =
        runTest(scope.testScheduler) {
            val context = mockk<Context>(relaxed = true)
            start(context)
            slots.value = 1
            settle()
            serviceComesUp()
            claims.value = directClaim()
            settle()
            slots.value = 3
            settle()
            verify(exactly = 1) { context.startService(any()) }
        }

    @Test
    fun `no sequence of work, liveness and lifecycle changes stops the service or starts a live one`() =
        runTest(scope.testScheduler) {
            for (seed in 1..8) {
                val random = Random(seed)
                val startsWhileLive = mutableListOf<Int>()
                var step = 0
                val context =
                    mockk<Context>(relaxed = true) {
                        every { startService(any()) } answers {
                            if (liveness.state.value) startsWhileLive += step
                            null
                        }
                    }
                slots.value = 0
                claims.value = emptyMap()
                liveness.markGone()
                val probe = start(context)
                var collecting = true
                repeat(400) {
                    step = it
                    when (random.nextInt(5)) {
                        0 -> slots.value = random.nextInt(3)
                        1 -> claims.value = if (claims.value.isEmpty()) directClaim() else emptyMap()
                        2 -> liveness.markLive()
                        3 -> liveness.markGone()
                        else -> {
                            if (collecting) probe.stop() else probe.start()
                            collecting = !collecting
                        }
                    }
                    settle()
                }
                if (collecting) probe.stop()
                settle()
                verify(exactly = 0) { context.stopService(any()) }
                assertEquals("seed $seed", emptyList<Int>(), startsWhileLive)
            }
        }
}
