// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.store.UsbPathPreferenceStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

// Drives the coordinator through the public path-choice entry point to verify the part the pure FSM
// can't: how a real open/claim/attach outcome is classified into a DirectClaimFailure. The Android USB
// layer is mocked; only one gamepad-shaped interface with an interrupt-IN endpoint is exposed.
@OptIn(ExperimentalCoroutinesApi::class)
class UsbGamepadManagerTest {
    private val vid = 0x045E
    private val pid = 0x028E
    private val key = (vid shl 16) or (pid and 0xFFFF)

    private val usbManager = mockk<UsbManager>(relaxed = true)
    private val registry = mockk<PhysicalGamepadRegistry>(relaxed = true)
    private val native = mockk<PhysicalInputNative>(relaxed = true)
    private val notifications = mockk<DishNotifications>(relaxed = true)
    private val hub = mockk<ConnectionHub>(relaxed = true)
    private val pathPrefs = mockk<UsbPathPreferenceStore>(relaxed = true)
    private val device = gamepadDevice()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun gamepadDevice(): UsbDevice {
        val epIn =
            mockk<UsbEndpoint> {
                every { type } returns UsbConstants.USB_ENDPOINT_XFER_INT
                every { direction } returns UsbConstants.USB_DIR_IN
                every { address } returns 0x81
                every { maxPacketSize } returns 64
                every { interval } returns 1
            }
        val intf =
            mockk<UsbInterface> {
                every { interfaceClass } returns UsbConstants.USB_CLASS_HID
                every { id } returns 0
                every { endpointCount } returns 1
                every { getEndpoint(0) } returns epIn
            }
        return mockk<UsbDevice> {
            every { vendorId } returns vid
            every { productId } returns pid
            every { deviceName } returns "usb-pad"
            every { interfaceCount } returns 1
            every { getInterface(0) } returns intf
        }
    }

    // Relaxed mocks return false / null, so isKnownFastLaneModel and directFailureFor default to the
    // "unknown, no prior failure" case; tests that need otherwise set those themselves.
    private fun buildManager(): UsbGamepadManager {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { usbManager.deviceList } returns hashMapOf("d" to device)
        every { usbManager.hasPermission(device) } returns true
        every { registry.devices } returns MutableStateFlow(emptyMap())
        every { native.lookupKnownModelName(vid, pid) } returns "Pad"
        // Relaxed mocks hand back the first enum constant for an enum return even when it is nullable, so
        // an unstubbed choiceFor would read as Direct and short-circuit resolvePath. Pin it to "no pick".
        every { pathPrefs.choiceFor(vid, pid) } returns null
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return UsbGamepadManager(ctx, registry, Provider { hub }, notifications, scope, native, pathPrefs)
    }

    private fun mockConn(): UsbDeviceConnection =
        mockk(relaxed = true) {
            every { fileDescriptor } returns 7
        }

    @Test
    fun `open rejected reports Busy and drops back to Standard`() {
        every { usbManager.openDevice(device) } returns null
        val m = buildManager()
        m.tryDirectMode(vid, pid)
        assertEquals(UsbPhase.Routed, m.controllers.value[key]?.phase)
        assertEquals(DirectClaimFailure.Busy, m.controllers.value[key]?.failure)
        verify { registry.markDirectFailed(vid, pid, DirectClaimFailure.Busy) }
    }

    @Test
    fun `security exception reports PermissionDenied`() {
        every { usbManager.openDevice(device) } throws SecurityException("revoked")
        val m = buildManager()
        m.tryDirectMode(vid, pid)
        assertEquals(UsbPhase.Routed, m.controllers.value[key]?.phase)
        assertEquals(DirectClaimFailure.PermissionDenied, m.controllers.value[key]?.failure)
        verify { registry.markDirectFailed(vid, pid, DirectClaimFailure.PermissionDenied) }
    }

