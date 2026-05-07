// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.bluetooth

import com.tinkernorth.dish.data.network.ConnectionStore
import com.tinkernorth.dish.data.network.RememberedBt
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepad.GamepadProfile
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for [BluetoothGamepadRegistry] on top of a real
 * [BluetoothHidSession] driven by a [FakeHidProxyClient]. These exercise the
 * full chain session-event → registry state without the Android framework so
 * regressions in the re-keying logic (temp id → bt:<MAC>) or the slot lifecycle
 * get caught by unit tests.
 */
class BluetoothGamepadRegistryTest {
    private lateinit var fake: FakeHidProxyClient
    private lateinit var session: BluetoothHidSession
    private lateinit var store: ConnectionStore
    private lateinit var registry: BluetoothGamepadRegistry

    @Before
    fun setUp() {
        fake = FakeHidProxyClient()
        session = BluetoothHidSession { fake }
        store =
            mockk(relaxed = true) {
                every { rememberedBt() } returns emptyList()
            }
        registry = BluetoothGamepadRegistry(store, session)
    }

    private fun driveToConnected(
        connId: String,
        mac: String,
        name: String?,
    ) {
        registry.start(connId, GamepadProfile.XBOX)
        fake.fireAcquired()
        fake.fireAppRegistered()
        fake.fireHostConnected(mac, name)
    }

    // ── Initial / lookup ──────────────────────────────────────────────────

    @Test
    fun `initial states map is empty`() {
        assertEquals(emptyMap<String, BluetoothGamepadRegistry.SlotState>(), registry.states.value)
        assertFalse(registry.isConnected("any"))
        assertEquals(BluetoothGamepadRegistry.SlotState(), registry.state("any"))
    }

    @Test
    fun `idFor derives bt-mac id`() {
        assertEquals("bt:AA:BB:CC", BluetoothGamepadRegistry.idFor("AA:BB:CC"))
    }

    // ── start() → Acquiring/Registered ────────────────────────────────────

    @Test
    fun `start seeds the slot with profile name and clears live flags`() {
        registry.start("bt-pending-1", GamepadProfile.PLAYSTATION)

        val s = registry.state("bt-pending-1")
        assertEquals("PlayStation", s.profileName)
        assertFalse(s.registered)
        assertFalse(s.connected)
        assertFalse(s.autoReconnecting)
    }

    // ── acquiring flag lifecycle ──────────────────────────────────────────

    @Test
    fun `start sets acquiring=true so the UI can show progress before Registered fires`() {
        // Prior to this flag the slot showed IDLE during the Acquiring phase
        // and the Connections row offered no feedback between profile-pick and
        // the host actually connecting.
        registry.start("bt-pending-1", GamepadProfile.XBOX)

        assertTrue(registry.state("bt-pending-1").acquiring)
    }

    @Test
    fun `onAppRegistered clears acquiring`() {
        registry.start("bt-pending-1", GamepadProfile.XBOX)
        fake.fireAcquired()
        fake.fireAppRegistered()

        assertFalse(registry.state("bt-pending-1").acquiring)
        assertTrue(registry.state("bt-pending-1").registered)
    }

    @Test
    fun `Connected clears acquiring on the re-keyed slot`() {
        registry.start("bt-pending-1", GamepadProfile.XBOX)
        fake.fireAcquired()
        // Skip Registered to confirm acquiring is cleared even on a direct
        // Connected (e.g. host already paired path).
        fake.fireHostConnected("AA:BB", "Xbox")

        assertFalse(registry.state("bt:AA:BB").acquiring)
    }

    @Test
    fun `Failed clears acquiring on the active slot`() {
        registry.start("bt-pending-1", GamepadProfile.XBOX)

        fake.fireError("HID profile unavailable")

        val s = registry.state("bt-pending-1")
        assertFalse(s.acquiring)
        assertFalse(s.registered)
        assertFalse(s.connected)
    }

    @Test
    fun `framework release clears acquiring along with the live flags`() {
        registry.start("bt-pending-1", GamepadProfile.XBOX)

        fake.fireReleased()

        assertFalse(registry.state("bt-pending-1").acquiring)
    }

    // ── errors flow ───────────────────────────────────────────────────────

    @Test
    fun `errors flow emits the message on Failed`() =
        runBlocking {
            registry.start("bt-pending-1", GamepadProfile.XBOX)

            val first = async { registry.errors.first() }
            // Yield so the collector subscribes before we emit; SharedFlow with a
            // 1-event extra buffer also covers the race, but the explicit yield
            // keeps intent obvious.
            kotlinx.coroutines.yield()
            fake.fireError("adapter disabled")

            assertEquals("adapter disabled", first.await())
        }

    @Test
    fun `errors flow does not emit on framework release`() =
        runBlocking {
            registry.start("bt-pending-1", GamepadProfile.XBOX)
            var emitted: String? = null
            val job = launch { registry.errors.collect { emitted = it } }

            fake.fireReleased()
            kotlinx.coroutines.yield()

            assertEquals(null, emitted)
            job.cancel()
        }

