// SPDX-License-Identifier: LGPL-3.0-or-later

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
            // 0x01 = ACK_ERR_BACKEND_UNAVAIL.
            every { repo.getLastControllerAck(any()) } returns 1

            var failureReason: String? = null
            conn.markConnecting()
            conn.markConnected(
                handle = 9,
                connectionId = "c",
                onRegistrationFailed = { failureReason = it },
            ) {}
            conn.attachSlot(slotId = "slot-1", controllerType = 0)

            assertEquals(false, conn.slots.value["slot-1"]?.registered)
            conn.sendReport("slot-1", buttons = 1, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)
            verify(exactly = 0) {
                repo.sendReport(any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
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

            coVerify {
                repo.addController(8, 0, match { (it and 0x0004) != 0 })
            }
        }

    @Test
    fun `controller add CLEARS CAP_MOTION when motionCapsBitsFor returns 0`() =
        runTest {
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

            coVerify {
                repo.addController(8, 0, match { (it and 0x0004) == 0 })
            }
            coVerify {
                repo.addController(8, 0, match { (it and 0x0001) != 0 && (it and 0x0002) != 0 })
            }

            noMotion.markDisconnected()
        }

    @Test
    fun `controller add SETS CAP_MOTION when motionCapsBitsFor returns the bit`() =
        runTest {
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

            assertTrue(conn.slots.value.containsKey("slot-1"))
        }

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
            conn.setControllerType("slot-A", controllerType = 1)

            verify { repo.sendControllerType(6, 0, 0) }
            verify { repo.sendControllerType(6, 0, 1) }
            assertEquals(1, conn.slots.value["slot-A"]?.controllerType)
        }

    @Test
    fun `setControllerType for an unregistered slot only updates local state`() =
        runTest {
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

            coVerify(exactly = 1) { repo.addController(1, 0, any()) }
            assertEquals(0, conn.slots.value["slot-A"]?.controllerType)
        }

    @Test
    fun `updateServer replaces server snapshot`() {
        val newServer = server.copy(name = "Renamed")
        conn.updateServer(newServer)
        assertEquals("Renamed", conn.server.value.name)
    }

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

    @Test
    fun `refreshCapsIfChanged sends a caps update when the lambda returns a new value`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            var capsBit = 0x0004
            val reactive =
                SatelliteConnection(
                    id = SatelliteConnection.idFor(server),
                    server = server,
                    scope = scope,
                    controllerRepo = repo,
                    motionCapsBitsFor = { capsBit },
                )
            reactive.markConnecting()
            reactive.markConnected(handle = 8, connectionId = "c") {}
            reactive.attachSlot(slotId = "slot-1", controllerType = 0)

            coVerify {
                repo.addController(8, 0, match { (it and 0x0004) != 0 })
            }

            capsBit = 0
            reactive.refreshCapsIfChanged()

            coVerify {
                repo.sendControllerCapsUpdate(
                    8,
                    0,
                    match { (it and 0x0004) == 0 && (it and 0x0001) != 0 && (it and 0x0002) != 0 },
                )
            }

            reactive.markDisconnected()
        }

    @Test
    fun `refreshCapsIfChanged is idempotent when the lambda hasn't moved`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            val steady =
                SatelliteConnection(
                    id = SatelliteConnection.idFor(server),
                    server = server,
                    scope = scope,
                    controllerRepo = repo,
                    motionCapsBitsFor = { 0x0004 },
                )
            steady.markConnecting()
            steady.markConnected(handle = 8, connectionId = "c") {}
            steady.attachSlot(slotId = "slot-1", controllerType = 0)

            steady.refreshCapsIfChanged()
            steady.refreshCapsIfChanged()
            steady.refreshCapsIfChanged()

            coVerify(exactly = 0) {
                repo.sendControllerCapsUpdate(any(), any(), any())
            }

            steady.markDisconnected()
        }

    @Test
    fun `refreshCapsIfChanged skips unregistered slots`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns -1

            var capsBit = 0x0004
            val pending =
                SatelliteConnection(
                    id = SatelliteConnection.idFor(server),
                    server = server,
                    scope = scope,
                    controllerRepo = repo,
                    motionCapsBitsFor = { capsBit },
                )
            pending.markConnecting()
            pending.markConnected(handle = 8, connectionId = "c") {}
            pending.attachSlot(slotId = "slot-pending", controllerType = 0)

            capsBit = 0
            pending.refreshCapsIfChanged()

            coVerify(exactly = 0) {
                repo.sendControllerCapsUpdate(any(), any(), any())
            }

            pending.markDisconnected()
        }

    @Test
    fun `refreshCapsIfChanged is a no-op when the session is idle`() =
        runTest {
            conn.refreshCapsIfChanged()
            coVerify(exactly = 0) {
                repo.sendControllerCapsUpdate(any(), any(), any())
            }
        }

    @Test
    fun `registration closes the race when composer emits new caps mid-handshake`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 0

            var capsBit = 0x0004
            var callCount = 0
            val race =
                SatelliteConnection(
                    id = SatelliteConnection.idFor(server),
                    server = server,
                    scope = scope,
                    controllerRepo = repo,
                    motionCapsBitsFor = {
                        callCount++
                        if (callCount == 1) capsBit else 0
                    },
                )
            race.markConnecting()
            race.markConnected(handle = 8, connectionId = "c") {}
            race.attachSlot(slotId = "slot-race", controllerType = 0)

            coVerify {
                repo.sendControllerCapsUpdate(
                    8,
                    0,
                    match { (it and 0x0004) == 0 },
                )
            }

            race.markDisconnected()
        }
}
