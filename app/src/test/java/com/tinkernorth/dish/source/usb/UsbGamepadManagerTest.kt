// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.core.input.vidPidKey
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.store.UsbPathPreferenceStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private val key = vidPidKey(vid, pid)

    private val usbManager = mockk<UsbManager>(relaxed = true)
    private val registry = mockk<PhysicalGamepadRegistry>(relaxed = true)
    private val native = mockk<PhysicalInputNative>(relaxed = true)
    private val notifications = mockk<DishNotifications>(relaxed = true)
    private val hub = mockk<ConnectionCoordinator>(relaxed = true)
    private val pathPrefs = mockk<UsbPathPreferenceStore>(relaxed = true)
    private val descriptors = UsbDescriptorStore()
    private val ctx = mockk<Context>(relaxed = true)
    private val device = gamepadDevice()

    // The registry's framework view, moved by the tests the way InputManager callbacks would.
    private val registryDevices = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())

    // One virtual clock for the manager's scope and for Dispatchers.Main, so the 4 s transition
    // timeout and the main-thread hops the manager makes both run under the test's control.
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)

    @Before
    fun setUp() {
        // The coordinator's flows are read on every track and release; tests that bind stub them again.
        every { hub.bindings } returns MutableStateFlow(emptyMap())
        every { hub.satTypes } returns MutableStateFlow(emptyMap())
        mockkStatic(Log::class)
        mockkStatic(ContextCompat::class, IntentCompat::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // A null device node leaves deviceName unstubbed, so the strict mock throws if anything reads it.
    private fun gamepadDevice(deviceNode: String? = "usb-pad"): UsbDevice {
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
                every { interfaceSubclass } returns 0
                every { interfaceProtocol } returns 0
                every { id } returns 0
                every { endpointCount } returns 1
                every { getEndpoint(0) } returns epIn
            }
        return mockk<UsbDevice> {
            every { vendorId } returns vid
            every { productId } returns pid
            deviceNode?.let { node -> every { deviceName } returns node }
            every { interfaceCount } returns 1
            every { getInterface(0) } returns intf
        }
    }

    // Relaxed mocks return false / null, so isKnownFastLaneModel and directFailureFor default to the
    // "unknown, no prior failure" case; tests that need otherwise set those themselves.
    private fun buildManager(): UsbGamepadManager {
        every { ctx.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { usbManager.deviceList } returns hashMapOf("d" to device)
        every { usbManager.hasPermission(device) } returns true
        every { registry.devices } returns registryDevices
        every { native.lookupKnownModelName(vid, pid) } returns "Pad"
        // The relaxed default (false) is the keyboard-settling exception; almost every model
        // re-enumerates as a framework gamepad, so pin the common case.
        every { native.modelExpectsFrameworkGamepad(vid, pid) } returns true
        // Relaxed mocks hand back the first enum constant for an enum return even when it is nullable, so
        // an unstubbed choiceFor would read as Direct and short-circuit resolvePath. Pin it to "no pick".
        every { pathPrefs.choiceFor(vid, pid) } returns null
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return UsbGamepadManager(ctx, registry, Provider { hub }, notifications, scope, native, pathPrefs, descriptors)
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
        every {
            native.attachUsbDevice(any(), any(), any(), any())
        } returns 0
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
        every {
            native.attachUsbDevice(any(), any(), any(), any())
        } returns -1000
        val m = buildManager()
        m.tryDirectMode(vid, pid)
        assertEquals(UsbPhase.Direct, m.controllers.value[key]?.phase)
        assertEquals(-1000, m.controllers.value[key]?.syntheticId)
        assertNull(m.controllers.value[key]?.failure)
        verify { registry.addUsbSynthetic(-1000, "Pad", any(), any(), vid, pid) }
    }

    @Test
    fun `a claim carries its framework twin's descriptor`() {
        val conn = mockConn()
        every { usbManager.openDevice(device) } returns conn
        every { conn.claimInterface(any(), true) } returns true
        every { native.attachUsbDevice(any(), any(), any(), any()) } returns -1000
        registryDevices.value = mapOf(7 to frameworkPad(7).copy(descriptor = "usb:054c:0ce6:1"))
        val m = buildManager()

        m.tryDirectMode(vid, pid)

        verify { registry.addUsbSynthetic(-1000, "Pad", any(), any(), vid, pid, descriptor = "usb:054c:0ce6:1") }
    }

    private fun claimTo(syntheticId: Int): UsbGamepadManager {
        val conn = mockConn()
        every { usbManager.openDevice(device) } returns conn
        every { conn.claimInterface(any(), true) } returns true
        every {
            native.attachUsbDevice(any(), any(), any(), any())
        } returns syntheticId
        return buildManager()
    }

    @Test
    fun `releasing a claimed pad that re-enumerates waits for the framework`() {
        val m = claimTo(-1000)
        m.tryDirectMode(vid, pid)
        m.setPathChoice(vid, pid, PathChoice.Standard)
        assertEquals(UsbPhase.AwaitingFramework, m.controllers.value[key]?.phase)
        verify { native.detachUsbDevice(-1000) }
    }

    // The Steam Controller settles as keyboard/mouse, so no framework gamepad ever re-enumerates
    // after a release; waiting for one would strand every release in RestoreStuck with a false
    // "restore failed" banner.
    @Test
    fun `releasing a keyboard-settling pad settles standard immediately`() {
        val m = claimTo(-1000)
        every { native.modelExpectsFrameworkGamepad(vid, pid) } returns false
        m.tryDirectMode(vid, pid)
        assertEquals(UsbPhase.Direct, m.controllers.value[key]?.phase)
        m.setPathChoice(vid, pid, PathChoice.Standard)
        assertEquals(UsbPhase.Routed, m.controllers.value[key]?.phase)
        assertNull(m.controllers.value[key]?.syntheticId)
        // Release detaches once and the RemoveSynthetic cleanup detaches again (idempotent
        // natively); the second call is what the physical-unplug path relies on, since unplug
        // emits RemoveSynthetic without a preceding Release.
        verify(exactly = 2) { native.detachUsbDevice(-1000) }
        verify { registry.removeUsbSynthetic(-1000) }
    }

    @Test
    fun `stop-all releases every held direct claim with its device restore`() {
        val m = claimTo(-1000)
        every { native.modelExpectsFrameworkGamepad(vid, pid) } returns false
        m.tryDirectMode(vid, pid)
        m.releaseAllDirect()
        assertEquals(UsbPhase.Routed, m.controllers.value[key]?.phase)
        verify { native.detachUsbDevice(-1000) }
        verify { pathPrefs.setChoice(vid, pid, PathChoice.Standard) }
    }

    private fun vendorInterface(
        ifaceId: Int,
        subclass: Int,
        protocol: Int,
        epInAddress: Int,
    ): UsbInterface {
        val epIn =
            mockk<UsbEndpoint> {
                every { type } returns UsbConstants.USB_ENDPOINT_XFER_INT
                every { direction } returns UsbConstants.USB_DIR_IN
                every { address } returns epInAddress
                every { maxPacketSize } returns 32
                every { interval } returns 4
            }
        return mockk {
            every { interfaceClass } returns UsbConstants.USB_CLASS_VENDOR_SPEC
            every { interfaceSubclass } returns subclass
            every { interfaceProtocol } returns protocol
            every { id } returns ifaceId
            every { endpointCount } returns 1
            every { getEndpoint(0) } returns epIn
        }
    }

    // Audio interface first (id 0) so a first-match bug would claim it over the gamepad (id 1).
    private fun compositeXbox360(): UsbDevice {
        val audio = vendorInterface(ifaceId = 0, subclass = 0x5D, protocol = 0x03, epInAddress = 0x81)
        val game = vendorInterface(ifaceId = 1, subclass = 0x5D, protocol = 0x01, epInAddress = 0x82)
        return mockk {
            every { vendorId } returns vid
            every { productId } returns pid
            every { deviceName } returns "xbox360"
            every { interfaceCount } returns 2
            every { getInterface(0) } returns audio
            every { getInterface(1) } returns game
        }
    }

    private fun buildManagerForDevice(dev: UsbDevice): UsbGamepadManager {
        every { ctx.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { usbManager.deviceList } returns hashMapOf("d" to dev)
        every { usbManager.hasPermission(dev) } returns true
        every { registry.devices } returns registryDevices
        every { native.lookupKnownModelName(vid, pid) } returns "Pad"
        every { native.modelExpectsFrameworkGamepad(vid, pid) } returns true
        every { pathPrefs.choiceFor(vid, pid) } returns null
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return UsbGamepadManager(ctx, registry, Provider { hub }, notifications, scope, native, pathPrefs, descriptors)
    }

    @Test
    fun `a known model is named by the table without reading its device node`() {
        val m = buildManagerForDevice(gamepadDevice(deviceNode = null))

        m.reconcileForeground()

        assertEquals("Pad", m.controllers.value[key]?.name)
    }

    @Test
    fun `claims the gamepad interface of a composite controller, not the audio one`() {
        val dev = compositeXbox360()
        val conn = mockConn()
        every { usbManager.openDevice(dev) } returns conn
        every { conn.claimInterface(any(), true) } returns true
        every {
            native.attachUsbDevice(any(), any(), any(), any())
        } returns -1000
        val m = buildManagerForDevice(dev)
        m.tryDirectMode(vid, pid)
        verify {
            native.attachUsbDevice(
                fd = any(),
                vendorId = vid,
                productId = pid,
                claim =
                    match {
                        it.interfaceNumber == 1 &&
                            it.endpointIn == 0x82 &&
                            it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                            it.interfaceSubclass == 0x5D &&
                            it.interfaceProtocol == 0x01
                    },
            )
        }
    }

    private fun hidInterface(
        ifaceId: Int,
        subclass: Int,
        protocol: Int,
        epInAddress: Int,
    ): UsbInterface {
        val epIn =
            mockk<UsbEndpoint> {
                every { type } returns UsbConstants.USB_ENDPOINT_XFER_INT
                every { direction } returns UsbConstants.USB_DIR_IN
                every { address } returns epInAddress
                every { maxPacketSize } returns 64
                every { interval } returns 4
            }
        return mockk {
            every { interfaceClass } returns UsbConstants.USB_CLASS_HID
            every { interfaceSubclass } returns subclass
            every { interfaceProtocol } returns protocol
            every { id } returns ifaceId
            every { endpointCount } returns 1
            every { getEndpoint(0) } returns epIn
        }
    }

    // Boot keyboard and mouse first, the way a Steam Controller enumerates: ranking every HID
    // interface alike would claim the keyboard and stream decoded key bytes as gamepad state.
    private fun keyboardMousePadComposite(): UsbDevice {
        val keyboard = hidInterface(ifaceId = 0, subclass = 0x01, protocol = 0x01, epInAddress = 0x81)
        val mouse = hidInterface(ifaceId = 1, subclass = 0x01, protocol = 0x02, epInAddress = 0x82)
        val pad = hidInterface(ifaceId = 2, subclass = 0x00, protocol = 0x00, epInAddress = 0x83)
        return mockk {
            every { vendorId } returns vid
            every { productId } returns pid
            every { deviceName } returns "composite-hid"
            every { interfaceCount } returns 3
            every { getInterface(0) } returns keyboard
            every { getInterface(1) } returns mouse
            every { getInterface(2) } returns pad
        }
    }

    private fun bootKeyboardOnly(): UsbDevice {
        val keyboard = hidInterface(ifaceId = 0, subclass = 0x01, protocol = 0x01, epInAddress = 0x81)
        return mockk {
            every { vendorId } returns vid
            every { productId } returns pid
            every { deviceName } returns "boot-only"
            every { interfaceCount } returns 1
            every { getInterface(0) } returns keyboard
        }
    }

    private fun expectAttach(dev: UsbDevice): UsbGamepadManager {
        val conn = mockConn()
        every { usbManager.openDevice(dev) } returns conn
        every { conn.claimInterface(any(), true) } returns true
        every {
            native.attachUsbDevice(any(), any(), any(), any())
        } returns -1000
        return buildManagerForDevice(dev)
    }

    @Test
    fun `claims the controller interface of a composite pad, not its boot keyboard or mouse`() {
        val dev = keyboardMousePadComposite()
        expectAttach(dev).tryDirectMode(vid, pid)
        verify {
            native.attachUsbDevice(
                fd = any(),
                vendorId = vid,
                productId = pid,
                claim =
                    match {
                        it.interfaceNumber == 2 &&
                            it.endpointIn == 0x83 &&
                            it.interfaceClass == UsbConstants.USB_CLASS_HID &&
                            it.interfaceSubclass == 0x00 &&
                            it.interfaceProtocol == 0x00
                    },
            )
        }
    }

    // Deprioritised, not disqualified: a device with nothing else must still be claimable.
    @Test
    fun `falls back to a boot interface when the device offers no other`() {
        val dev = bootKeyboardOnly()
        expectAttach(dev).tryDirectMode(vid, pid)
        verify {
            native.attachUsbDevice(
                fd = any(),
                vendorId = vid,
                productId = pid,
                claim =
                    match {
                        it.interfaceNumber == 0 &&
                            it.endpointIn == 0x81 &&
                            it.interfaceClass == UsbConstants.USB_CLASS_HID &&
                            it.interfaceSubclass == 0x01 &&
                            it.interfaceProtocol == 0x01
                    },
            )
        }
    }

    // A real registry so directFailureFor genuinely reflects what markDirectFailed recorded, instead of
    // relying on stubbing the read back (the guard is integration, not a single mocked return).
    private fun realRegistry(): PhysicalGamepadRegistry {
        val registryCtx = mockk<Context>(relaxed = true)
        every { registryCtx.getSystemService(Context.INPUT_SERVICE) } returns mockk<InputManager>(relaxed = true)
        every { registryCtx.getSystemService(Context.USB_SERVICE) } returns mockk<UsbManager>(relaxed = true)
        return PhysicalGamepadRegistry(registryCtx, CoroutineScope(SupervisorJob()), native, mockk(relaxed = true))
    }

    private fun buildManagerWith(reg: PhysicalGamepadRegistry): UsbGamepadManager {
        every { ctx.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { usbManager.deviceList } returns hashMapOf("d" to device)
        every { usbManager.hasPermission(device) } returns true
        every { native.lookupKnownModelName(vid, pid) } returns "Pad"
        every { native.modelExpectsFrameworkGamepad(vid, pid) } returns true
        every { pathPrefs.choiceFor(vid, pid) } returns null
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return UsbGamepadManager(ctx, reg, Provider { hub }, notifications, scope, native, pathPrefs, descriptors)
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

    private fun frameworkPad(id: Int) = PhysicalGamepadRegistry.Device(id = id, name = "Pad", vendorId = vid, productId = pid)

    private fun advancePastTransitionTimeout() {
        scheduler.advanceTimeBy(4100)
        scheduler.runCurrent()
    }

    @Test
    fun `a framework pad re-enumerating sends FrameworkUp to its controller`() {
        val m = buildManager()
        m.install()
        assertNull(m.controllers.value[key]?.frameworkId)

        registryDevices.value = mapOf(7 to frameworkPad(7))

        assertEquals(7, m.controllers.value[key]?.frameworkId)
        assertEquals(UsbPhase.Routed, m.controllers.value[key]?.phase)
    }

    @Test
    fun `a framework pad vanishing sends FrameworkDown to its controller`() {
        val m = buildManager()
        m.install()
        registryDevices.value = mapOf(7 to frameworkPad(7))

        registryDevices.value = emptyMap()

        assertEquals(UsbPhase.AwaitingFramework, m.controllers.value[key]?.phase)
        assertNull(m.controllers.value[key]?.frameworkId)
    }

    @Test
    fun `a framework pad that never returns settles NeedsReplug after the timeout`() {
        val m = buildManager()
        m.install()
        registryDevices.value = mapOf(7 to frameworkPad(7))
        registryDevices.value = emptyMap()

        advancePastTransitionTimeout()

        assertEquals(UsbPhase.NeedsReplug, m.controllers.value[key]?.phase)
        assertEquals(DirectClaimFailure.Dropped, m.controllers.value[key]?.failure)
        verify { registry.markNeedsReplug(vid, pid) }
    }

    @Test
    fun `a framework pad that returns in time cancels the wait`() {
        val m = buildManager()
        m.install()
        registryDevices.value = mapOf(7 to frameworkPad(7))
        registryDevices.value = emptyMap()

        registryDevices.value = mapOf(8 to frameworkPad(8))
        advancePastTransitionTimeout()

        assertEquals(UsbPhase.Routed, m.controllers.value[key]?.phase)
        assertEquals(8, m.controllers.value[key]?.frameworkId)
        verify(exactly = 0) { registry.markNeedsReplug(any(), any()) }
    }

    @Test
    fun `a framework pad enumerating before the USB broadcast starts tracking`() {
        val m = buildManager()
        every { usbManager.deviceList } returns hashMapOf()
        m.install()
        assertTrue(m.controllers.value.isEmpty())

        every { usbManager.deviceList } returns hashMapOf("d" to device)
        registryDevices.value = mapOf(7 to frameworkPad(7))

        assertEquals(7, m.controllers.value[key]?.frameworkId)
        assertEquals(UsbPhase.Routed, m.controllers.value[key]?.phase)
    }

    @Test
    fun `a release that never re-enumerates settles RestoreStuck after the timeout`() {
        val m = claimTo(-1000)
        m.tryDirectMode(vid, pid)
        m.setPathChoice(vid, pid, PathChoice.Standard)

        advancePastTransitionTimeout()

        assertEquals(UsbPhase.RestoreStuck, m.controllers.value[key]?.phase)
        assertEquals(-1000, m.controllers.value[key]?.syntheticId)
        verify { registry.markRestoreStuck(vid, pid) }
    }

    @Test
    fun `a framework that returns after a release cancels the stuck detection`() {
        val m = claimTo(-1000)
        m.install()
        m.tryDirectMode(vid, pid)
        m.setPathChoice(vid, pid, PathChoice.Standard)

        registryDevices.value = mapOf(9 to frameworkPad(9))
        advancePastTransitionTimeout()

        assertEquals(UsbPhase.Routed, m.controllers.value[key]?.phase)
        verify(exactly = 0) { registry.markRestoreStuck(any(), any()) }
        verify { registry.removeUsbSynthetic(-1000) }
    }

    private fun restoreStuck(): UsbGamepadManager {
        val m = claimTo(-1000)
        m.tryDirectMode(vid, pid)
        m.setPathChoice(vid, pid, PathChoice.Standard)
        advancePastTransitionTimeout()
        assertEquals(UsbPhase.RestoreStuck, m.controllers.value[key]?.phase)
        return m
    }

    @Test
    fun `reclaim from RestoreStuck drops the placeholder and settles Direct on the new synthetic`() {
        val m = restoreStuck()
        every { native.attachUsbDevice(any(), any(), any(), any()) } returns -1001

        m.tryDirectMode(vid, pid)

        assertEquals(UsbPhase.Direct, m.controllers.value[key]?.phase)
        assertEquals(-1001, m.controllers.value[key]?.syntheticId)
        verify { registry.removeUsbSynthetic(-1000) }
        verify { pathPrefs.setChoice(vid, pid, PathChoice.Direct) }
    }

    @Test
    fun `reclaim from RestoreStuck re-binds the carried connection to the new synthetic`() {
        every { hub.bindings } returns MutableStateFlow(mapOf("-1000" to "sat-1"))
        every { hub.satTypes } returns MutableStateFlow(mapOf(("sat-1" to "-1000") to 3))
        val m = restoreStuck()
        every { native.attachUsbDevice(any(), any(), any(), any()) } returns -1001

        m.tryDirectMode(vid, pid)

        verify { hub.bind("-1001", "sat-1", 3) }
    }

    @Test
    fun `a reclaim that fails needs replug`() {
        val m = restoreStuck()
        every { usbManager.openDevice(device) } returns null

        m.tryDirectMode(vid, pid)

        assertEquals(UsbPhase.NeedsReplug, m.controllers.value[key]?.phase)
        assertEquals(DirectClaimFailure.Dropped, m.controllers.value[key]?.failure)
        assertNull(m.controllers.value[key]?.syntheticId)
        verify { registry.removeUsbSynthetic(-1000) }
    }

    @Test
    fun `releasing a claimed pad captures its binding and holds the synthetic as a placeholder`() {
        every { hub.bindings } returns MutableStateFlow(mapOf("-1000" to "sat-1"))
        every { hub.satTypes } returns MutableStateFlow(mapOf(("sat-1" to "-1000") to 3))
        val m = claimTo(-1000)
        m.tryDirectMode(vid, pid)

        m.setPathChoice(vid, pid, PathChoice.Standard)

        assertEquals("sat-1", m.controllers.value[key]?.connId)
        assertEquals(3, m.controllers.value[key]?.type)
        verify { registry.setUsbSyntheticTransitioning(-1000, true) }
    }

    @Test
    fun `releasing a claimed pad carries its binding to the framework that returns`() {
        every { hub.bindings } returns MutableStateFlow(mapOf("-1000" to "sat-1"))
        every { hub.satTypes } returns MutableStateFlow(mapOf(("sat-1" to "-1000") to 3))
        val m = claimTo(-1000)
        m.install()
        m.tryDirectMode(vid, pid)
        m.setPathChoice(vid, pid, PathChoice.Standard)

        registryDevices.value = mapOf(9 to frameworkPad(9))

        verify { hub.bind("9", "sat-1", 3) }
        verify { registry.removeUsbSynthetic(-1000) }
    }

    @Test
    fun `a restored binding with no remembered type re-registers as Xbox`() {
        every { hub.bindings } returns MutableStateFlow(mapOf("-1000" to "sat-1"))
        every { hub.satTypes } returns MutableStateFlow(emptyMap())
        val m = claimTo(-1000)
        m.install()
        m.tryDirectMode(vid, pid)
        m.setPathChoice(vid, pid, PathChoice.Standard)

        registryDevices.value = mapOf(9 to frameworkPad(9))

        verify { hub.bind("9", "sat-1", CONTROLLER_TYPE_XBOX) }
    }

    @Test
    fun `an unbound pad that returns to the framework binds nothing`() {
        val m = claimTo(-1000)
        m.install()
        m.tryDirectMode(vid, pid)
        m.setPathChoice(vid, pid, PathChoice.Standard)

        registryDevices.value = mapOf(9 to frameworkPad(9))

        verify(exactly = 0) { hub.bind(any(), any(), any()) }
    }

    @Test
    fun `a new controller captures its framework binding at track time`() {
        every { hub.bindings } returns MutableStateFlow(mapOf("7" to "sat-1"))
        every { hub.satTypes } returns MutableStateFlow(mapOf(("sat-1" to "7") to 2))
        registryDevices.value = mapOf(7 to frameworkPad(7))
        val m = buildManager()

        m.reconcileForeground()

        assertEquals(7, m.controllers.value[key]?.frameworkId)
        assertEquals("sat-1", m.controllers.value[key]?.connId)
        assertEquals(2, m.controllers.value[key]?.type)
    }

    @Test
    fun `a claim supersedes the routed framework card and re-keys its binding`() {
        registryDevices.value = mapOf(7 to frameworkPad(7))
        val m = claimTo(-1000)

        m.tryDirectMode(vid, pid)

        verify { hub.bindClaimedSynthetic("7", "-1000") }
    }

    @Test
    fun `attachClaimed forgets the stolen framework device`() {
        registryDevices.value = mapOf(7 to frameworkPad(7))
        val m = claimTo(-1000)

        m.tryDirectMode(vid, pid)

        verify { registry.forgetSupersededFramework(7) }
    }

    @Test
    fun `a claim with no framework twin re-keys nothing and forgets nothing`() {
        val m = claimTo(-1000)

        m.tryDirectMode(vid, pid)

        verify { hub.bindClaimedSynthetic(null, "-1000") }
        verify(exactly = 0) { registry.forgetSupersededFramework(any()) }
    }

    @Test
    fun `a re-scan after the permission grant feeds PermissionGranted`() {
        val m = buildManager()
        every { usbManager.hasPermission(device) } returns false
        m.reconcileForeground()
        assertEquals(false, m.controllers.value[key]?.hasPermission)

        every { usbManager.hasPermission(device) } returns true
        m.reconcileForeground()

        assertEquals(true, m.controllers.value[key]?.hasPermission)
    }

    @Test
    fun `a permission that arrives while wanting Direct starts the claim`() {
        every { native.isKnownFastLaneModel(vid, pid) } returns true
        every { usbManager.openDevice(device) } returns null
        val m = buildManagerWith(realRegistry())
        every { usbManager.hasPermission(device) } returns false
        m.reconcileForeground()
        assertEquals(PathChoice.Direct, m.controllers.value[key]?.desired)
        verify(exactly = 0) { usbManager.openDevice(any()) }

        every { usbManager.hasPermission(device) } returns true
        m.reconcileForeground()

        verify(exactly = 1) { usbManager.openDevice(device) }
    }

    private fun installedReceiver(m: UsbGamepadManager): BroadcastReceiver {
        val receiver = slot<BroadcastReceiver>()
        every {
            ContextCompat.registerReceiver(ctx, capture(receiver), any(), ContextCompat.RECEIVER_NOT_EXPORTED)
        } returns null
        m.install()
        return receiver.captured
    }

    // The permission grant rides this receiver: an exported one would let any app forge
    // ACTION_USB_PERMISSION or a detach for a pad it does not own.
    @Test
    fun `install registers its receiver not exported`() {
        val m = buildManager()

        m.install()

        verify(exactly = 1) {
            ContextCompat.registerReceiver(ctx, any(), any(), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun detachedIntent(): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns UsbManager.ACTION_USB_DEVICE_DETACHED
        every { IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) } returns device
        return intent
    }

    @Test
    fun `unplugging a claimed pad closes its connection and forgets the recorded failure`() {
        val conn = mockConn()
        every { usbManager.openDevice(device) } returns conn
        every { conn.claimInterface(any(), true) } returns true
        every { hub.bindings } returns MutableStateFlow(emptyMap())
        every { hub.satTypes } returns MutableStateFlow(emptyMap())
        every { native.attachUsbDevice(any(), any(), any(), any()) } returns -1000
        val m = buildManager()
        val receiver = installedReceiver(m)
        m.tryDirectMode(vid, pid)
        assertEquals(UsbPhase.Direct, m.controllers.value[key]?.phase)

        receiver.onReceive(ctx, detachedIntent())

        assertTrue(m.controllers.value.isEmpty())
        verify { native.detachUsbDevice(-1000) }
        verify { registry.removeUsbSynthetic(-1000) }
        verify { conn.close() }
        verify { registry.clearDirectFailed(vid, pid) }
    }

    @Test
    fun `a user switch to Direct that fails warns with the cause as body`() {
        every { usbManager.openDevice(device) } returns null
        every { ctx.getString(R.string.direct_failed, "Pad") } returns "Direct failed for Pad"
        every { ctx.getString(R.string.path_reason_busy) } returns "busy"
        val m = buildManager()

        m.tryDirectMode(vid, pid)

        verify {
            notifications.warn(
                title = "Direct failed for Pad",
                body = "busy",
                glyph = R.drawable.ic_gamepad,
                action = null,
                key = "direct-result:045e:028e",
                durationMs = any(),
            )
        }
    }

    @Test
    fun `a restore failed notice warns with no body`() {
        every { ctx.getString(R.string.direct_restore_failed, "Pad") } returns "Pad did not come back"
        restoreStuck()

        verify {
            notifications.warn(
                title = "Pad did not come back",
                body = null,
                glyph = R.drawable.ic_gamepad,
                action = null,
                key = "direct-result:045e:028e",
                durationMs = any(),
            )
        }
    }

    @Test
    fun `plugging in a pad records its endpoint facts`() {
        val m = buildManager()

        m.reconcileForeground()

        val facts = descriptors.factsFor(vid, pid)
        assertEquals(1, facts?.intervalRaw)
        assertEquals(64, facts?.maxPacketSize)
        assertEquals(computeUsbPollRateHz(1, 64), facts?.pollRateHz)
        assertEquals(false, facts?.highSpeed)
        assertEquals(UsbConstants.USB_CLASS_HID, facts?.interfaceClass)
        assertEquals(false, facts?.hasOutEndpoint)
    }

    private fun buildManagerWithoutUsbService(): UsbGamepadManager {
        every { ctx.getSystemService(Context.USB_SERVICE) } returns null
        every { registry.devices } returns registryDevices
        every { pathPrefs.choiceFor(vid, pid) } returns null
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return UsbGamepadManager(ctx, registry, Provider { hub }, notifications, scope, native, pathPrefs, descriptors)
    }

    @Test
    fun `a device with no USB service builds the manager and tracks nothing`() {
        val m = buildManagerWithoutUsbService()
        m.reconcileForeground()
        m.tryDirectMode(vid, pid)
        m.setPathChoice(vid, pid, PathChoice.Standard)
        m.releaseAllDirect()
        assertTrue(m.controllers.value.isEmpty())
        verify(exactly = 0) { registry.markDirectFailed(any(), any(), any()) }
        verify(exactly = 0) { registry.addUsbSynthetic(any(), any(), any(), any(), any(), any()) }
    }
}
