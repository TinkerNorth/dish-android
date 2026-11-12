// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

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

// A wireless receiver serves up to four paired controllers under one vid:pid, one HID interface
// each. The Android USB layer is mocked as such a receiver: its keyboard, then a slot per interface,
// each of which native attaches under a synthetic id of its own and reports linked while the test
// says its controller is connected. The registry is real, so its devices are the pads on the list.
@OptIn(ExperimentalCoroutinesApi::class)
class UsbReceiverManagerTest {
    private val valve = 0x28DE
    private val steamReceiver = 0x1142
    private val proteusReceiver = 0x1304
    private val nereidReceiver = 0x1305
    private val receiverName = "Valve Steam Controller (dongle)"

    private val usbManager = mockk<UsbManager>(relaxed = true)
    private val native = mockk<PhysicalInputNative>(relaxed = true)
    private val notifications = mockk<DishNotifications>(relaxed = true)
    private val hub = mockk<ConnectionCoordinator>(relaxed = true)
    private val pathPrefs = mockk<UsbPathPreferenceStore>(relaxed = true)
    private val ctx = mockk<Context>(relaxed = true)
    private val conn = mockk<UsbDeviceConnection>(relaxed = true)
    private val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val types = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())

    // The synthetic ids whose controller is connected, as native reads its slots.
    private val linked = mutableSetOf<Int>()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private lateinit var registry: PhysicalGamepadRegistry

    @Before
    fun setUp() {
        every { hub.bindings } returns bindings
        every { hub.satTypes } returns types
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

    private fun hidInterface(
        number: Int,
        subclass: Int,
        protocol: Int,
    ): UsbInterface {
        val epIn =
            mockk<UsbEndpoint> {
                every { type } returns UsbConstants.USB_ENDPOINT_XFER_INT
                every { direction } returns UsbConstants.USB_DIR_IN
                every { address } returns IN_ENDPOINT_BASE + number
                every { maxPacketSize } returns 64
                every { interval } returns 4
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
        productId: Int,
        interfaces: List<UsbInterface>,
    ): UsbDevice =
        mockk {
            every { vendorId } returns valve
            every { this@mockk.productId } returns productId
            every { deviceName } returns "receiver"
            every { interfaceCount } returns interfaces.size
            interfaces.forEachIndexed { index, intf -> every { getInterface(index) } returns intf }
        }

    // hid-steam's steam_is_valve_interface: the 2015 receiver is its keyboard, then slots 1 to 4.
    private fun steamReceiverDevice(): UsbDevice =
        usbDevice(steamReceiver, listOf(hidInterface(0, 0x01, 0x01)) + (1..4).map { hidInterface(it, 0x00, 0x00) })

    // The 2026 receivers' slots are 2 to 5.
    private fun proteusReceiverDevice(): UsbDevice = usbDevice(proteusReceiver, (0..6).map { hidInterface(it, 0x00, 0x00) })

    private fun nereidReceiverDevice(): UsbDevice = usbDevice(nereidReceiver, (0..6).map { hidInterface(it, 0x00, 0x00) })

    private fun slotPad(interfaceNumber: Int) = SYNTHETIC_BASE - interfaceNumber

    private fun managerFor(device: UsbDevice): UsbGamepadManager {
        val vid = device.vendorId
        val pid = device.productId
        every { ctx.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { usbManager.deviceList } returns hashMapOf("d" to device)
        every { usbManager.hasPermission(device) } returns true
        every { usbManager.openDevice(device) } returns conn
        every { conn.fileDescriptor } returns 7
        every { conn.claimInterface(any(), true) } returns true
        every { native.lookupKnownModelName(vid, pid) } returns receiverName
        every { native.modelExpectsFrameworkGamepad(vid, pid) } returns false
        every { pathPrefs.choiceFor(vid, pid) } returns null
        every { native.attachUsbDevice(any(), vid, pid, any()) } answers { slotPad(arg<UsbInterfaceClaim>(3).interfaceNumber) }
        every { native.isUsbPadLinked(any()) } answers { firstArg<Int>() in linked }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return UsbGamepadManager(ctx, registry, Provider { hub }, notifications, scope, native, pathPrefs, UsbDescriptorStore())
    }

    private fun connect(vararg interfaceNumbers: Int) {
        interfaceNumbers.forEach { linked += slotPad(it) }
    }

    private fun disconnect(vararg interfaceNumbers: Int) {
        interfaceNumbers.forEach { linked -= slotPad(it) }
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

    private fun claimedSteamReceiver(): UsbGamepadManager {
        val m = managerFor(steamReceiverDevice())
        m.tryDirectMode(valve, steamReceiver)
        return m
    }

    @Test
    fun `a receiver with two controllers connected lists two pads`() {
        connect(1, 3)

        claimedSteamReceiver()
        aLinkReadLater()

        assertEquals(listOf(slotPad(1), slotPad(3)), pads())
    }

    @Test
    fun `a receiver with three controllers connected lists three pads`() {
        connect(1, 2, 4)

        claimedSteamReceiver()
        aLinkReadLater()

        assertEquals(listOf(slotPad(1), slotPad(2), slotPad(4)), pads())
    }

    @Test
    fun `a receiver with four controllers connected lists four pads`() {
        connect(1, 2, 3, 4)

        claimedSteamReceiver()
        aLinkReadLater()

        assertEquals(listOf(slotPad(1), slotPad(2), slotPad(3), slotPad(4)), pads())
    }

    @Test
    fun `a 2026 receiver lists a pad for each controller on its slots 2 to 5`() {
        connect(2, 5)
        val m = managerFor(proteusReceiverDevice())

        m.tryDirectMode(valve, proteusReceiver)
        aLinkReadLater()

        assertEquals(listOf(slotPad(2), slotPad(5)), pads())
    }

    @Test
    fun `a Nereid receiver lists a pad for each controller on its slots 2 to 5`() {
        connect(3, 4)
        val m = managerFor(nereidReceiverDevice())

        m.tryDirectMode(valve, nereidReceiver)
        aLinkReadLater()

        assertEquals(listOf(slotPad(3), slotPad(4)), pads())
    }

    @Test
    fun `a remembered Direct pick opens every slot of the receiver`() {
        val m = managerFor(steamReceiverDevice())
        every { pathPrefs.choiceFor(valve, steamReceiver) } returns PathChoice.Direct

        m.reconcileForeground()

        for (slot in 1..4) {
            verify { native.attachUsbDevice(7, valve, steamReceiver, match { it.interfaceNumber == slot && it.receiverSlot }) }
        }
        assertEquals(UsbPhase.Direct, m.controllers.value[vidPidKey(valve, steamReceiver)]?.phase)
    }

    @Test
    fun `Direct on a receiver claims every slot, each to listen for its controller`() {
        claimedSteamReceiver()

        for (slot in 1..4) {
            verify { native.attachUsbDevice(7, valve, steamReceiver, match { it.interfaceNumber == slot && it.receiverSlot }) }
        }
        verify(exactly = 0) { native.attachUsbDevice(any(), any(), any(), match { it.interfaceNumber == 0 }) }
    }

    @Test
    fun `a receiver whose controllers are all off is Direct with no pad`() {
        val m = claimedSteamReceiver()
        aLinkReadLater()

        assertEquals(UsbPhase.Direct, m.controllers.value[vidPidKey(valve, steamReceiver)]?.phase)
        assertEquals(emptyList<Int>(), pads())
    }

    @Test
    fun `an empty slot never becomes a pad`() {
        connect(1)
        claimedSteamReceiver()

        repeat(LINK_READS) { aLinkReadLater() }

        assertEquals(listOf(slotPad(1)), pads())
    }

    @Test
    fun `a controller switched on later appears as its own pad`() {
        connect(1)
        claimedSteamReceiver()
        aLinkReadLater()

        connect(3)
        aLinkReadLater()

        assertEquals(listOf(slotPad(1), slotPad(3)), pads())
    }

    @Test
    fun `a controller switched off leaves`() {
        connect(1, 3)
        claimedSteamReceiver()
        aLinkReadLater()

        disconnect(3)
        aLinkReadLater()

        assertEquals(listOf(slotPad(1)), pads())
    }

    @Test
    fun `a slot keeps its pad when another slot's controller leaves`() {
        connect(1, 3)
        claimedSteamReceiver()
        aLinkReadLater()

        disconnect(1)
        aLinkReadLater()

        assertEquals(listOf(slotPad(3)), pads())
    }

    @Test
    fun `a controller that reconnects comes back as the same pad on the same slot`() {
        connect(2)
        claimedSteamReceiver()
        aLinkReadLater()
        disconnect(2)
        aLinkReadLater()

        connect(2)
        aLinkReadLater()

        assertEquals(listOf(slotPad(2)), pads())
        verify(exactly = 1) { native.attachUsbDevice(any(), any(), any(), match { it.interfaceNumber == 2 }) }
    }

    @Test
    fun `a pad that appears is bound the way a new claim is`() {
        connect(2)

        claimedSteamReceiver()
        aLinkReadLater()

        verify { hub.bindClaimedSynthetic(null, slotPad(2).toString()) }
    }

    @Test
    fun `a controller that reconnects comes back with the binding it left with`() {
        connect(2)
        claimedSteamReceiver()
        aLinkReadLater()
        bindings.value = mapOf(slotPad(2).toString() to "sat-1")
        types.value = mapOf(("sat-1" to slotPad(2).toString()) to 3)
        disconnect(2)
        aLinkReadLater()
        bindings.value = emptyMap()

        connect(2)
        aLinkReadLater()

        verify { hub.bind(slotPad(2).toString(), "sat-1", 3) }
    }

    @Test
    fun `Standard picked from one slot's card releases every slot of the receiver`() {
        connect(1, 2)
        val m = claimedSteamReceiver()
        aLinkReadLater()

        m.setPathChoice(valve, steamReceiver, PathChoice.Standard)

        for (slot in 1..4) verify { native.detachUsbDevice(slotPad(slot)) }
        assertEquals(emptyList<Int>(), pads())
        assertEquals(UsbPhase.Routed, m.controllers.value[vidPidKey(valve, steamReceiver)]?.phase)
        verify { pathPrefs.setChoice(valve, steamReceiver, PathChoice.Standard) }
    }

    @Test
    fun `a released receiver lists no pad when a controller connects`() {
        val m = claimedSteamReceiver()
        m.setPathChoice(valve, steamReceiver, PathChoice.Standard)

        connect(1)
        aLinkReadLater()

        assertEquals(emptyList<Int>(), pads())
    }

    @Test
    fun `a receiver slot the system refuses claims no slot at all`() {
        val m = managerFor(steamReceiverDevice())
        every { conn.claimInterface(match { it.id == 3 }, true) } returns false

        m.tryDirectMode(valve, steamReceiver)

        assertEquals(DirectClaimFailure.Busy, m.controllers.value[vidPidKey(valve, steamReceiver)]?.failure)
        verify(exactly = 0) { native.attachUsbDevice(any(), any(), any(), any()) }
        verify { conn.close() }
    }

    @Test
    fun `a receiver slot that will not attach gives back the slots that did`() {
        val m = managerFor(steamReceiverDevice())
        every { native.attachUsbDevice(any(), valve, steamReceiver, match { it.interfaceNumber == 3 }) } returns 0

        m.tryDirectMode(valve, steamReceiver)

        assertEquals(DirectClaimFailure.InitFailed, m.controllers.value[vidPidKey(valve, steamReceiver)]?.failure)
        verify { native.detachUsbDevice(slotPad(1)) }
        verify { native.detachUsbDevice(slotPad(2)) }
        verify { conn.close() }
        assertEquals(emptyList<Int>(), pads())
    }

    @Test
    fun `the setup wizard is handed the receiver's first connected slot`() {
        connect(2, 4)
        val m = claimedSteamReceiver()
        aLinkReadLater()

        assertEquals(slotPad(2), m.controllers.value[vidPidKey(valve, steamReceiver)]?.firstPadId())
    }

    private companion object {
        const val SYNTHETIC_BASE = -1000
        const val IN_ENDPOINT_BASE = 0x81
        const val LINK_READS = 4
    }
}
