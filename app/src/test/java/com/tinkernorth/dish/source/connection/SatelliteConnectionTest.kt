// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.DiscoveredServer
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SatelliteConnection] state transitions, multi-slot binding
 * and gating of [SatelliteConnection.sendReport] on connection state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteConnectionTest {
    private val server =
        DiscoveredServer(
            name = "Pc",
            ip = "10.0.0.5",
            udpPort = 9876,
            pairPort = 9878,
            httpPort = 9877,
        )
    private lateinit var repo: ControllerRepository
    private lateinit var scope: TestScope
    private lateinit var conn: SatelliteConnection

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        scope = TestScope(StandardTestDispatcher())
        conn =
            SatelliteConnection(
                id = SatelliteConnection.idFor(server),
                server = server,
                scope = scope,
                controllerRepo = repo,
            )
    }

    @After
    fun tearDown() {
        // Cancel any background loops started by markConnected so runTest exits.
        conn.markDisconnected()
    }

    @Test
    fun `idFor derives stable id from ip and udp port`() {
        assertEquals("satellite:10.0.0.5:9876", SatelliteConnection.idFor(server))
    }

    @Test
    fun `initial state is IDLE with no handle, connectionId or slots`() {
        assertEquals(SatelliteSessionState.Idle, conn.state.value)
        assertEquals(-1, conn.handle)
        assertNull(conn.connectionId)
        assertTrue(conn.slots.value.isEmpty())
    }

    @Test
    fun `markConnecting advances state to CONNECTING`() {
        conn.markConnecting()
        assertEquals(SatelliteSessionState.Linking, conn.state.value)
    }

    @Test
    fun `markConnected stores handle, id and starts heartbeat + ack reset`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns true

            conn.markConnecting()
            conn.markConnected(handle = 7, connectionId = "conn-abc") {}

            assertEquals(SatelliteSessionState.Live, conn.state.value)
            assertEquals(7, conn.handle)
            assertEquals("conn-abc", conn.connectionId)
            verify { repo.resetControllerAck(7) }
            verify { repo.startHeartbeat(7) }
        }

    @Test
    fun `markDisconnected tears down heartbeat, clears handle and unregisters slots`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.stopHeartbeat(any()) } just Runs
            every { repo.closeSocket(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns true
            every { repo.getLastControllerAck(any()) } returns 0

            conn.markConnecting()
            conn.markConnected(handle = 3, connectionId = "c") {}
            conn.attachSlot(slotId = "slot-1", controllerType = 0)
            conn.markDisconnected()

            assertEquals(SatelliteSessionState.Idle, conn.state.value)
            assertEquals(-1, conn.handle)
            assertNull(conn.connectionId)
            verify { repo.stopHeartbeat(3) }
            verify { repo.closeSocket(3) }
            // Slot is preserved across disconnect (so reconnect re-registers it)
            // but its registered flag is cleared.
            assertEquals(false, conn.slots.value["slot-1"]?.registered)
        }

    @Test
    fun `sendReport is a no-op when state is IDLE`() {
        conn.sendReport(slotId = "slot-1", buttons = 1, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)
        verify(exactly = 0) { repo.sendReport(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendReport forwards to repository when CONNECTED and controller is registered`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0
            conn.markConnecting()
            conn.markConnected(handle = 9, connectionId = "c") {}
            // attachSlot triggers registerController which sets registered=true;
            // sendReport gates on it so reports during the addController ACK
            // window don't leak.
            conn.attachSlot(slotId = "slot-1", controllerType = 0)

            conn.sendReport(slotId = "slot-1", buttons = 0x42, lt = 10, rt = 20, lx = 30, ly = -40, rx = 50, ry = -60)

            verify { repo.sendReport(9, 0, 0x42, 10, 20, 30, -40, 50, -60) }
        }

    @Test
    fun `sendReport for an unknown slot is dropped`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0
            conn.markConnecting()
            conn.markConnected(handle = 9, connectionId = "c") {}
            conn.attachSlot(slotId = "slot-1", controllerType = 0)

            conn.sendReport(slotId = "ghost", buttons = 1, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)

            verify(exactly = 0) {
                repo.sendReport(any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `sendReport is dropped while controller registration is still pending`() {
        every { repo.resetControllerAck(any()) } just Runs
        every { repo.startHeartbeat(any()) } just Runs
        every { repo.isConnectionAlive(any()) } returns true
        conn.markConnecting()
        conn.markConnected(handle = 9, connectionId = "c") {}
        // No attachSlot → no registerController → registered stays false.
        conn.sendReport(slotId = "slot-1", buttons = 0x42, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)

        verify(exactly = 0) {
            repo.sendReport(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `detachSlot for an unknown slot is a no-op`() {
        conn.detachSlot("ghost")
        verify(exactly = 0) { repo.removeController(any(), any()) }
    }

    @Test
    fun `attachSlot records the binding even when not connected`() =
        runTest {
            conn.attachSlot(slotId = "slot-1", controllerType = 0)
            assertEquals(0, conn.slots.value["slot-1"]?.controllerIndex)
            assertEquals(false, conn.slots.value["slot-1"]?.registered)
            // Server-side registration is deferred until markConnected runs.
            coVerify(exactly = 0) { repo.addController(any(), any(), any()) }
        }

    @Test
    fun `attachSlot when already connected registers controller with server`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            conn.markConnecting()
            conn.markConnected(handle = 8, connectionId = "c") {}
            conn.attachSlot(slotId = "slot-1", controllerType = 0)

            coVerify { repo.addController(8, 0, any()) }
            verify { repo.sendControllerType(8, 0, 0) }
            assertEquals(true, conn.slots.value["slot-1"]?.registered)
        }

    @Test
    fun `an error ACK fails registration so reports stay gated and the reason surfaces`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            // Low byte 0x01 = ACK_ERR_BACKEND_UNAVAIL — a satellite with no
            // virtual-gamepad backend (e.g. macOS). The dish must treat this as
            // a failed registration, not — as it once did — silently as success.
            every { repo.getLastControllerAck(any()) } returns 1

            var failureReason: String? = null
            conn.markConnecting()
            conn.markConnected(
                handle = 9,
                connectionId = "c",
                onRegistrationFailed = { failureReason = it },
            ) {}
            conn.attachSlot(slotId = "slot-1", controllerType = 0)

            // An error ACK must not register the slot, so sendReport() stays
            // gated — no input is streamed to a controller the satellite
            // rejected.
            assertEquals(false, conn.slots.value["slot-1"]?.registered)
            conn.sendReport("slot-1", buttons = 1, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)
            verify(exactly = 0) {
                repo.sendReport(any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
            // ...and the rejection reason reaches the caller for display.
            assertTrue(failureReason?.contains("backend", ignoreCase = true) == true)
        }

    @Test
    fun `controller add advertises the motion capability bit`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            conn.markConnecting()
            conn.markConnected(handle = 8, connectionId = "c") {}
            conn.attachSlot(slotId = "slot-1", controllerType = 0)

            // Post-Task-1.1 builds must set CAP_MOTION (0x0004) in the
            // MSG_CONTROLLER_ADD capability word so the server knows to expect
            // the MSG_MOTION IMU stream from this client. Default-arg
            // motionCapsBitsFor returns CAP_MOTION_BIT_LEGACY, exercising the
            // pre-composer behaviour.
            coVerify {
                repo.addController(8, 0, match { (it and 0x0004) != 0 })
            }
        }

    @Test
    fun `controller add CLEARS CAP_MOTION when motionCapsBitsFor returns 0`() =
        runTest {
            // The headline PR3 fix: when the capability composer says "this
            // slot has no gyro" OR "the user toggled motion off," the cap
            // word on the wire must drop CAP_MOTION so the receiver's web
            // UI is honest about which slots stream motion. A regression
            // here is the existing bug (every controller silently advertises
            // CAP_MOTION).
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            val noMotion =
                SatelliteConnection(
                    id = SatelliteConnection.idFor(server),
                    server = server,
                    scope = scope,
                    controllerRepo = repo,
                    motionCapsBitsFor = { 0 },
                )
            noMotion.markConnecting()
            noMotion.markConnected(handle = 8, connectionId = "c") {}
            noMotion.attachSlot(slotId = "slot-1", controllerType = 0)

            // Motion bit absent.
            coVerify {
                repo.addController(8, 0, match { (it and 0x0004) == 0 })
            }
            // Non-motion bits (analog triggers 0x0001 + rumble 0x0002) still set —
            // the lambda only controls the motion bit, not the whole word.
            coVerify {
                repo.addController(8, 0, match { (it and 0x0001) != 0 && (it and 0x0002) != 0 })
            }

            noMotion.markDisconnected()
        }

    @Test
    fun `controller add SETS CAP_MOTION when motionCapsBitsFor returns the bit`() =
        runTest {
            // The other side of the truth table: when the slot has motion
            // (gyro present + user-enabled), the cap word must include
            // CAP_MOTION. Pin the explicit-bit path (no default-arg
            // accident).
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            val withMotion =
                SatelliteConnection(
                    id = SatelliteConnection.idFor(server),
                    server = server,
                    scope = scope,
                    controllerRepo = repo,
                    motionCapsBitsFor = { 0x0004 },
                )
            withMotion.markConnecting()
            withMotion.markConnected(handle = 8, connectionId = "c") {}
            withMotion.attachSlot(slotId = "slot-1", controllerType = 0)

            coVerify {
                repo.addController(8, 0, match { (it and 0x0004) != 0 })
            }
            withMotion.markDisconnected()
        }

    @Test
    fun `motionCapsBitsFor is called with the SAME slotId being registered`() =
        runTest {
            // Per-slot resolution is the whole point — two slots with
            // different toggles must each see their own slotId. A regression
            // that hardcodes a single slotId would silently apply slot-A's
            // toggle to slot-B (or worse, return random results).
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            val askedFor = mutableListOf<String>()
            val perSlot =
                SatelliteConnection(
                    id = SatelliteConnection.idFor(server),
                    server = server,
                    scope = scope,
                    controllerRepo = repo,
                    motionCapsBitsFor = { slot ->
                        askedFor += slot
                        if (slot == "slot-A") 0x0004 else 0
                    },
                )
            perSlot.markConnecting()
            perSlot.markConnected(handle = 8, connectionId = "c") {}
            perSlot.attachSlot("slot-A", controllerType = 0)
            perSlot.attachSlot("slot-B", controllerType = 0)

            assertTrue(askedFor.contains("slot-A"))
            assertTrue(askedFor.contains("slot-B"))
            coVerify { repo.addController(8, 0, match { (it and 0x0004) != 0 }) }
            coVerify { repo.addController(8, 1, match { (it and 0x0004) == 0 }) }
            perSlot.markDisconnected()
        }

    @Test
    fun `markConnected preserves slot bindings recorded before connect`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            conn.attachSlot(slotId = "slot-1", controllerType = 0)
            assertTrue(conn.slots.value.containsKey("slot-1"))
            conn.markConnecting()
            conn.markConnected(handle = 5, connectionId = "c") {}

            // Slot survives the IDLE → CONNECTED transition; registration is
            // dispatched via scope.launch into the connection's scope, not the
            // runTest scope, so we don't assert the addController call here —
            // see "attachSlot when already connected" for the registration
            // contract.
            assertTrue(conn.slots.value.containsKey("slot-1"))
        }

    // ── Multi-slot ──────────────────────────────────────────────────────────

    @Test
    fun `attaching a second slot allocates a fresh controller index`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            conn.markConnecting()
            conn.markConnected(handle = 4, connectionId = "c") {}
            conn.attachSlot("slot-A", controllerType = 0)
            conn.attachSlot("slot-B", controllerType = 0)

            assertEquals(0, conn.slots.value["slot-A"]?.controllerIndex)
            assertEquals(1, conn.slots.value["slot-B"]?.controllerIndex)
            coVerify { repo.addController(4, 0, any()) }
            coVerify { repo.addController(4, 1, any()) }
        }

    @Test
    fun `sendReport routes to the attached slot's controller index`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            conn.markConnecting()
            conn.markConnected(handle = 11, connectionId = "c") {}
            conn.attachSlot("slot-A", controllerType = 0)
            conn.attachSlot("slot-B", controllerType = 0)

            conn.sendReport("slot-B", buttons = 1, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)

            verify { repo.sendReport(11, 1, 1, 0, 0, 0, 0, 0, 0) }
        }

    @Test
    fun `detaching one slot frees its index for the next attach`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0
            every { repo.removeController(any(), any()) } just Runs

            conn.markConnecting()
            conn.markConnected(handle = 2, connectionId = "c") {}
            conn.attachSlot("slot-A", controllerType = 0)
            conn.attachSlot("slot-B", controllerType = 0)

            conn.detachSlot("slot-A")
            verify { repo.removeController(2, 0) }
            assertNull(conn.slots.value["slot-A"])

            conn.attachSlot("slot-C", controllerType = 0)
            // index 0 freed by the detach is reclaimed before allocating a new one.
            assertEquals(0, conn.slots.value["slot-C"]?.controllerIndex)
        }

    @Test
    fun `setControllerType pushes to the server when slot is registered`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            conn.markConnecting()
            conn.markConnected(handle = 6, connectionId = "c") {}
            conn.attachSlot("slot-A", controllerType = 0)
            // Initial register pushed type=0; the change pushes type=1.
            conn.setControllerType("slot-A", controllerType = 1)

            verify { repo.sendControllerType(6, 0, 0) }
            verify { repo.sendControllerType(6, 0, 1) }
            assertEquals(1, conn.slots.value["slot-A"]?.controllerType)
        }

    @Test
    fun `setControllerType for an unregistered slot only updates local state`() =
        runTest {
            // Not connected, so attachSlot stashes locally without registering.
            conn.attachSlot("slot-A", controllerType = 0)
            conn.setControllerType("slot-A", controllerType = 1)

            assertEquals(1, conn.slots.value["slot-A"]?.controllerType)
            verify(exactly = 0) { repo.sendControllerType(any(), any(), any()) }
        }

    @Test
    fun `setControllerType for an unknown slot is a no-op`() =
        runTest {
            conn.setControllerType("ghost", controllerType = 1)
            verify(exactly = 0) { repo.sendControllerType(any(), any(), any()) }
            assertTrue(conn.slots.value.isEmpty())
        }

    @Test
    fun `attachSlot for an already-attached slot does not re-register`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            conn.markConnecting()
            conn.markConnected(handle = 1, connectionId = "c") {}
            conn.attachSlot("slot-A", controllerType = 0)
            conn.attachSlot("slot-A", controllerType = 1)

            // Only the first registration runs; setControllerType is the path
            // for changing type on an attached slot.
            coVerify(exactly = 1) { repo.addController(1, 0, any()) }
            // Type stays at the initial value because attachSlot is idempotent.
            assertEquals(0, conn.slots.value["slot-A"]?.controllerType)
        }

    @Test
    fun `updateServer replaces server snapshot`() {
        val newServer = server.copy(name = "Renamed")
        conn.updateServer(newServer)
        assertEquals("Renamed", conn.server.value.name)
    }

    // ── Transition guards ───────────────────────────────────────────────────

    @Test
    fun `markConnected from IDLE is rejected and leaves state IDLE`() {
        conn.markConnected(handle = 11, connectionId = "c") {}

        assertEquals(SatelliteSessionState.Idle, conn.state.value)
        assertEquals(-1, conn.handle)
        assertNull(conn.connectionId)
        verify(exactly = 0) { repo.resetControllerAck(any()) }
        verify(exactly = 0) { repo.startHeartbeat(any()) }
    }

    @Test
    fun `second markConnected while already CONNECTED preserves original handle`() {
        every { repo.resetControllerAck(any()) } just Runs
        every { repo.startHeartbeat(any()) } just Runs
        every { repo.isConnectionAlive(any()) } returns true

        conn.markConnecting()
        conn.markConnected(handle = 1, connectionId = "first") {}
        // A second attempt without going through disconnect must be rejected
        // so we don't leak the first session's native handle.
        conn.markConnected(handle = 2, connectionId = "second") {}

        assertEquals(SatelliteSessionState.Live, conn.state.value)
        assertEquals(1, conn.handle)
        assertEquals("first", conn.connectionId)
        verify(exactly = 1) { repo.resetControllerAck(any()) }
        verify(exactly = 1) { repo.startHeartbeat(any()) }
    }

    @Test
    fun `markConnecting from CONNECTED is ignored so a live session isn't demoted`() {
        every { repo.resetControllerAck(any()) } just Runs
        every { repo.startHeartbeat(any()) } just Runs
        every { repo.isConnectionAlive(any()) } returns true

        conn.markConnecting()
        conn.markConnected(handle = 4, connectionId = "c") {}
        conn.markConnecting()

        assertEquals(SatelliteSessionState.Live, conn.state.value)
        assertEquals(4, conn.handle)
    }

    @Test
    fun `markDisconnected from IDLE is a no-op and touches no native handles`() {
        conn.markDisconnected()

        assertEquals(SatelliteSessionState.Idle, conn.state.value)
        verify(exactly = 0) { repo.stopHeartbeat(any()) }
        verify(exactly = 0) { repo.closeSocket(any()) }
    }
}
