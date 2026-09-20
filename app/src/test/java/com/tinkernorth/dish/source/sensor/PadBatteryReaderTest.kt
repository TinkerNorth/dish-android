// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.hardware.BatteryState
import android.os.BatteryManager
import android.view.InputDevice
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadBatteryReaderTest {
    private val native = mockk<PhysicalInputNative>(relaxed = true)
    private val bluetoothBattery = mockk<BluetoothBatteryReader>()
    private val pads = mutableMapOf<Int, InputDevice>()
    private val lookups = mutableListOf<Int>()
    private val batteryReads = mutableListOf<Int>()

    private fun reader(sdkInt: Int = 31) =
        PadBatteryReader(native, bluetoothBattery, sdkInt) { deviceId ->
            lookups += deviceId
            pads[deviceId]
        }

    private fun pad(
        deviceId: Int,
        present: Boolean = true,
        level: Float = 0.5f,
    ): InputDevice {
        val state =
            mockk<BatteryState> {
                every { isPresent } returns present
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

    private fun framework(deviceId: Int) =
        PhysicalGamepadRegistry.Device(id = deviceId, name = "Pad $deviceId", transport = Transport.Bluetooth)

    private fun direct(deviceId: Int) =
        PhysicalGamepadRegistry.Device(id = deviceId, name = "Pad $deviceId", isUsbSynthetic = true, transport = Transport.Usb)

    @Test
    fun `a framework pad is looked up once and the device reused`() {
        pads[7] = pad(7)
        val r = reader()
        assertEquals(50, r.sample(framework(7))?.level)
        assertEquals(50, r.sample(framework(7))?.level)
        assertEquals(listOf(7), lookups)
        assertEquals(listOf(7, 7), batteryReads)
    }

    @Test
    fun `retain drops pads that left and the next sample looks them up again`() {
        pads[7] = pad(7)
        val r = reader()
        r.sample(framework(7))
        r.retain(setOf(8))
        r.sample(framework(7))
        assertEquals(listOf(7, 7), lookups)
    }

    @Test
    fun `retain keeps pads that are still present`() {
        pads[7] = pad(7)
        val r = reader()
        r.sample(framework(7))
        r.retain(setOf(7, 8))
        r.sample(framework(7))
        assertEquals(listOf(7), lookups)
    }

    @Test
    fun `a failed lookup is not cached`() {
        val r = reader()
        assertNull(r.sample(framework(7)))
        pads[7] = pad(7)
        assertEquals(50, r.sample(framework(7))?.level)
        assertEquals(listOf(7, 7), lookups)
    }

    @Test
    fun `an absent battery yields no sample`() {
        pads[7] = pad(7, present = false)
        assertNull(reader().sample(framework(7)))
    }

    @Test
    fun `below API 31 the battery state API is never touched and the Bluetooth reader answers`() {
        pads[7] = pad(7)
        every { bluetoothBattery.readLevel("Pad 7") } returns 42
        val sample = reader(sdkInt = 30).sample(framework(7))
        assertEquals(BatteryValidator.BatterySample(42, BatteryValidator.STATUS_UNKNOWN), sample)
        assertTrue(batteryReads.isEmpty())
        assertEquals(listOf(7), lookups)
    }

    @Test
    fun `below API 31 a pad the Bluetooth reader cannot see yields no sample`() {
        pads[7] = pad(7)
        every { bluetoothBattery.readLevel("Pad 7") } returns null
        assertNull(reader(sdkInt = 30).sample(framework(7)))
        assertTrue(batteryReads.isEmpty())
    }

    @Test
    fun `a Direct pad reads its native report and never the framework`() {
        every { native.getDirectPadBattery(-1000) } returns ((60 shl 8) or BatteryValidator.STATUS_DISCHARGING)
        val sample = reader().sample(direct(-1000))
        assertEquals(60, sample?.level)
        assertTrue(lookups.isEmpty())
        verify(exactly = 1) { native.getDirectPadBattery(-1000) }
    }
}
