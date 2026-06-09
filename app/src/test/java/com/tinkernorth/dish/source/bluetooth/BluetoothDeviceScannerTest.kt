// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothDeviceScannerTest {
    private lateinit var context: Context
    private lateinit var adapter: BluetoothAdapter
    private lateinit var scanner: BluetoothDeviceScanner

    private val devices: List<BluetoothDeviceScanner.Device> get() = scanner.state.value.devices
    private val scanning: Boolean get() = scanner.state.value.scanning

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        adapter = mockk(relaxed = true)
        every { adapter.bondedDevices } returns emptySet()
        every { adapter.startDiscovery() } returns true
        every { adapter.cancelDiscovery() } returns true
        scanner = BluetoothDeviceScanner(context) { adapter }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun deviceMock(
        mac: String?,
        name: String?,
    ): BluetoothDevice {
        val device = mockk<BluetoothDevice>(relaxed = true)
        every { device.address } returns mac
        every { device.name } returns name
        return device
    }

    private fun foundIntent(device: BluetoothDevice?): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns BluetoothDevice.ACTION_FOUND
        every { intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns device
        every {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } returns device
        return intent
    }

    private fun discoveryFinishedIntent(): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns BluetoothAdapter.ACTION_DISCOVERY_FINISHED
        return intent
    }

    private fun receiverField(): BroadcastReceiver? {
        val field = BluetoothDeviceScanner::class.java.getDeclaredField("receiver")
        field.isAccessible = true
        return field.get(scanner) as BroadcastReceiver?
    }

    private fun fire(intent: Intent) {
        (receiverField() ?: error("no receiver registered")).onReceive(context, intent)
    }

    @Test
    fun `initial state is empty and not scanning`() {
        assertTrue(devices.isEmpty())
        assertFalse(scanning)
    }

    @Test
    fun `start with canScan false seeds bonded devices without scanning`() {
        every { adapter.bondedDevices } returns setOf(deviceMock("AA", "Xbox"))

        scanner.start(canScan = false)

        assertEquals(1, devices.size)
        val device = devices[0]
        assertTrue(device.bonded)
        assertEquals("Xbox", device.name)
        assertFalse(scanning)
        verify(exactly = 0) { adapter.startDiscovery() }
    }

    @Test
    fun `start with canScan false does not register a receiver`() {
        scanner.start(canScan = false)

        assertNull(receiverField())
    }

    @Test
    fun `start with canScan true starts discovery and reports scanning`() {
        every { adapter.startDiscovery() } returns true

        scanner.start(canScan = true)

        assertTrue(scanning)
        verify { adapter.startDiscovery() }
    }

    @Test
    fun `start reports not scanning when startDiscovery returns false`() {
        every { adapter.startDiscovery() } returns false

        scanner.start(canScan = true)

        assertFalse(scanning)
    }

    @Test
    fun `start degrades to not scanning when startDiscovery throws`() {
        every { adapter.startDiscovery() } throws SecurityException("scan permission revoked")

        scanner.start(canScan = true)

        assertFalse(scanning)
    }

    @Test
    fun `start degrades to no bonded devices when bondedDevices throws`() {
        every { adapter.bondedDevices } throws SecurityException("connect permission revoked")

        scanner.start(canScan = true)

        assertTrue(devices.isEmpty())
    }

    @Test
    fun `start with no adapter does not crash and reports not scanning`() {
        val noAdapter = BluetoothDeviceScanner(context) { null }

        noAdapter.start(canScan = true)

        val state = noAdapter.state.value
        assertFalse(state.scanning)
        assertTrue(state.devices.isEmpty())
    }

    @Test
    fun `bonded devices are sorted before discovered and then by name`() {
        every { adapter.bondedDevices } returns
            linkedSetOf(deviceMock("BB", "Zeta"), deviceMock("AA", "Alpha"))

        scanner.start(canScan = true)
        fire(foundIntent(deviceMock("CC", "Mid")))

        assertEquals(listOf("Alpha", "Zeta", "Mid"), devices.map { it.name })
    }

    @Test
    fun `discovered device is appended as not bonded and keeps scanning true`() {
        scanner.start(canScan = true)

        fire(foundIntent(deviceMock("CC", "Controller")))

        val device = devices.single { it.mac == "CC" }
        assertFalse(device.bonded)
        assertEquals("Controller", device.name)
        assertTrue(scanning)
    }

    @Test
    fun `discovery does not downgrade a bonded entry`() {
        every { adapter.bondedDevices } returns setOf(deviceMock("AA", "Paired Xbox"))

        scanner.start(canScan = true)
        fire(foundIntent(deviceMock("AA", "AA")))

        val device = devices.single { it.mac == "AA" }
        assertTrue(device.bonded)
        assertEquals("Paired Xbox", device.name)
    }

    @Test
    fun `duplicate discovery keeps a single entry and updates the name`() {
        scanner.start(canScan = true)

        fire(foundIntent(deviceMock("CC", "First")))
        fire(foundIntent(deviceMock("CC", "Second")))

        val matches = devices.filter { it.mac == "CC" }
        assertEquals(1, matches.size)
        assertEquals("Second", matches[0].name)
    }

    @Test
    fun `found intent without a device is ignored`() {
        scanner.start(canScan = true)

        fire(foundIntent(device = null))

        assertTrue(devices.isEmpty())
    }

    @Test
    fun `found intent with a null address is ignored`() {
        scanner.start(canScan = true)

        fire(foundIntent(deviceMock(mac = null, name = "Nameless")))

        assertTrue(devices.isEmpty())
    }

    @Test
    fun `discovered device with an unreadable name is added with a null name`() {
        val device = mockk<BluetoothDevice>(relaxed = true)
        every { device.address } returns "CC"
        every { device.name } throws SecurityException("connect permission revoked")

        scanner.start(canScan = true)
        fire(foundIntent(device))

        assertNull(devices.single { it.mac == "CC" }.name)
    }

    @Test
    fun `discovery finished clears scanning but keeps devices`() {
        scanner.start(canScan = true)
        fire(foundIntent(deviceMock("CC", "Controller")))

        fire(discoveryFinishedIntent())

        assertFalse(scanning)
        assertEquals(1, devices.size)
    }

    @Test
    fun `stop clears devices and resets scanning`() {
        every { adapter.bondedDevices } returns setOf(deviceMock("AA", "Xbox"))
        scanner.start(canScan = true)

        scanner.stop()

        assertTrue(devices.isEmpty())
        assertFalse(scanning)
    }

    @Test
    fun `stop unregisters the receiver and cancels discovery`() {
        scanner.start(canScan = true)
        val registered = requireNotNull(receiverField())

        scanner.stop()

        verify { context.unregisterReceiver(registered) }
        verify { adapter.cancelDiscovery() }
        assertNull(receiverField())
    }

    @Test
    fun `a broadcast delivered after stop is ignored`() {
        scanner.start(canScan = true)
        val stale = requireNotNull(receiverField())
        scanner.stop()

        stale.onReceive(context, foundIntent(deviceMock("CC", "Late")))

        assertTrue(devices.isEmpty())
    }

    @Test
    fun `start is idempotent and tears down the previous receiver`() {
        scanner.start(canScan = true)
        val first = requireNotNull(receiverField())

        scanner.start(canScan = true)
        val second = requireNotNull(receiverField())

        verify { context.unregisterReceiver(first) }
        assertNotSame(first, second)
    }

    @Test
    fun `restart reseeds from the current bonded set`() {
        every { adapter.bondedDevices } returns setOf(deviceMock("AA", "Xbox"))
        scanner.start(canScan = true)
        fire(foundIntent(deviceMock("CC", "Controller")))
        assertEquals(2, devices.size)

        every { adapter.bondedDevices } returns setOf(deviceMock("BB", "PlayStation"))
        scanner.start(canScan = true)

        assertEquals(listOf("BB"), devices.map { it.mac })
    }

    @Test
    fun `state flow publishes updates to collectors`() =
        runTest {
            every { adapter.bondedDevices } returns setOf(deviceMock("AA", "Xbox"))
            val seen = mutableListOf<BluetoothDeviceScanner.State>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    scanner.state.collect { seen += it }
                }

            scanner.start(canScan = true)
            fire(foundIntent(deviceMock("CC", "Controller")))

            val last = seen.last()
            assertEquals(scanner.state.value, last)
            assertTrue(last.devices.any { it.mac == "CC" })
            job.cancel()
        }
}