    @Test
    fun `claim interface rejected reports Busy`() {
        val conn = mockConn()
        every { usbManager.openDevice(device) } returns conn
        every { conn.claimInterface(any(), true) } returns false
        val m = buildManager()
        m.tryDirectMode(vid, pid)
        assertEquals(DirectClaimFailure.Busy, m.controllers.value[key]?.failure)
        verify { conn.close() }
    }

    @Test
    fun `native attach failure after claim waits for the framework as InitFailed`() {
        val conn = mockConn()
        every { usbManager.openDevice(device) } returns conn
        every { conn.claimInterface(any(), true) } returns true
        every { native.attachUsbDevice(any(), any(), any(), any(), any(), any(), any()) } returns 0
        val m = buildManager()
        m.tryDirectMode(vid, pid)
        // The interface was stolen, so we wait for re-enumeration rather than declaring Standard usable.
        assertEquals(UsbPhase.AwaitingFramework, m.controllers.value[key]?.phase)
        assertEquals(DirectClaimFailure.InitFailed, m.controllers.value[key]?.failure)
    }

    @Test
    fun `successful claim reaches Direct and registers a synthetic`() {
        val conn = mockConn()
        every { usbManager.openDevice(device) } returns conn
        every { conn.claimInterface(any(), true) } returns true
        every { native.attachUsbDevice(any(), any(), any(), any(), any(), any(), any()) } returns -1000
        val m = buildManager()
        m.tryDirectMode(vid, pid)
        assertEquals(UsbPhase.Direct, m.controllers.value[key]?.phase)
        assertEquals(-1000, m.controllers.value[key]?.syntheticId)
        assertNull(m.controllers.value[key]?.failure)
        verify { registry.addUsbSynthetic(-1000, "Pad", any(), any(), vid, pid) }
    }

    // A real registry so directFailureFor genuinely reflects what markDirectFailed recorded, instead of
    // relying on stubbing the read back (the guard is integration, not a single mocked return).
    private fun realRegistry(): PhysicalGamepadRegistry {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Context.INPUT_SERVICE) } returns mockk<InputManager>(relaxed = true)
        every { ctx.getSystemService(Context.USB_SERVICE) } returns mockk<UsbManager>(relaxed = true)
        return PhysicalGamepadRegistry(ctx, CoroutineScope(SupervisorJob()), native)
    }

    private fun buildManagerWith(reg: PhysicalGamepadRegistry): UsbGamepadManager {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { usbManager.deviceList } returns hashMapOf("d" to device)
        every { usbManager.hasPermission(device) } returns true
        every { native.lookupKnownModelName(vid, pid) } returns "Pad"
        every { pathPrefs.choiceFor(vid, pid) } returns null
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return UsbGamepadManager(ctx, reg, Provider { hub }, notifications, scope, native, pathPrefs)
    }

    @Test
    fun `a recorded failure suppresses auto-Direct on a verified model`() {
        // Verified model, but a prior failure is on record: the auto path must settle Standard.
        every { native.isKnownFastLaneModel(vid, pid) } returns true
        val reg = realRegistry()
        reg.markDirectFailed(vid, pid, DirectClaimFailure.Busy)
        assertEquals(DirectClaimFailure.Busy, reg.directFailureFor(vid, pid))
        val m = buildManagerWith(reg)
        m.reconcileForeground()
        assertEquals(PathChoice.Standard, m.controllers.value[key]?.desired)
        verify(exactly = 0) { usbManager.openDevice(any()) }
    }

    @Test
    fun `a verified model with no recorded failure auto-claims Direct`() {
        every { native.isKnownFastLaneModel(vid, pid) } returns true
        every { usbManager.openDevice(device) } returns null
        val m = buildManagerWith(realRegistry())
        m.reconcileForeground()
        // The auto path attempted the claim (open was reached) instead of settling Standard.
        verify { usbManager.openDevice(device) }
    }
}
