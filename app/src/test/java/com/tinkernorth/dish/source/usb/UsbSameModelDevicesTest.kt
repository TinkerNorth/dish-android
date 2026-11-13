// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

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
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.core.input.vidPidKey
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.jni.UsbInterfaceClaim
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
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

// Two physical devices of one model: one vendor and product id, a device node and a connection
// each. Native attaches a claimed interface under an id made of its connection's fd and its
// interface number, and the registry is real, so its devices are the pads on the list.
@OptIn(ExperimentalCoroutinesApi::class)
class UsbSameModelDevicesTest {
    private val usbManager = mockk<UsbManager>(relaxed = true)
    private val native = mockk<PhysicalInputNative>(relaxed = true)
    private val notifications = mockk<DishNotifications>(relaxed = true)
    private val hub = mockk<ConnectionCoordinator>(relaxed = true)
    private val pathPrefs = mockk<UsbPathPreferenceStore>(relaxed = true)
    private val ctx = mockk<Context>(relaxed = true)
    private val firstConn = connection(FIRST_FD)
    private val secondConn = connection(SECOND_FD)
    private val plugged = LinkedHashMap<String, UsbDevice>()

    // The ids whose controller is connected, as native reads a receiver's slots.
    private val linked = mutableSetOf<Int>()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private lateinit var registry: PhysicalGamepadRegistry
    private lateinit var usbBroadcasts: BroadcastReceiver

    private val firstDualSense = dualSenseOn(FIRST_NODE)
    private val secondDualSense = dualSenseOn(SECOND_NODE)
    private val firstPad = padOf(FIRST_FD, DUALSENSE_INTERFACE)
    private val secondPad = padOf(SECOND_FD, DUALSENSE_INTERFACE)
    private val dualSenseModel = vidPidKey(SONY, DUALSENSE)

