// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PollRateSamplerTest {
    private lateinit var registry: PhysicalGamepadRegistry
    private lateinit var native: PhysicalInputNative
    private lateinit var sampler: PollRateSampler

    private val deviceId = -1000

    @Before
    fun setUp() {
        val ctx = mockk<Context>()
        every { ctx.getSystemService(Context.INPUT_SERVICE) } returns mockk<InputManager>(relaxed = true)
        every { ctx.getSystemService(Context.USB_SERVICE) } returns mockk<UsbManager>(relaxed = true)
        native = mockk(relaxed = true)
        registry = PhysicalGamepadRegistry(ctx, CoroutineScope(SupervisorJob()), native)
        sampler = PollRateSampler(registry, CoroutineScope(SupervisorJob()), native)
    }

    private fun seedSynthetic() {
        registry.addUsbSynthetic(
            deviceId = deviceId,
            name = "Pad",
            hasGyro = false,
            pollRateHz = 0,
            vendorId = 1,
            productId = 2,
        )
    }

    private fun rateOf(): Int? = registry.devices.value[deviceId]?.pollRateHz

    @Test
    fun `derives Hz from the URB-count delta over the sampling window`() {
        seedSynthetic()
        every { native.getDeviceUrbCount(deviceId) } returns 0L
        sampler.sampleAll(nowMs = 1000L)
        every { native.getDeviceUrbCount(deviceId) } returns 500L
        sampler.sampleAll(nowMs = 1500L)
        assertEquals(1000, rateOf())
    }

    @Test
    fun `first sample only snapshots and does not write a rate`() {
        seedSynthetic()
        every { native.getDeviceUrbCount(deviceId) } returns 500L
        sampler.sampleAll(nowMs = 1000L)
        assertEquals(0, rateOf())
    }

    @Test
    fun `an idle controller whose count stops moving reports zero rather than freezing`() {
        seedSynthetic()
        every { native.getDeviceUrbCount(deviceId) } returns 0L
        sampler.sampleAll(nowMs = 1000L)
        every { native.getDeviceUrbCount(deviceId) } returns 500L
        sampler.sampleAll(nowMs = 1500L)
        assertEquals(1000, rateOf())
        sampler.sampleAll(nowMs = 2000L)
        assertEquals(0, rateOf())
    }

    @Test
    fun `a counter reset cannot produce a negative rate`() {
        seedSynthetic()
        every { native.getDeviceUrbCount(deviceId) } returns 1000L
        sampler.sampleAll(nowMs = 1000L)
        every { native.getDeviceUrbCount(deviceId) } returns 5L
        sampler.sampleAll(nowMs = 1500L)
        assertEquals(0, rateOf())
    }

    @Test
    fun `a detached device is not resurrected by a later sample`() {
        seedSynthetic()
        every { native.getDeviceUrbCount(deviceId) } returns 100L
        sampler.sampleAll(nowMs = 1000L)
        registry.removeUsbSynthetic(deviceId)
        every { native.getDeviceUrbCount(deviceId) } returns 600L
        sampler.sampleAll(nowMs = 1500L)
        assertEquals(null, registry.devices.value[deviceId])
    }

    @Test
    fun `re-attaching the same id after detach starts from a fresh snapshot`() {
        seedSynthetic()
        every { native.getDeviceUrbCount(deviceId) } returns 5000L
        sampler.sampleAll(nowMs = 1000L)
        registry.removeUsbSynthetic(deviceId)
        sampler.sampleAll(nowMs = 1500L)

        seedSynthetic()
        every { native.getDeviceUrbCount(deviceId) } returns 10L
        sampler.sampleAll(nowMs = 2000L)
        assertEquals(0, rateOf())
        every { native.getDeviceUrbCount(deviceId) } returns 510L
        sampler.sampleAll(nowMs = 2500L)
        assertEquals(1000, rateOf())
    }
}
