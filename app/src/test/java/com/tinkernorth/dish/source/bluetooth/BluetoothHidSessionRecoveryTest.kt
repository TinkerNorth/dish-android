// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.core.input.BluetoothGamepad
import com.tinkernorth.dish.core.input.BluetoothGamepad.GamepadProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BluetoothHidSessionRecoveryTest {
    private lateinit var fakes: ArrayDeque<FakeHidProxyClient>
    private lateinit var session: BluetoothHidSession

    @Before
    fun setUp() {
        fakes = ArrayDeque()
        repeat(8) { fakes.add(FakeHidProxyClient()) }
        val supplier: () -> HidProxyClient = { fakes.removeFirst() }
        session = BluetoothHidSession(supplier)
    }

    @Test
    fun `start() while Connected releases the old proxy and acquires a brand-new one`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireAcquired()
        first.fireAppRegistered()
        first.fireHostConnected("AA", "Xbox")
        assertTrue(session.state.value is BluetoothSessionState.Connected)

        val second = fakes.first()
        session.start(GamepadProfile.PLAYSTATION, null)

        assertTrue(
            "outgoing proxy must be released on restart",
            first.calls.any { it is FakeHidProxyClient.Call.UnregisterAndRelease },
        )
        assertNotSame(first, second)
        assertTrue(second.calls.any { it is FakeHidProxyClient.Call.Acquire })
    }

    @Test
    fun `stop() from Connected releases the proxy and returns to Idle`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireAcquired()
        first.fireAppRegistered()
        first.fireHostConnected("AA", "X")

        session.stop()

        assertEquals(BluetoothSessionState.Idle, session.state.value)
        assertTrue(first.calls.any { it is FakeHidProxyClient.Call.UnregisterAndRelease })
    }

    @Test
    fun `stop() is idempotent from Idle`() {
        session.stop()
        session.stop()
        assertEquals(BluetoothSessionState.Idle, session.state.value)
        assertTrue(fakes.first().calls.isEmpty())
    }

    @Test
    fun `onReleased while non-Idle returns to Idle and does not re-acquire on its own`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, "AA")
        first.fireAcquired()
        first.fireAppRegistered()
        first.fireReleased()

        assertEquals(BluetoothSessionState.Idle, session.state.value)
        assertFalse(first.hasLiveEvents())
    }

    @Test
    fun `after onReleased, a subsequent start acquires a FRESH proxy (regression test)`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, "AA")
        first.fireAcquired()
        first.fireAppRegistered()
        first.fireReleased()

        val second = fakes.first()
        session.start(GamepadProfile.XBOX, "AA")

        assertNotSame("reconnect must bind a new proxy", first, second)
        val secondAcquire = second.calls.filterIsInstance<FakeHidProxyClient.Call.Acquire>()
        assertEquals(1, secondAcquire.size)

        second.fireAcquired()
        second.fireAppRegistered()
        second.fireHostConnected("AA", "Xbox")
        assertTrue(session.state.value is BluetoothSessionState.Connected)
    }

    @Test
    fun `events from a stale (released) proxy are ignored after restart`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireAcquired()
        first.fireAppRegistered()

        val second = fakes.first()
        session.start(GamepadProfile.PLAYSTATION, null)
        second.fireAcquired()
        second.fireAppRegistered()

        first.fireHostConnected("GHOST:MAC", "zombie")

        val s = session.state.value as BluetoothSessionState.Registered
        assertEquals(GamepadProfile.PLAYSTATION, s.profile)
    }

    @Test
    fun `onAppUnregistered while Connected returns to Idle and releases the proxy`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireAcquired()
        first.fireAppRegistered()
        first.fireHostConnected("AA", "X")

        first.fireAppUnregistered()

        assertEquals(BluetoothSessionState.Idle, session.state.value)
        assertTrue(first.calls.any { it is FakeHidProxyClient.Call.UnregisterAndRelease })
    }

    @Test
    fun `onError transitions to Failed and next start recovers cleanly`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireError("adapter disabled")

        assertTrue(session.state.value is BluetoothSessionState.Failed)
        assertEquals("adapter disabled", (session.state.value as BluetoothSessionState.Failed).message)

        val second = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        assertTrue(session.state.value is BluetoothSessionState.Acquiring)
        assertTrue(second.calls.any { it is FakeHidProxyClient.Call.Acquire })
    }
}
