// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.lights.FrameworkLightGateway
import com.tinkernorth.dish.source.lights.LightSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val PAD = 5
private const val CONNECTION = "sat:a"
private const val HANDLE = 9
private const val CONTROLLER = 0
private const val WAIT_SECONDS = 5L

// A native call that holds its caller until the test lets it go, so a test can stop the observer
// while that caller is mid-flight.
private class HeldCall {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    fun hold() {
        entered.countDown()
        release.await(WAIT_SECONDS, TimeUnit.SECONDS)
    }
}

// The one thread the observer collects on, kept so a test can watch it wait on the observer's lock.
private class CollectorThreads : ThreadFactory {
    val last = AtomicReference<Thread>()

    override fun newThread(runnable: Runnable): Thread = Thread(runnable).also(last::set)
}

// Waits until [thread] is parked on a monitor or has finished, whichever the code under test leads
// it to; a thread still running past the deadline fails the test.
private fun awaitBlockedOrDone(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
    while (thread.state != Thread.State.BLOCKED && thread.state != Thread.State.TERMINATED) {
        check(System.nanoTime() < deadline) { "${thread.name} neither waited on the lock nor finished" }
        Thread.onSpinWait()
    }
}

// The observer's own lifecycle, with its native slot calls and the light bars mocked, so the JVM can
// follow a pad being bound on start and a stop that no push outlives.
class PhysicalSlotBindingObserverLifecycleTest {
    private val deviceFlow = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val registry = mockk<PhysicalGamepadRegistry> { every { devices } returns deviceFlow }
    private val hub =
        mockk<ConnectionCoordinator>(relaxed = true) {
            every { bindings } returns MutableStateFlow(mapOf(PAD.toString() to CONNECTION))
            every { connections } returns MutableStateFlow(listOf(liveSatellite()))
        }
    private val connection =
        mockk<SatelliteConnection> {
            every { handle } returns HANDLE
            every { slots } returns
                MutableStateFlow(
                    mapOf(PAD.toString() to SatelliteConnection.SlotBinding(CONTROLLER, controllerType = 0, registered = true)),
                )
        }
    private val satellite = mockk<SatelliteConnectionManager>()
    private val bt = mockk<BluetoothGamepadRegistry> { every { isConnected(any()) } returns false }
    private val moonlight = mockk<MoonlightConnectionManager>()
    private val lights = mockk<FrameworkLightGateway>(relaxed = true)
    private val native = mockk<PhysicalInputNative>(relaxed = true)
    private val owner = mockk<LifecycleOwner>()
    private val collectors = CollectorThreads()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(collectors)

    // Stubbed outside a mockk block, where get(...) would resolve to MockK's own dynamic call.
    @Before
    fun setUp() {
        every { satellite.connections } returns MutableStateFlow(mapOf(CONNECTION to connection))
        every { satellite.get(CONNECTION) } returns connection
        every { moonlight.get(any()) } returns null
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun liveSatellite() =
        ConnectionSummary(
            id = CONNECTION,
            kind = ConnectionKind.SATELLITE,
            label = CONNECTION,
            detail = "",
            live = LinkState.Connected,
            boundSlotIds = listOf(PAD.toString()),
        )

    private fun observer(scope: CoroutineScope) =
        PhysicalSlotBindingObserver(
            registry = registry,
            hub = hub,
            satellite = satellite,
            bt = bt,
            moonlight = moonlight,
            frameworkLights = lights,
            native = native,
            scope = scope,
        )

    private fun onCollectorThread() = observer(CoroutineScope(executor.asCoroutineDispatcher()))

    private fun padPresent() {
        deviceFlow.value = mapOf(PAD to PhysicalGamepadRegistry.Device(id = PAD, name = "pad"))
    }

    // Everything already handed to the collector thread has run.
    private fun drainCollector() {
        executor.submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `onStart subscribes to the registry once`() {
        observer(CoroutineScope(Dispatchers.Unconfined)).onStart(owner)
        assertEquals(1, deviceFlow.subscriptionCount.value)
    }

    @Test
    fun `onStart twice keeps one collector`() {
        val observer = observer(CoroutineScope(Dispatchers.Unconfined))
        observer.onStart(owner)
        observer.onStart(owner)
        assertEquals(1, deviceFlow.subscriptionCount.value)
    }

    @Test
    fun `a push already binding a pad when the app stops finishes before the teardown`() {
        val binding = HeldCall()
        every { native.bindPhysicalSlotSatellite(PAD, HANDLE, CONTROLLER) } answers { binding.hold() }
        padPresent()
        val observer = onCollectorThread()
        observer.onStart(owner)
        assertTrue(binding.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))

        val stopper = thread { observer.onStop(owner) }
        awaitBlockedOrDone(stopper)
        binding.release.countDown()
        stopper.join()

        verifyOrder {
            lights.boundTo(PAD, LightSource(CONNECTION, CONTROLLER))
            lights.releaseAll()
        }
    }

    @Test
    fun `a pad that turns up while the app is stopping is not bound after the stop`() {
        val clearing = HeldCall()
        every { native.clearAllPhysicalSlots() } answers { clearing.hold() }
        val observer = onCollectorThread()
        observer.onStart(owner)
        drainCollector()

        val stopper = thread { observer.onStop(owner) }
        assertTrue(clearing.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        padPresent()
        awaitBlockedOrDone(collectors.last.get())
        clearing.release.countDown()
        stopper.join()
        drainCollector()

        verify(exactly = 0) { native.bindPhysicalSlotSatellite(any(), any(), any()) }
        verify(exactly = 0) { lights.boundTo(any(), any()) }
    }

    @Test
    fun `a start after a stop binds the pads again`() {
        padPresent()
        val observer = observer(CoroutineScope(Dispatchers.Unconfined))
        observer.onStart(owner)
        observer.onStop(owner)

        observer.onStart(owner)

        verify(exactly = 2) { native.bindPhysicalSlotSatellite(PAD, HANDLE, CONTROLLER) }
    }
}