    @Test
    fun `start with autoConnectMac marks the slot autoReconnecting`() {
        registry.start("bt:AA", GamepadProfile.XBOX, autoConnectMac = "AA")

        assertTrue(registry.state("bt:AA").autoReconnecting)
        assertTrue(registry.isAutoReconnecting("bt:AA"))
    }

    @Test
    fun `onAppRegistered flips the active slot to registered=true`() {
        registry.start("bt-pending-1", GamepadProfile.XBOX)
        fake.fireAcquired()
        fake.fireAppRegistered()

        assertTrue(registry.state("bt-pending-1").registered)
        assertFalse(registry.state("bt-pending-1").connected)
    }

    // ── Re-keying on Connected ────────────────────────────────────────────

    @Test
    fun `Connected re-keys slot from temp id to bt-mac id and removes temp entry`() {
        registry.start("bt-pending-42", GamepadProfile.XBOX)
        fake.fireAcquired()
        fake.fireAppRegistered()

        fake.fireHostConnected("AA:BB:CC", "Xbox One")

        assertNull(registry.states.value["bt-pending-42"])
        val s = registry.state("bt:AA:BB:CC")
        assertTrue(s.connected)
        assertTrue(s.registered)
        assertEquals("Xbox One", s.connectedName)
        assertEquals("Xbox", s.profileName)
        assertFalse(s.autoReconnecting)
    }

    @Test
    fun `Connected persists the host under the stable id`() {
        registry.start("bt-pending-9", GamepadProfile.PLAYSTATION)
        fake.fireAcquired()
        fake.fireAppRegistered()

        val captured = slot<RememberedBt>()
        every { store.rememberBt(capture(captured)) } returns Unit

        fake.fireHostConnected("11:22:33", "PS5")

        assertEquals("bt:11:22:33", captured.captured.id)
        assertEquals("PS5", captured.captured.name)
        assertEquals("11:22:33", captured.captured.mac)
        assertEquals(GamepadProfile.PLAYSTATION.profileName, captured.captured.profileName)
    }

    @Test
    fun `Connected without a name falls back to the MAC address`() {
        registry.start("bt-pending-7", GamepadProfile.XBOX)
        fake.fireAcquired()
        fake.fireAppRegistered()

        val captured = slot<RememberedBt>()
        every { store.rememberBt(capture(captured)) } returns Unit

        fake.fireHostConnected("DE:AD:BE", name = null)

        assertEquals("DE:AD:BE", captured.captured.name)
        assertEquals("DE:AD:BE", registry.state("bt:DE:AD:BE").connectedName)
    }

    @Test
    fun `Connected with a stable connId does not churn the states map`() {
        registry.start("bt:AA", GamepadProfile.XBOX, autoConnectMac = "AA")
        fake.fireAcquired()
        fake.fireAppRegistered()

        fake.fireHostConnected("AA", "Xbox")

        assertEquals(1, registry.states.value.size)
        assertTrue(registry.state("bt:AA").connected)
    }

    // ── Disconnect / teardown ─────────────────────────────────────────────

    @Test
    fun `host disconnect clears connected flag but keeps the slot registered`() {
        driveToConnected("bt-pending-1", "AA:BB", "Xbox")

        fake.fireHostDisconnected("AA:BB")

        val s = registry.state("bt:AA:BB")
        assertFalse(s.connected)
        assertTrue(s.registered)
        assertNull(s.connectedName)
    }

    @Test
    fun `stop of the active slot stops the session and removes the slot`() {
        driveToConnected("bt-pending-1", "AA:BB", "Xbox")

        registry.stop("bt:AA:BB")

        assertNull(registry.states.value["bt:AA:BB"])
        assertTrue(fake.calls.any { it is FakeHidProxyClient.Call.UnregisterAndRelease })
    }

    @Test
    fun `stop of a non-active slot only evicts its state entry`() {
        driveToConnected("bt-pending-1", "AA:BB", "Xbox")
        val callsBefore = fake.calls.size

        registry.stop("bt:OTHER")

        assertEquals(callsBefore, fake.calls.size)
        assertTrue(registry.state("bt:AA:BB").connected)
    }

    @Test
    fun `starting a different slot evicts the prior slot entry`() {
        driveToConnected("bt-pending-1", "AA:BB", "Xbox")

        registry.start("bt-pending-2", GamepadProfile.PLAYSTATION)

        assertNull(registry.states.value["bt:AA:BB"])
        assertEquals("PlayStation", registry.state("bt-pending-2").profileName)
    }

    @Test
    fun `stopAll clears every slot and stops the session`() {
        driveToConnected("bt-pending-1", "AA:BB", "Xbox")

        registry.stopAll()

        assertTrue(registry.states.value.isEmpty())
        assertTrue(fake.calls.any { it is FakeHidProxyClient.Call.UnregisterAndRelease })
    }

    // ── Error & unexpected release ────────────────────────────────────────

