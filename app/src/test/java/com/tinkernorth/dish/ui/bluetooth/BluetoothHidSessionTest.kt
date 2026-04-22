package com.tinkernorth.dish.ui.bluetooth

import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad.GamepadProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FSM contract tests for [BluetoothHidSession]. The session owns at most one
 * HID registration at a time (Android's HID Device API constraint) and must
 * be safely re-enterable across the app's foreground/background transitions.
 *
 * Each test drives the FSM through a concrete sequence of framework events
 * using a [FakeHidProxyClient], and asserts both the emitted [SessionState]
 * and the exact sequence of proxy calls. Proxy call order matters — the
 * original bug was caused by skipping `unregisterAndRelease` on re-start.
 */
class BluetoothHidSessionTest {
    private lateinit var fake: FakeHidProxyClient
    private lateinit var session: BluetoothHidSession

    @Before
    fun setUp() {
        fake = FakeHidProxyClient()
        session = BluetoothHidSession { fake }
    }

    // ── Initial state ─────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() {
        assertEquals(SessionState.Idle, session.state.value)
        assertTrue(fake.calls.isEmpty())
    }

    // ── start() → Acquiring → Registered ──────────────────────────────────

    @Test
    fun `start from Idle moves to Acquiring and acquires a fresh proxy`() {
        session.start(GamepadProfile.XBOX, autoConnectMac = null)

        val s = session.state.value as SessionState.Acquiring
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
        // Still Acquiring — registration is not yet confirmed.
        assertTrue(session.state.value is SessionState.Acquiring)
    }

    @Test
    fun `onAppRegistered without autoConnectMac moves to Registered and does not auto-connect`() {
        session.start(GamepadProfile.XBOX, null)
        fake.fireAcquired()
        fake.fireAppRegistered()

        assertTrue(session.state.value is SessionState.Registered)
        assertTrue(fake.calls.none { it is FakeHidProxyClient.Call.ConnectToHost })
    }

    @Test
    fun `onAppRegistered with autoConnectMac requests a connect when host is not already connected at OS level`() {
        session.start(GamepadProfile.XBOX, autoConnectMac = "AA:BB")
        fake.fireAcquired()
        fake.fireAppRegistered()

        val call = fake.calls.filterIsInstance<FakeHidProxyClient.Call.ConnectToHost>().single()
        assertEquals("AA:BB", call.mac)
        assertTrue(session.state.value is SessionState.Registered)
    }

    @Test
    fun `onAppRegistered with autoConnectMac skips ConnectToHost when OS already has the host connected and jumps to Connected`() {
        fake.osConnectedHosts["AA:BB"] = "Living Room TV"
        session.start(GamepadProfile.XBOX, autoConnectMac = "AA:BB")
        fake.fireAcquired()
        fake.fireAppRegistered()

        assertTrue(fake.calls.none { it is FakeHidProxyClient.Call.ConnectToHost })
        val s = session.state.value as SessionState.Connected
        assertEquals("AA:BB", s.mac)
        assertEquals("Living Room TV", s.name)
    }

    // ── Connect / disconnect from Registered ──────────────────────────────

    @Test
    fun `onHostConnected from Registered transitions to Connected with mac and name`() {
        session.start(GamepadProfile.XBOX, null)
        fake.fireAcquired()
        fake.fireAppRegistered()

        fake.fireHostConnected("11:22", "Xbox One")

        val s = session.state.value as SessionState.Connected
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

        val s = session.state.value as SessionState.Registered
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

        assertTrue(session.state.value is SessionState.Connected)
    }
}
