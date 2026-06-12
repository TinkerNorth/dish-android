// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.inputrate

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.lowpower.LowPowerSignal
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class InputRateStoreTest {
    private lateinit var native: PhysicalInputNative
    private lateinit var registry: PhysicalGamepadRegistry
    private lateinit var signal: LowPowerSignal
    private lateinit var store: InputRateStore

    private val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())

    private val slotId = "7"

    @Before
    fun setUp() {
        native = mockk(relaxed = true)
        registry = mockk()
        every { registry.devices } returns devices
        signal = LowPowerSignal()
        store = InputRateStore(registry, native, signal, CoroutineScope(SupervisorJob()))
    }

    private fun slotRates(id: String = slotId): SlotInputRates? = store.state.value.slots[id]

    private fun seedFramework(id: Int = 7) {
        devices.value = mapOf(id to PhysicalGamepadRegistry.Device(id = id, name = "Pad"))
    }

    private fun seedSynthetic(id: Int = -1000) {
        devices.value =
            mapOf(id to PhysicalGamepadRegistry.Device(id = id, name = "Pad", isUsbSynthetic = true))
    }

    @Test
    fun `framework device derives controller rate from the event-count delta and holds it through idle`() {
        seedFramework()
        every { native.getDeviceInputEventCount(7) } returns 0L
        store.sampleAll(nowMs = 1000L)
        every { native.getDeviceInputEventCount(7) } returns 60L
        store.sampleAll(nowMs = 1500L)
        assertEquals(SlotInputRates(controllerHz = 120, controllerPeakHz = 120), slotRates())
        store.sampleAll(nowMs = 2000L)
        assertEquals(SlotInputRates(controllerHz = 120, controllerPeakHz = 120), slotRates())
    }

    @Test
    fun `synthetic device reads the urb and native motion counters`() {
        seedSynthetic()
        every { native.getDeviceUrbCount(-1000) } returns 0L
        every { native.getDeviceMotionCount(-1000) } returns 0L
        store.sampleAll(nowMs = 1000L)
        every { native.getDeviceUrbCount(-1000) } returns 500L
        every { native.getDeviceMotionCount(-1000) } returns 62L
        store.sampleAll(nowMs = 1500L)
        assertEquals(
            SlotInputRates(controllerHz = 1000, controllerPeakHz = 1000, gyroHz = 125),
            slotRates("-1000"),
        )
    }

    @Test
    fun `recorded motion samples feed the slot's gyro rate`() {
        seedFramework()
        every { native.getDeviceInputEventCount(7) } returns 0L
        store.sampleAll(nowMs = 1000L)
        repeat(125) { store.recordMotionSample(slotId) }
        store.sampleAll(nowMs = 1500L)
        assertEquals(250, slotRates()?.gyroHz)
    }

    @Test
    fun `screen samples feed one device-wide peak`() {
        store.sampleAll(nowMs = 1000L)
        repeat(60) { store.recordScreenSample() }
        store.sampleAll(nowMs = 1500L)
        assertEquals(120, store.state.value.screenPeakHz)
        store.sampleAll(nowMs = 2000L)
        assertEquals(120, store.state.value.screenPeakHz)
    }

    @Test
    fun `virtual slot is tracked without a device`() {
        store.sampleAll(nowMs = 1000L)
        repeat(125) { store.recordMotionSample(VIRTUAL_SLOT_ID) }
        store.sampleAll(nowMs = 1500L)
        assertEquals(SlotInputRates(gyroHz = 250), slotRates(VIRTUAL_SLOT_ID))
    }

    @Test
    fun `low power holds published rates and drops the stale window on exit`() {
        seedFramework()
        every { native.getDeviceInputEventCount(7) } returns 0L
        store.sampleAll(nowMs = 1000L)
        every { native.getDeviceInputEventCount(7) } returns 60L
        store.sampleAll(nowMs = 1500L)
        assertEquals(120, slotRates()?.controllerHz)

        signal.setActive(true)
        every { native.getDeviceInputEventCount(7) } returns 70L
        store.sampleAll(nowMs = 4000L)
        assertEquals(120, slotRates()?.controllerHz)

        signal.setActive(false)
        every { native.getDeviceInputEventCount(7) } returns 80L
        store.sampleAll(nowMs = 4500L)
        assertEquals(120, slotRates()?.controllerHz)
        assertEquals(120, slotRates()?.controllerPeakHz)
        every { native.getDeviceInputEventCount(7) } returns 200L
        store.sampleAll(nowMs = 5000L)
        assertEquals(240, slotRates()?.controllerHz)
    }

    @Test
    fun `a slot with no measured input publishes no entry`() {
        seedFramework()
        every { native.getDeviceInputEventCount(7) } returns 0L
        store.sampleAll(nowMs = 1000L)
        store.sampleAll(nowMs = 1500L)
        assertEquals(null, slotRates())
        assertEquals(null, slotRates(VIRTUAL_SLOT_ID))
        assertEquals(0, store.state.value.screenPeakHz)
    }

    @Test
    fun `a removed device is dropped from published rates`() {
        seedFramework()
        every { native.getDeviceInputEventCount(7) } returns 0L
        store.sampleAll(nowMs = 1000L)
        every { native.getDeviceInputEventCount(7) } returns 60L
        store.sampleAll(nowMs = 1500L)
        assertEquals(120, slotRates()?.controllerHz)
        devices.value = emptyMap()
        store.sampleAll(nowMs = 2000L)
        assertEquals(null, slotRates())
    }

    @Test
    fun `re-adding a device starts from fresh trackers and counters`() {
        seedFramework()
        every { native.getDeviceInputEventCount(7) } returns 0L
        store.sampleAll(nowMs = 1000L)
        repeat(125) { store.recordMotionSample(slotId) }
        store.sampleAll(nowMs = 1500L)
        assertEquals(250, slotRates()?.gyroHz)

        devices.value = emptyMap()
        store.sampleAll(nowMs = 2000L)

        seedFramework()
        store.sampleAll(nowMs = 2500L)
        assertEquals(null, slotRates())
        repeat(25) { store.recordMotionSample(slotId) }
        store.sampleAll(nowMs = 3000L)
        assertEquals(50, slotRates()?.gyroHz)
    }

    @Test
    fun `screen peak survives a low power pause and its stale window is dropped`() {
        store.sampleAll(nowMs = 1000L)
        repeat(60) { store.recordScreenSample() }
        store.sampleAll(nowMs = 1500L)
        assertEquals(120, store.state.value.screenPeakHz)

        signal.setActive(true)
        store.sampleAll(nowMs = 4000L)
        assertEquals(120, store.state.value.screenPeakHz)

        signal.setActive(false)
        repeat(200) { store.recordScreenSample() }
        store.sampleAll(nowMs = 4500L)
        assertEquals(120, store.state.value.screenPeakHz)
        repeat(30) { store.recordScreenSample() }
        store.sampleAll(nowMs = 5000L)
        assertEquals(120, store.state.value.screenPeakHz)
    }
}