    @Before
    fun setUp() {
        every { hub.bindings } returns MutableStateFlow(emptyMap())
        every { hub.satTypes } returns MutableStateFlow(emptyMap())
        every { usbManager.hasPermission(any<UsbDevice>()) } returns true
        mockkStatic(Log::class)
        mockkStatic(ContextCompat::class, IntentCompat::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        Dispatchers.setMain(dispatcher)
        registry = realRegistry()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun realRegistry(): PhysicalGamepadRegistry {
        val registryCtx = mockk<Context>(relaxed = true)
        every { registryCtx.getSystemService(Context.INPUT_SERVICE) } returns mockk<InputManager>(relaxed = true)
        every { registryCtx.getSystemService(Context.USB_SERVICE) } returns mockk<UsbManager>(relaxed = true)
        return PhysicalGamepadRegistry(registryCtx, CoroutineScope(SupervisorJob()), native, mockk(relaxed = true))
    }

    private fun connection(fd: Int): UsbDeviceConnection =
        mockk(relaxed = true) {
            every { fileDescriptor } returns fd
            every { claimInterface(any(), true) } returns true
        }

    private fun hidInterface(
        number: Int,
        subclass: Int = 0,
        protocol: Int = 0,
    ): UsbInterface {
        val epIn =
            mockk<UsbEndpoint> {
                every { type } returns UsbConstants.USB_ENDPOINT_XFER_INT
                every { direction } returns UsbConstants.USB_DIR_IN
                every { address } returns IN_ENDPOINT_BASE + number
                every { maxPacketSize } returns MAX_PACKET
                every { interval } returns 1
            }
        return mockk {
            every { interfaceClass } returns UsbConstants.USB_CLASS_HID
            every { interfaceSubclass } returns subclass
            every { interfaceProtocol } returns protocol
            every { id } returns number
            every { endpointCount } returns 1
            every { getEndpoint(0) } returns epIn
        }
    }

    private fun usbDevice(
        node: String,
        vendorId: Int,
        productId: Int,
        interfaces: List<UsbInterface>,
    ): UsbDevice =
        mockk {
            every { this@mockk.vendorId } returns vendorId
            every { this@mockk.productId } returns productId
            every { deviceName } returns node
            every { interfaceCount } returns interfaces.size
            interfaces.forEachIndexed { index, intf -> every { getInterface(index) } returns intf }
        }

    private fun dualSenseOn(node: String): UsbDevice = usbDevice(node, SONY, DUALSENSE, listOf(hidInterface(DUALSENSE_INTERFACE)))

    // Its keyboard, then a slot per paired controller.
    private fun steamReceiverOn(node: String): UsbDevice =
        usbDevice(
            node,
            VALVE,
            STEAM_RECEIVER,
            listOf(hidInterface(0, HID_BOOT_SUBCLASS, HID_KEYBOARD_PROTOCOL)) + STEAM_RECEIVER_SLOTS.map { hidInterface(it) },
        )

    private fun tritonReceiverOn(
        node: String,
        productId: Int,
    ): UsbDevice = usbDevice(node, VALVE, productId, (0..TRITON_LAST_INTERFACE).map { hidInterface(it) })

    private fun padOf(
        fd: Int,
        interfaceNumber: Int,
    ): Int = SYNTHETIC_BASE - fd * IDS_PER_CONNECTION - interfaceNumber

    private fun stubModel(
        vendorId: Int,
        productId: Int,
        frameworkExpected: Boolean,
    ) {
        every { native.lookupKnownModelName(vendorId, productId) } returns MODEL_NAME
        every { native.modelExpectsFrameworkGamepad(vendorId, productId) } returns frameworkExpected
        every { pathPrefs.choiceFor(vendorId, productId) } returns null
    }

    private fun installed(
        first: UsbDevice,
        second: UsbDevice,
    ): UsbGamepadManager {
        plugged[FIRST_NODE] = first
        plugged[SECOND_NODE] = second
        every { ctx.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { usbManager.deviceList } answers { HashMap(plugged) }
        every { usbManager.openDevice(first) } returns firstConn
        every { usbManager.openDevice(second) } returns secondConn
        every { native.attachUsbDevice(any(), any(), any(), any()) } answers {
            padOf(firstArg(), arg<UsbInterfaceClaim>(3).interfaceNumber)
        }
        every { native.isUsbPadLinked(any()) } answers { firstArg<Int>() in linked }
        val broadcasts = slot<BroadcastReceiver>()
        every { ContextCompat.registerReceiver(ctx, capture(broadcasts), any(), ContextCompat.RECEIVER_NOT_EXPORTED) } returns null
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val manager = UsbGamepadManager(ctx, registry, Provider { hub }, notifications, scope, native, pathPrefs, UsbDescriptorStore())
        manager.install()
        usbBroadcasts = broadcasts.captured
        return manager
    }

    private fun intentFor(
        action: String,
        device: UsbDevice,
    ): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns action
        every { IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) } returns device
        return intent
    }

    private fun unplug(device: UsbDevice) {
        plugged.remove(device.deviceName)
        usbBroadcasts.onReceive(ctx, intentFor(UsbManager.ACTION_USB_DEVICE_DETACHED, device))
    }

    private fun plugIn(device: UsbDevice) {
        plugged[device.deviceName] = device
        usbBroadcasts.onReceive(ctx, intentFor(UsbManager.ACTION_USB_DEVICE_ATTACHED, device))
    }

    private fun answerPermission(
        device: UsbDevice,
        granted: Boolean,
    ) {
        val intent = intentFor(USB_PERMISSION, device)
        every { intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) } returns granted
        usbBroadcasts.onReceive(ctx, intent)
    }

    private fun connect(
        fd: Int,
        vararg interfaceNumbers: Int,
    ) {
        interfaceNumbers.forEach { linked += padOf(fd, it) }
    }

    private fun aLinkReadLater() {
        scheduler.advanceTimeBy(RECEIVER_LINK_POLL_MS + 1)
        scheduler.runCurrent()
    }

    private fun pads(): List<Int> =
        registry.devices.value.values
            .filter { it.isUsbSynthetic }
            .map { it.id }
            .sortedDescending()

    private fun claimedDualSenses(): UsbGamepadManager {
        stubModel(SONY, DUALSENSE, frameworkExpected = true)
        val manager = installed(firstDualSense, secondDualSense)
        manager.tryDirectMode(SONY, DUALSENSE)
        return manager
    }

    private fun refusedFirstDualSense(): UsbGamepadManager {
        stubModel(SONY, DUALSENSE, frameworkExpected = true)
        val manager = installed(firstDualSense, secondDualSense)
        every { usbManager.openDevice(firstDualSense) } returns null
        manager.tryDirectMode(SONY, DUALSENSE)
        return manager
    }

