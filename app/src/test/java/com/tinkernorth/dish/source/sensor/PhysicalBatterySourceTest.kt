// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.BatteryState
import android.os.BatteryManager
import android.view.InputDevice
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.PhysicalReachabilityComposer
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.connection.TelemetrySink
import com.tinkernorth.dish.source.store.BatteryStatusStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalBatterySourceTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val deviceFlow = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val reachableFlow = MutableStateFlow<Map<String, TelemetrySink>>(emptyMap())
    private val registry =
        mockk<PhysicalGamepadRegistry> {
            every { devices } returns deviceFlow
        }
    private val reachability =
        mockk<PhysicalReachabilityComposer> {
            every { state } returns reachableFlow
        }
    private val store = BatteryStatusStore()
    private val native = mockk<PhysicalInputNative>(relaxed = true)
    private val bluetoothBattery =
        mockk<BluetoothBatteryReader> {
            every { readLevel(any()) } returns null
        }
    private val receiver = slot<BroadcastReceiver>()
    private val context =
        mockk<Context>(relaxed = true) {
            every { registerReceiver(capture(receiver), any(), any(), any()) } returns null
        }
    private val owner = mockk<LifecycleOwner>()
    private val pads = mutableMapOf<Int, InputDevice>()
    private val lookups = mutableListOf<Int>()
    private val batteryReads = mutableListOf<Int>()

    private fun pad(
        deviceId: Int,
        level: Float = 0.5f,
    ): InputDevice {
        val state =
            mockk<BatteryState> {
                every { isPresent } returns true
                every { capacity } returns level
                every { status } returns BatteryManager.BATTERY_STATUS_DISCHARGING
            }
        return mockk<InputDevice> {
            every { id } returns deviceId
            every { name } returns "Pad $deviceId"
            every { batteryState } answers {
                batteryReads += deviceId
                state
            }
        }
    }

    private fun device(
        deviceId: Int,
        transport: Transport = Transport.Bluetooth,
        synthetic: Boolean = false,
        transitioning: Boolean = false,
        disconnectingSec: Int? = null,
    ) = PhysicalGamepadRegistry.Device(
        id = deviceId,
        name = "Pad $deviceId",
        disconnectingTimeLeftSec = disconnectingSec,
        isUsbSynthetic = synthetic,
        transitioning = transitioning,
        transport = transport,
    )

    private fun source(): PhysicalBatterySource {
        val reader =
            PadBatteryReader(native, bluetoothBattery, sdkInt = 31) { deviceId ->
                lookups += deviceId
                pads[deviceId]
            }
        return PhysicalBatterySource(context, registry, reachability, store, scope, reader)
    }

    private fun settle() = scope.testScheduler.runCurrent()

    private fun started(): PhysicalBatterySource {
        val s = source()
        s.onStart(owner)
        settle()
        return s
    }

    private fun reachableChange(tag: String) {
        reachableFlow.value = mapOf(tag to mockk<TelemetrySink>(relaxed = true))
        settle()
    }

    private fun chargingIntent(status: Int): Intent =
        mockk {
            every { getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns status
        }

    @Test
    fun `a framework pad is looked up once and its battery read on every poll`() {
        pads[7] = pad(7)
        deviceFlow.value = mapOf(7 to device(7))
        started()
        repeat(5) { reachableChange("r$it") }
        assertEquals(listOf(7), lookups)
        assertTrue(batteryReads.count { it == 7 } >= 5)
        assertEquals(50, store.samples.value["7"]?.level)
    }

    @Test
    fun `a burst of triggers collapses instead of polling once per trigger`() {
        pads[7] = pad(7)
        deviceFlow.value = mapOf(7 to device(7))
        started()
        val before = batteryReads.size
        repeat(10) {
            val status =
                if (it % 2 == 0) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING
            receiver.captured.onReceive(context, chargingIntent(status))
        }
        settle()
        val polls = batteryReads.size - before
        assertTrue("10 triggers produced $polls polls", polls in 1..2)
    }

    @Test
    fun `a pad that is transitioning or disconnecting is left alone`() {
        pads[7] = pad(7)
        pads[8] = pad(8)
        pads[9] = pad(9)
        deviceFlow.value =
            mapOf(
                7 to device(7),
                8 to device(8, transitioning = true),
                9 to device(9, disconnectingSec = 3),
            )
        started()
        assertEquals(listOf(7), lookups)
        assertEquals(setOf("7"), store.samples.value.keys)
    }

    @Test
    fun `a pad that leaves the registry is evicted and looked up again if it returns`() {
        pads[7] = pad(7)
        deviceFlow.value = mapOf(7 to device(7))
        started()
        deviceFlow.value = emptyMap()
        settle()
        assertNull(store.samples.value["7"])
        deviceFlow.value = mapOf(7 to device(7))
        settle()
        assertEquals(listOf(7, 7), lookups)
        assertEquals(50, store.samples.value["7"]?.level)
    }

    @Test
    fun `a pad whose lookup fails is retried on the next poll`() {
        deviceFlow.value = mapOf(7 to device(7))
        started()
        val misses = lookups.size
        assertTrue(misses >= 1 && lookups.all { it == 7 })
        assertNull(store.samples.value["7"])
        pads[7] = pad(7)
        reachableChange("retry")
        assertEquals(misses + 1, lookups.size)
        assertEquals(50, store.samples.value["7"]?.level)
        reachableChange("cached")
        assertEquals(misses + 1, lookups.size)
    }

    @Test
    fun `a Direct pad never touches the framework lookup`() {
        every { native.getDirectPadBattery(-1000) } returns ((60 shl 8) or 1)
        deviceFlow.value = mapOf(-1000 to device(-1000, transport = Transport.Usb, synthetic = true))
        started()
        assertTrue(lookups.isEmpty())
        verify(atLeast = 1) { native.getDirectPadBattery(-1000) }
    }

    @Test
    fun `only reachable slots reach the wire`() {
        pads[7] = pad(7)
        pads[8] = pad(8)
        deviceFlow.value = mapOf(7 to device(7), 8 to device(8))
        val sink = mockk<TelemetrySink>(relaxed = true)
        reachableFlow.value = mapOf("7" to sink)
        started()
        verify(atLeast = 1) { sink.sendBattery("7", 50, any()) }
        verify(exactly = 0) { sink.sendBattery("8", any(), any()) }
    }

    @Test
    fun `stopping the source halts polling and clears the display`() {
        pads[7] = pad(7)
        deviceFlow.value = mapOf(7 to device(7))
        val s = started()
        assertEquals(50, store.samples.value["7"]?.level)
        s.onStop(owner)
        settle()
        assertTrue(store.samples.value.isEmpty())
        val before = batteryReads.size
        reachableChange("after-stop")
        assertEquals(before, batteryReads.size)
    }

    @Test
    fun `restarting after a stop resumes with the cached device`() {
        pads[7] = pad(7)
        deviceFlow.value = mapOf(7 to device(7))
        val s = started()
        s.onStop(owner)
        settle()
        s.onStart(owner)
        settle()
        assertEquals(listOf(7), lookups)
        assertEquals(50, store.samples.value["7"]?.level)
    }
}
