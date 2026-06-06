// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.view.InputDevice
import com.tinkernorth.dish.source.usb.DirectClaimFailure
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalGamepadRegistryTest {
    @Test
    fun `device with SOURCE_GAMEPAD bit is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `device with SOURCE_JOYSTICK bit is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_JOYSTICK,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `device with the exact mask seen in the Generic USB Joystick bug is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = 0x1000111,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `device with both SOURCE_GAMEPAD and SOURCE_JOYSTICK is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `pure keyboard with no gamepad-source bits is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_KEYBOARD,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `touchscreen device is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_TOUCHSCREEN,
                keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
            ),
        )
    }

    @Test
    fun `mouse device is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_MOUSE,
                keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
            ),
        )
    }

    @Test
    fun `dpad-only device without GAMEPAD or JOYSTICK is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_DPAD,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `zero source mask is rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = 0,
                keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
            ),
        )
    }

    @Test
    fun `alphabetic keyboard with SOURCE_JOYSTICK is still rejected`() {
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_JOYSTICK,
                keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            ),
        )
    }

    @Test
    fun `KEYBOARD_TYPE_NONE with SOURCE_JOYSTICK is accepted`() {
        assertTrue(
            isGamepadDeviceFromCapabilities(
                sources = InputDevice.SOURCE_JOYSTICK,
                keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
            ),
        )
    }

    @Test
    fun `partial source bits without a full GAMEPAD class are rejected`() {
        val incomplete = InputDevice.SOURCE_GAMEPAD and 0x400.inv()
        assertFalse(
            isGamepadDeviceFromCapabilities(
                sources = incomplete,
                keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
            ),
        )
    }

    private fun buildRegistry(): PhysicalGamepadRegistry {
        val ctx = mockk<Context>()
        every { ctx.getSystemService(Context.INPUT_SERVICE) } returns mockk<InputManager>(relaxed = true)
        every { ctx.getSystemService(Context.USB_SERVICE) } returns mockk<UsbManager>(relaxed = true)
        return PhysicalGamepadRegistry(ctx, CoroutineScope(SupervisorJob()), mockk(relaxed = true))
    }

    @Test
    fun `addUsbSynthetic publishes a Direct-mode device with the supplied fields`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(
            deviceId = -1000,
            name = "DualSense",
            hasGyro = true,
            pollRateHz = 250,
            vendorId = 0x054C,
            productId = 0x0CE6,
        )
        val d = registry.devices.value.getValue(-1000)
        assertEquals("DualSense", d.name)
        assertTrue(d.isUsbSynthetic)
        assertTrue(d.hasGyro)
        assertEquals(250, d.pollRateHz)
        assertEquals(0x054C, d.vendorId)
        assertEquals(0x0CE6, d.productId)
    }

    @Test
    fun `removeUsbSynthetic drops the entry`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(-1000, "Pad", false, 0, 1, 2)
        registry.removeUsbSynthetic(-1000)
        assertNull(registry.devices.value[-1000])
    }

    @Test
    fun `forgetSupersededFramework drops the device immediately`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(-1000, "Pad", false, 0, 1, 2)
        registry.forgetSupersededFramework(-1000)
        assertNull(registry.devices.value[-1000])
    }

    @Test
    fun `updateMeasuredPollRate sets the rate on a present device`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(-1000, "Pad", false, 0, 1, 2)
        registry.updateMeasuredPollRate(-1000, 500)
        assertEquals(500, registry.devices.value[-1000]?.pollRateHz)
    }

    @Test
    fun `updateMeasuredPollRate never creates a device that is not present`() {
        val registry = buildRegistry()
        registry.updateMeasuredPollRate(-1000, 500)
        assertNull(registry.devices.value[-1000])
    }

    @Test
    fun `updateMeasuredPollRate after removal does not resurrect the device`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(-1000, "Pad", false, 0, 1, 2)
        registry.removeUsbSynthetic(-1000)
        registry.updateMeasuredPollRate(-1000, 500)
        assertNull(registry.devices.value[-1000])
    }

    @Test
    fun `updateMeasuredPollRate with the same rate is a no-op that does not re-emit`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(-1000, "Pad", false, 250, 1, 2)
        val before = registry.devices.value
        registry.updateMeasuredPollRate(-1000, 250)
        assertSame(before, registry.devices.value)
    }

    @Test
    fun `updating one synthetic preserves the others`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(-1000, "A", false, 0, 1, 2)
        registry.addUsbSynthetic(-1001, "B", false, 0, 3, 4)
        registry.updateMeasuredPollRate(-1000, 500)
        assertEquals(2, registry.devices.value.size)
        assertEquals(500, registry.devices.value[-1000]?.pollRateHz)
        assertEquals(0, registry.devices.value[-1001]?.pollRateHz)
    }

    @Test
    fun `markDirectFailed records the cause for the model but not on a synthetic of it`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(-1000, "Pad", false, 0, vendorId = 1, productId = 2)
        registry.markDirectFailed(1, 2, DirectClaimFailure.Busy)
        assertEquals(DirectClaimFailure.Busy, registry.directFailureFor(1, 2))
        assertNull(registry.devices.value[-1000]?.directFailure)
    }

    @Test
    fun `clearDirectFailed forgets the recorded cause`() {
        val registry = buildRegistry()
        registry.markDirectFailed(1, 2, DirectClaimFailure.InitFailed)
        registry.clearDirectFailed(1, 2)
        assertNull(registry.directFailureFor(1, 2))
    }

    @Test
    fun `markRestoreStuck flips the held synthetic to an actionable card`() {
        val registry = buildRegistry()
        registry.addUsbSynthetic(-1000, "Pad", false, 0, vendorId = 1, productId = 2)
        registry.markRestoreStuck(1, 2)
        val d = registry.devices.value.getValue(-1000)
        assertTrue(d.restoreStuck)
        assertFalse(d.transitioning)
    }

    @Test
    fun `isSyntheticId treats negative ids as synthetic and non-negative as framework`() {
        assertTrue(PhysicalGamepadRegistry.isSyntheticId(-1000))
        assertTrue(PhysicalGamepadRegistry.isSyntheticId(-1))
        assertFalse(PhysicalGamepadRegistry.isSyntheticId(0))
        assertFalse(PhysicalGamepadRegistry.isSyntheticId(42))
    }
}