    private fun claimedReceivers(
        first: UsbDevice,
        second: UsbDevice,
    ): UsbGamepadManager {
        stubModel(first.vendorId, first.productId, frameworkExpected = false)
        val manager = installed(first, second)
        manager.tryDirectMode(first.vendorId, first.productId)
        aLinkReadLater()
        return manager
    }

    private fun verifyEverySlotAttached(
        fd: Int,
        productId: Int,
        slots: IntRange,
    ) {
        for (slot in slots) {
            verify { native.attachUsbDevice(fd, VALVE, productId, match { it.interfaceNumber == slot && it.receiverSlot }) }
        }
    }

    @Test
    fun `two pads of one model each get a claim and a pad of their own`() {
        claimedDualSenses()

        assertEquals(listOf(firstPad, secondPad), pads())
    }

    @Test
    fun `unplugging the first of two pads leaves the second its claim and its pad`() {
        val m = claimedDualSenses()

        unplug(firstDualSense)

        assertEquals(listOf(secondPad), pads())
        verify(exactly = 0) { native.detachUsbDevice(secondPad) }
        verify(exactly = 0) { secondConn.close() }
        assertEquals(UsbPhase.Direct, m.controllers.value[dualSenseModel]?.phase)
    }

    @Test
    fun `unplugging the second of two pads leaves the first its claim and its pad`() {
        val m = claimedDualSenses()

        unplug(secondDualSense)

        assertEquals(listOf(firstPad), pads())
        verify(exactly = 0) { native.detachUsbDevice(firstPad) }
        verify(exactly = 0) { firstConn.close() }
        assertEquals(UsbPhase.Direct, m.controllers.value[dualSenseModel]?.phase)
    }

    @Test
    fun `both pads of one model hold a Direct claim each`() {
        val m = claimedDualSenses()

        assertEquals(2, m.controllersByUnit.value.directClaimCount())
    }

    @Test
    fun `a pad keeps its unit while it stays and a newcomer takes the lowest free one`() {
        val m = claimedDualSenses()
        unplug(firstDualSense)

        plugIn(dualSenseOn(THIRD_NODE))

        val byUnit = m.controllersByUnit.value
        assertEquals(secondPad, byUnit[UsbUnitKey(dualSenseModel, 1)]?.syntheticId)
        assertEquals(UsbPhase.Routed, byUnit[UsbUnitKey(dualSenseModel, 0)]?.phase)
    }

    @Test
    fun `Standard picked for the model releases both of its pads`() {
        val m = claimedDualSenses()

        m.setPathChoice(SONY, DUALSENSE, PathChoice.Standard)

        verify { native.detachUsbDevice(firstPad) }
        verify { native.detachUsbDevice(secondPad) }
        verify { pathPrefs.setChoice(SONY, DUALSENSE, PathChoice.Standard) }
    }

    @Test
    fun `a pad that fails to claim leaves its twin on Direct and its failure on record`() {
        refusedFirstDualSense()

        assertEquals(listOf(secondPad), pads())
        assertEquals(DirectClaimFailure.Busy, registry.directFailureFor(SONY, DUALSENSE))
    }

    @Test
    fun `unplugging the twin that claimed keeps the failure of the one that did not`() {
        refusedFirstDualSense()

        unplug(secondDualSense)

        assertEquals(DirectClaimFailure.Busy, registry.directFailureFor(SONY, DUALSENSE))
    }

    @Test
    fun `a permission answer reaches only the pad it was asked for`() {
        every { usbManager.hasPermission(any<UsbDevice>()) } returns false
        every { native.isKnownFastLaneModel(SONY, DUALSENSE) } returns true
        stubModel(SONY, DUALSENSE, frameworkExpected = true)
        installed(firstDualSense, secondDualSense)

        answerPermission(firstDualSense, granted = false)
        every { usbManager.hasPermission(secondDualSense) } returns true
        answerPermission(secondDualSense, granted = true)

        assertEquals(listOf(secondPad), pads())
        verify(exactly = 0) { usbManager.openDevice(firstDualSense) }
    }

    @Test
    fun `two 2015 receivers each read their own controllers as pads`() {
        connect(FIRST_FD, 1)
        connect(SECOND_FD, 4)

        claimedReceivers(steamReceiverOn(FIRST_NODE), steamReceiverOn(SECOND_NODE))

        assertEquals(listOf(padOf(FIRST_FD, 1), padOf(SECOND_FD, 4)), pads())
        verifyEverySlotAttached(FIRST_FD, STEAM_RECEIVER, STEAM_RECEIVER_SLOTS)
        verifyEverySlotAttached(SECOND_FD, STEAM_RECEIVER, STEAM_RECEIVER_SLOTS)
    }