    @Test
    fun `framework release clears the active slot flags`() {
        driveToConnected("bt-pending-1", "AA:BB", "Xbox")

        fake.fireReleased()

        val s = registry.state("bt:AA:BB")
        assertFalse(s.registered)
        assertFalse(s.connected)
    }

    @Test
    fun `session error clears autoReconnecting on the active slot`() {
        registry.start("bt:AA", GamepadProfile.XBOX, autoConnectMac = "AA")

        fake.fireError("boom")

        assertFalse(registry.state("bt:AA").autoReconnecting)
    }

    // ── sendReport gating ─────────────────────────────────────────────────

    @Test
    fun `sendReport is dropped when the slot is not connected`() {
        registry.start("bt-pending-1", GamepadProfile.XBOX)
        fake.fireAcquired()
        fake.fireAppRegistered()

        registry.sendReport("bt-pending-1", ByteArray(14))

        assertTrue(fake.calls.none { it is FakeHidProxyClient.Call.SendReport })
    }

    @Test
    fun `sendReport forwards to the session when the active slot is connected`() {
        driveToConnected("bt-pending-1", "AA:BB", "Xbox")

        registry.sendReport("bt:AA:BB", ByteArray(14) { 0x01 })

        assertTrue(fake.calls.any { it is FakeHidProxyClient.Call.SendReport })
    }

    @Test
    fun `sendReport on a non-active connId does not reach the session`() {
        driveToConnected("bt-pending-1", "AA:BB", "Xbox")
        val before = fake.calls.count { it is FakeHidProxyClient.Call.SendReport }

        registry.sendReport("bt:NOT_ACTIVE", ByteArray(14))

        val after = fake.calls.count { it is FakeHidProxyClient.Call.SendReport }
        assertEquals(before, after)
    }

    @Test
    fun `buildReport returns bytes only for the active connected slot`() {
        assertNull(registry.buildReport("bt:AA:BB", 0, 0, 0, 0, 0, 0, 0, 0))

        driveToConnected("bt-pending-1", "AA:BB", "Xbox")

        assertNull(registry.buildReport("bt:OTHER", 0, 0, 0, 0, 0, 0, 0, 0))
        val bytes = registry.buildReport("bt:AA:BB", 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(bytes != null && bytes.size == 14)
    }

    // ── tryAutoReconnect ──────────────────────────────────────────────────

    @Test
    fun `tryAutoReconnect returns null when the connId is not remembered`() {
        every { store.rememberedBt() } returns emptyList()

        assertNull(registry.tryAutoReconnect("bt:MISSING"))
    }

    @Test
    fun `tryAutoReconnect starts the session with the remembered profile and mac`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:AA", name = "Xbox", mac = "AA", profileName = "Xbox"),
            )

        val profile = registry.tryAutoReconnect("bt:AA")

        assertEquals(GamepadProfile.XBOX, profile)
        assertTrue(registry.state("bt:AA").autoReconnecting)
        assertTrue(fake.calls.any { it is FakeHidProxyClient.Call.Acquire })
    }

    @Test
    fun `tryAutoReconnect accepts the legacy enum-name persisted by older builds`() {
        // Builds before the fix stored "XBOX" / "PLAYSTATION" (enum names) in
        // RememberedBt.profileName. Tests guard the migration so an existing
        // remembered host on a user's device still auto-reconnects after the
        // upgrade without forcing a re-pair.
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:AA", name = "Xbox", mac = "AA", profileName = "XBOX"),
            )

        val profile = registry.tryAutoReconnect("bt:AA")

        assertEquals(GamepadProfile.XBOX, profile)
    }

    @Test
    fun `tryAutoReconnect is a no-op when the slot is already registered`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:AA", name = "Xbox", mac = "AA", profileName = "XBOX"),
            )
        registry.start("bt:AA", GamepadProfile.XBOX, autoConnectMac = "AA")
        fake.fireAcquired()
        fake.fireAppRegistered()
        val before = fake.calls.size

        registry.tryAutoReconnect("bt:AA")

        assertEquals(before, fake.calls.size)
    }

    @Test
    fun `tryAutoReconnect drops entries whose profileName no longer maps`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:AA", name = "Xbox", mac = "AA", profileName = "UNKNOWN"),
            )

        assertNull(registry.tryAutoReconnect("bt:AA"))
    }

    // ── Listener snapshot on state ────────────────────────────────────────

    @Test
    fun `states StateFlow reflects the latest registry snapshot`() {
        registry.start("bt-pending-1", GamepadProfile.XBOX)
        fake.fireAcquired()
        fake.fireAppRegistered()
        fake.fireHostConnected("AA", "Xbox")

        val snapshot = registry.states.value
        assertEquals(1, snapshot.size)
        assertEquals(true, snapshot["bt:AA"]?.connected)
    }

    @Test
    fun `store rememberBt is not called on an unexpected early Connected before start`() {
        fake.fireHostConnected("AA", "X")
        verify(exactly = 0) { store.rememberBt(any()) }
    }
}
