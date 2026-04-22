package com.tinkernorth.dish.ui.bluetooth

import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad.GamepadProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the "background → return → reconnect is dead" bug.
 *
 * The root cause was that the old [BluetoothGamepad.stop] short-circuited
 * when the profile proxy had already been released by the OS, leaving the
 * HID app registration lingering in the framework. The next `start()` then
 * tried to re-bind on top of that stale registration and never saw the
 * `onAppStatusChanged(true)` callback.
 *
 * The contract pinned here:
 *   - Every teardown (`stop()`, `start()` on a non-Idle session,
 *     `onReleased` while non-Idle) MUST call `unregisterAndRelease` on
 *     the outgoing proxy.
 *   - A fresh proxy is acquired on every restart — the session must never
 *     reuse a proxy whose framework binding has been cleared.
 *   - Callbacks from a released proxy must be ignored and never mutate the
 *     current session's state.
 */
class BluetoothHidSessionRecoveryTest {

    private lateinit var fakes: ArrayDeque<FakeHidProxyClient>
    private lateinit var session: BluetoothHidSession

    @Before
    fun setUp() {
        fakes = ArrayDeque()
        // Pre-seed a deep stack; each start() pops a fresh fake.
        repeat(8) { fakes.add(FakeHidProxyClient()) }
        val supplier: () -> HidProxyClient = { fakes.removeFirst() }
        session = BluetoothHidSession(supplier)
    }

    @Test
    fun `start() while Connected releases the old proxy and acquires a brand-new one`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireAcquired(); first.fireAppRegistered(); first.fireHostConnected("AA", "Xbox")
        assertTrue(session.state.value is SessionState.Connected)

        val second = fakes.first()
        session.start(GamepadProfile.PLAYSTATION, null)

        // Old proxy was released.
        assertTrue(
            "outgoing proxy must be released on restart",
            first.calls.any { it is FakeHidProxyClient.Call.UnregisterAndRelease },
        )
        // New proxy is a different instance and was acquired.
        assertNotSame(first, second)
        assertTrue(second.calls.any { it is FakeHidProxyClient.Call.Acquire })
    }

    @Test
    fun `stop() from Connected releases the proxy and returns to Idle`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireAcquired(); first.fireAppRegistered(); first.fireHostConnected("AA", "X")

        session.stop()

        assertEquals(SessionState.Idle, session.state.value)
        assertTrue(first.calls.any { it is FakeHidProxyClient.Call.UnregisterAndRelease })
    }

    @Test
    fun `stop() is idempotent from Idle`() {
        session.stop()
        session.stop()
        assertEquals(SessionState.Idle, session.state.value)
        assertTrue(fakes.first().calls.isEmpty())
    }

    @Test
    fun `onReleased while non-Idle returns to Idle and does not re-acquire on its own`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, "AA")
        first.fireAcquired(); first.fireAppRegistered()
        // Framework yanks the proxy (backgrounding, BT toggle, OEM freeze).
        first.fireReleased()

        assertEquals(SessionState.Idle, session.state.value)
        assertFalse(first.hasLiveEvents())
    }

    @Test
    fun `after onReleased, a subsequent start acquires a FRESH proxy (regression test)`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, "AA")
        first.fireAcquired(); first.fireAppRegistered()
        first.fireReleased()

        // This is exactly the "user taps Reconnect after returning from bg".
        val second = fakes.first()
        session.start(GamepadProfile.XBOX, "AA")

        assertNotSame("reconnect must bind a new proxy", first, second)
        val secondAcquire = second.calls.filterIsInstance<FakeHidProxyClient.Call.Acquire>()
        assertEquals(1, secondAcquire.size)

        // Drive the new proxy through to Connected to prove the bug is gone.
        second.fireAcquired(); second.fireAppRegistered(); second.fireHostConnected("AA", "Xbox")
        assertTrue(session.state.value is SessionState.Connected)
    }

    @Test
    fun `events from a stale (released) proxy are ignored after restart`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireAcquired(); first.fireAppRegistered()

        val second = fakes.first()
        session.start(GamepadProfile.PLAYSTATION, null)
        second.fireAcquired(); second.fireAppRegistered()

        // A late callback from the first (already released) proxy arrives.
        first.fireHostConnected("GHOST:MAC", "zombie")

        // Session is still in the Registered state for the new profile.
        val s = session.state.value as SessionState.Registered
        assertEquals(GamepadProfile.PLAYSTATION, s.profile)
    }

    @Test
    fun `onAppUnregistered while Connected returns to Idle and releases the proxy`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireAcquired(); first.fireAppRegistered(); first.fireHostConnected("AA", "X")

        first.fireAppUnregistered()

        assertEquals(SessionState.Idle, session.state.value)
        assertTrue(first.calls.any { it is FakeHidProxyClient.Call.UnregisterAndRelease })
    }

    @Test
    fun `onError transitions to Failed and next start recovers cleanly`() {
        val first = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        first.fireError("adapter disabled")

        assertTrue(session.state.value is SessionState.Failed)
        assertEquals("adapter disabled", (session.state.value as SessionState.Failed).message)

        val second = fakes.first()
        session.start(GamepadProfile.XBOX, null)
        assertTrue(session.state.value is SessionState.Acquiring)
        assertTrue(second.calls.any { it is FakeHidProxyClient.Call.Acquire })
    }
}