    @Test
    fun `two Proteus receivers each read their own controllers as pads`() {
        connect(FIRST_FD, 2)
        connect(SECOND_FD, 5)

        claimedReceivers(tritonReceiverOn(FIRST_NODE, PROTEUS_RECEIVER), tritonReceiverOn(SECOND_NODE, PROTEUS_RECEIVER))

        assertEquals(listOf(padOf(FIRST_FD, 2), padOf(SECOND_FD, 5)), pads())
        verifyEverySlotAttached(FIRST_FD, PROTEUS_RECEIVER, TRITON_RECEIVER_SLOTS)
        verifyEverySlotAttached(SECOND_FD, PROTEUS_RECEIVER, TRITON_RECEIVER_SLOTS)
    }

    @Test
    fun `two Nereid receivers each read their own controllers as pads`() {
        connect(FIRST_FD, 3)
        connect(SECOND_FD, 3)

        claimedReceivers(tritonReceiverOn(FIRST_NODE, NEREID_RECEIVER), tritonReceiverOn(SECOND_NODE, NEREID_RECEIVER))

        assertEquals(listOf(padOf(FIRST_FD, 3), padOf(SECOND_FD, 3)), pads())
        verifyEverySlotAttached(FIRST_FD, NEREID_RECEIVER, TRITON_RECEIVER_SLOTS)
        verifyEverySlotAttached(SECOND_FD, NEREID_RECEIVER, TRITON_RECEIVER_SLOTS)
    }

    @Test
    fun `unplugging the first of two 2015 receivers leaves the second reading its controllers`() {
        val firstReceiver = steamReceiverOn(FIRST_NODE)
        connect(FIRST_FD, 1)
        connect(SECOND_FD, 1)
        claimedReceivers(firstReceiver, steamReceiverOn(SECOND_NODE))

        unplug(firstReceiver)
        connect(SECOND_FD, 3)
        aLinkReadLater()

        assertEquals(listOf(padOf(SECOND_FD, 1), padOf(SECOND_FD, 3)), pads())
        verify(exactly = 0) { secondConn.close() }
    }

    @Test
    fun `unplugging the second of two 2026 receivers leaves the first its controllers`() {
        val secondReceiver = tritonReceiverOn(SECOND_NODE, PROTEUS_RECEIVER)
        connect(FIRST_FD, 2)
        connect(SECOND_FD, 2)
        claimedReceivers(tritonReceiverOn(FIRST_NODE, PROTEUS_RECEIVER), secondReceiver)

        unplug(secondReceiver)
        aLinkReadLater()

        assertEquals(listOf(padOf(FIRST_FD, 2)), pads())
        for (slot in TRITON_RECEIVER_SLOTS) verify(exactly = 0) { native.detachUsbDevice(padOf(FIRST_FD, slot)) }
    }

    private companion object {
        const val SONY = 0x054C
        const val DUALSENSE = 0x0CE6
        const val VALVE = 0x28DE
        const val STEAM_RECEIVER = 0x1142
        const val PROTEUS_RECEIVER = 0x1304
        const val NEREID_RECEIVER = 0x1305
        const val MODEL_NAME = "Controller"
        const val USB_PERMISSION = "com.tinkernorth.dish.USB_PERMISSION"
        const val FIRST_NODE = "/dev/bus/usb/001/002"
        const val SECOND_NODE = "/dev/bus/usb/001/003"
        const val THIRD_NODE = "/dev/bus/usb/001/004"
        const val FIRST_FD = 7
        const val SECOND_FD = 8
        const val SYNTHETIC_BASE = -1000
        const val IDS_PER_CONNECTION = 10
        const val DUALSENSE_INTERFACE = 3
        const val TRITON_LAST_INTERFACE = 6
        const val IN_ENDPOINT_BASE = 0x81
        const val MAX_PACKET = 64
        const val HID_BOOT_SUBCLASS = 0x01
        const val HID_KEYBOARD_PROTOCOL = 0x01
        val STEAM_RECEIVER_SLOTS = 1..4
        val TRITON_RECEIVER_SLOTS = 2..5
    }
}
