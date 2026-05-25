// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.core.input.BluetoothGamepad.GamepadProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BluetoothHidSessionTest {
    private lateinit var fake: FakeHidProxyClient
    private lateinit var session: BluetoothHidSession

    @Before
    fun setUp() {
        fake = FakeHidProxyClient()
        session = BluetoothHidSession { fake }
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(BluetoothSessionState.Idle, session.state.value)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `start from Idle moves to Acquiring and acquires a fresh proxy`() {
        session.start(GamepadProfile.XBOX, autoConnectMac = null)

        val s = session.state.value as BluetoothSessionState.Acquiring
        assertEquals(GamepadProfile.XBOX, s.profile)
        assertEquals(null, s.autoConnectMac)
        assertTrue(fake.calls.first() is FakeHidProxyClient.Call.Acquire)
    }

    @Test
    fun `onAcquired triggers registerApp`() {
        session.start(GamepadProfile.PLAYSTATION, null)
        fake.fireAcquired()

        val registerCall = fake.calls.filterIsInstance<FakeHidProxyClient.Call.RegisterApp>().single()
        assertEquals(GamepadProfile.PLAYSTATION, registerCall.profile)
        assertTrue(session.state.value is BluetoothSessionState.Acquiring)
    }

    @Test
    fun `onAppRegistered without autoConnectMac moves to Registered and does not auto-connect`() {
        session.start(GamepadProfile.XBOX, null)
        fake.fireAcquired()
        fake.fireAppRegistered()

        assertTrue(session.state.value is BluetoothSessionState.Registered)
        assertTrue(fake.calls.none { it is FakeHidProxyClient.Call.ConnectToHost })
    }

    @Test
    fun `onAppRegistered with autoConnectMac requests a connect when host is not already connected at OS level`() {
        session.start(GamepadProfile.XBOX, autoConnectMac = "AA:BB")
        fake.fireAcquired()
        fake.fireAppRegistered()

        val call = fake.calls.filterIsInstance<FakeHidProxyClient.Call.ConnectToHost>().single()
        assertEquals("AA:BB", call.mac)
        assertTrue(session.state.value is BluetoothSessionState.Registered)
    }

    @Test
    fun `onAppRegistered with autoConnectMac skips ConnectToHost when OS already has the host connected and jumps to Connected`() {
        fake.osConnectedHosts["AA:BB"] = "Living Room TV"
        session.start(GamepadProfile.XBOX, autoConnectMac = "AA:BB")
        fake.fireAcquired()
        fake.fireAppRegistered()

        assertTrue(fake.calls.none { it is FakeHidProxyClient.Call.ConnectToHost })
        val s = session.state.value as BluetoothSessionState.Connected
        assertEquals("AA:BB", s.mac)
        assertEquals("Living Room TV", s.name)
    }

    @Test
    fun `onHostConnected from Registered transitions to Connected with mac and name`() {
        session.start(GamepadProfile.XBOX, null)
        fake.fireAcquired()
        fake.fireAppRegistered()

        fake.fireHostConnected("11:22", "Xbox One")

        val s = session.state.value as BluetoothSessionState.Connected
        assertEquals("11:22", s.mac)
        assertEquals("Xbox One", s.name)
        assertEquals(GamepadProfile.XBOX, s.profile)
    }

    @Test
    fun `onHostDisconnected from Connected drops back to Registered and clears autoConnectMac`() {
        session.start(GamepadProfile.XBOX, autoConnectMac = "11:22")
        fake.fireAcquired()
        fake.fireAppRegistered()
        fake.fireHostConnected("11:22", "Xbox One")

        fake.fireHostDisconnected("11:22")

        val s = session.state.value as BluetoothSessionState.Registered
        assertEquals(GamepadProfile.XBOX, s.profile)
        assertEquals(null, s.autoConnectMac)
    }

    @Test
    fun `onHostDisconnected for a different mac while Connected is ignored`() {
        session.start(GamepadProfile.XBOX, null)
        fake.fireAcquired()
        fake.fireAppRegistered()
        fake.fireHostConnected("11:22", "Xbox")

        fake.fireHostDisconnected("OTHER")

        assertTrue(session.state.value is BluetoothSessionState.Connected)
    }
}
