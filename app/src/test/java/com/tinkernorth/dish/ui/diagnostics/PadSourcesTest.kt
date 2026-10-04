// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.PadAudioFactsStore
import com.tinkernorth.dish.source.bluetooth.BluetoothLinkType
import com.tinkernorth.dish.source.bluetooth.BluetoothPadLinkReader
import com.tinkernorth.dish.source.inputrate.FrameworkInputTimingStore
import com.tinkernorth.dish.source.inputrate.FrameworkTimingSummary
import com.tinkernorth.dish.source.store.StickTestHistoryStore
import com.tinkernorth.dish.source.store.StickTestRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PadSourcesTest {
    private val dispatcher = StandardTestDispatcher()
    private val devices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val history = MutableStateFlow<Map<String, StickTestRecord>>(emptyMap())
    private val registry = mockk<PhysicalGamepadRegistry> { every { devices } returns this@PadSourcesTest.devices }
    private val native =
        mockk<PhysicalInputNative> {
            every { deviceLatencyJson(any()) } returns ""
            every { deviceInfoJson(any()) } returns ""
            every { getDeviceUrbErrorCount(any()) } returns URB_ERRORS
            every { getDeviceUrbCount(any()) } returns URB_COUNT
            every { getDeviceInputEventCount(any()) } returns EVENT_COUNT
        }
    private val timing = mockk<FrameworkInputTimingStore> { every { summary(any()) } returns null }
    private val bluetoothLink = mockk<BluetoothPadLinkReader> { every { linkType(any()) } returns BluetoothLinkType.CLASSIC }
    private val stickHistory = mockk<StickTestHistoryStore> { every { state } returns history }
    private val sources =
        PadSources(
            registry,
            native,
            timing,
            bluetoothLink,
            stickHistory,
            PadAudioFactsStore(),
            Json { ignoreUnknownKeys = true },
        )

    private fun direct(id: Int) = PhysicalGamepadRegistry.Device(id = id, name = "DualSense", isUsbSynthetic = true)

    private fun framework(
        id: Int,
        transport: Transport,
    ) = PhysicalGamepadRegistry.Device(id = id, name = "Pad $id", transport = transport)

    private fun TestScope.collectWorlds(): List<PadWorld> {
        val out = mutableListOf<PadWorld>()
        backgroundScope.launch { sources.flow(flowOf(Unit)).collect { out.add(it) } }
        dispatcher.scheduler.runCurrent()
        return out
    }

    @Test
    fun `a direct pad reads the native counters and no framework timing`() =
        runTest(dispatcher) {
            devices.value = mapOf(-5 to direct(-5))
            val world = collectWorlds().last()
            assertEquals(URB_ERRORS, world.urbErrors[-5])
            assertEquals(URB_COUNT, world.reportCounts[-5])
            assertTrue(world.frameworkTiming.isEmpty())
            assertTrue(world.btLinkTypes.isEmpty())
            verify(exactly = 0) { native.getDeviceInputEventCount(any()) }
        }

    @Test
    fun `a framework pad reads its timing summary and event count`() =
        runTest(dispatcher) {
            val summary = FrameworkTimingSummary(samples = 3, gapP50Ms = 8f, gapP99Ms = 9f, delayP50Ms = 1f, delayP99Ms = 2f)
            every { timing.summary(4) } returns summary
            devices.value = mapOf(4 to framework(4, Transport.Usb))
            val world = collectWorlds().last()
            assertEquals(summary, world.frameworkTiming[4])
            assertEquals(EVENT_COUNT, world.reportCounts[4])
            assertTrue(world.urbErrors.isEmpty())
            verify(exactly = 0) { native.getDeviceUrbCount(any()) }
        }

    @Test
    fun `a USB framework pad has no link type`() =
        runTest(dispatcher) {
            devices.value = mapOf(4 to framework(4, Transport.Usb))
            val world = collectWorlds().last()
            assertNull(world.btLinkTypes[4])
            verify(exactly = 0) { bluetoothLink.linkType(any()) }
        }

    @Test
    fun `a Bluetooth pad's link type is read once and forgotten when it leaves`() =
        runTest(dispatcher) {
            devices.value = mapOf(4 to framework(4, Transport.Bluetooth))
            val worlds = collectWorlds()
            assertEquals(BluetoothLinkType.CLASSIC, worlds.last().btLinkTypes[4])

            devices.value = mapOf(4 to framework(4, Transport.Bluetooth), 5 to framework(5, Transport.Usb))
            dispatcher.scheduler.runCurrent()
            verify(exactly = 1) { bluetoothLink.linkType("Pad 4") }

            devices.value = emptyMap()
            dispatcher.scheduler.runCurrent()
            devices.value = mapOf(4 to framework(4, Transport.Bluetooth))
            dispatcher.scheduler.runCurrent()
            verify(exactly = 2) { bluetoothLink.linkType("Pad 4") }
        }

    @Test
    fun `the stick history rides along unchanged`() =
        runTest(dispatcher) {
            val record = StickTestRecord(driftAtMs = 9L)
            history.value = mapOf("k" to record)
            assertEquals(mapOf("k" to record), collectWorlds().last().stickHistory)
        }

    private companion object {
        const val URB_ERRORS = 3L
        const val URB_COUNT = 100L
        const val EVENT_COUNT = 7L
    }
}
