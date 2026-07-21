// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BluetoothConnectionsTest {
    private val context = mockk<Context>(relaxed = true)
    private val receiverSlot = slot<BroadcastReceiver>()
    private lateinit var connections: BluetoothConnections
    private var changes = 0

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.registerReceiver(any(), capture(receiverSlot), any(), any()) } returns null
        changes = 0
        connections = BluetoothConnections(context)
        connections.start { changes++ }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // One-arg getParcelableExtra: the JVM stub's SDK_INT=0 drives the legacy path.
    @Suppress("DEPRECATION")
    private fun aclEvent(
        action: String,
        deviceName: String?,
    ): Intent {
        val device = mockk<BluetoothDevice> { every { name } returns deviceName }
        return mockk<Intent> {
            every { this@mockk.action } returns action
            every { getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns device
        }
    }

    private fun deliver(
        action: String,
        deviceName: String?,
    ) = receiverSlot.captured.onReceive(context, aclEvent(action, deviceName))

    @Test
    fun `nothing is connected before any broadcast`() {
        assertFalse(connections.isConnected("Xbox Wireless Controller"))
    }

    @Test
    fun `a connected device matches by name ignoring case and surrounding space`() {
        deliver(BluetoothDevice.ACTION_ACL_CONNECTED, "Xbox Wireless Controller")

        assertTrue(connections.isConnected("xbox wireless controller"))
        assertTrue(connections.isConnected("  Xbox Wireless Controller  "))
        assertFalse(connections.isConnected("DualSense Wireless Controller"))
    }

    @Test
    fun `a disconnected device stops matching`() {
        deliver(BluetoothDevice.ACTION_ACL_CONNECTED, "DualSense Wireless Controller")
        deliver(BluetoothDevice.ACTION_ACL_DISCONNECTED, "DualSense Wireless Controller")

        assertFalse(connections.isConnected("DualSense Wireless Controller"))
    }

    @Test
    fun `each handled broadcast notifies the listener`() {
        deliver(BluetoothDevice.ACTION_ACL_CONNECTED, "Xbox Wireless Controller")
        deliver(BluetoothDevice.ACTION_ACL_DISCONNECTED, "Xbox Wireless Controller")

        assertEquals(2, changes)
    }

    @Test
    fun `a broadcast without a device name is ignored`() {
        deliver(BluetoothDevice.ACTION_ACL_CONNECTED, null)

        assertFalse(connections.isConnected("Xbox Wireless Controller"))
        assertEquals(0, changes)
    }

    @Test
    fun `a blank query never matches`() {
        deliver(BluetoothDevice.ACTION_ACL_CONNECTED, "Xbox Wireless Controller")

        assertFalse(connections.isConnected("   "))
    }
}
