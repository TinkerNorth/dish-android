// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHidProxyClientReportTest {
    private val context = mockk<Context>(relaxed = true)
    private val client = AndroidHidProxyClient(context)

    @After
    fun tearDown() {
        unmockkAll()
    }

    // sendReport reads @Volatile hidDevice/connectedDevice set by the binder thread; the tests
    // inject them directly since there is no Android adapter to drive the real callbacks here.
    private fun setField(
        name: String,
        value: Any?,
    ) {
        AndroidHidProxyClient::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(client, value)
    }

    @Test
    fun `sendReport returns false without crashing when the proxy throws after teardown`() {
        val hid =
            mockk<BluetoothHidDevice> {
                every { sendReport(any(), any(), any()) } throws IllegalStateException("proxy closed")
            }
        setField("hidDevice", hid)
        setField("connectedDevice", mockk<BluetoothDevice>(relaxed = true))

        assertFalse(client.sendReport(ByteArray(14)))
    }

    @Test
    fun `sendReport delegates to the proxy and returns its result on the happy path`() {
        val device = mockk<BluetoothDevice>(relaxed = true)
        val hid =
            mockk<BluetoothHidDevice> {
                every { sendReport(any(), any(), any()) } returns true
            }
        setField("hidDevice", hid)
        setField("connectedDevice", device)

        assertTrue(client.sendReport(ByteArray(14).also { it[0] = 1 }))
        verify { hid.sendReport(device, 1, any()) }
    }

    @Test
    fun `sendReport returns false when nothing is connected`() {
        assertFalse(client.sendReport(ByteArray(14)))
    }

    @Test
    fun `sendReport forwards the payload with the report-id byte stripped`() {
        val device = mockk<BluetoothDevice>(relaxed = true)
        val payload = slot<ByteArray>()
        val hid =
            mockk<BluetoothHidDevice> {
                every { sendReport(any(), any(), capture(payload)) } returns true
            }
        setField("hidDevice", hid)
        setField("connectedDevice", device)

        assertTrue(client.sendReport(ByteArray(14) { it.toByte() }))
        assertArrayEquals(ByteArray(13) { (it + 1).toByte() }, payload.captured)
    }

    private fun deviceAt(mac: String): BluetoothDevice =
        mockk(relaxed = true) {
            every { address } returns mac
        }

    private fun hidCallback(): BluetoothHidDevice.Callback =
        AndroidHidProxyClient::class.java
            .getDeclaredField("hidCallback")
            .apply { isAccessible = true }
            .get(client) as BluetoothHidDevice.Callback

    private fun connectedDevice(): BluetoothDevice? =
        AndroidHidProxyClient::class.java
            .getDeclaredField("connectedDevice")
            .apply { isAccessible = true }
            .get(client) as BluetoothDevice?

    @Test
    fun `a stale disconnect for another host keeps the current connection`() {
        val current = deviceAt("AA:BB")
        setField("connectedDevice", current)

        hidCallback().onConnectionStateChanged(deviceAt("CC:DD"), BluetoothProfile.STATE_DISCONNECTED)

        assertSame(current, connectedDevice())
    }

    @Test
    fun `a disconnect for the connected host clears it`() {
        setField("connectedDevice", deviceAt("AA:BB"))

        hidCallback().onConnectionStateChanged(deviceAt("AA:BB"), BluetoothProfile.STATE_DISCONNECTED)

        assertNull(connectedDevice())
    }
}
