package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.repository.ControllerRepository
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
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [WifiConnection] state transitions and gating of
 * [WifiConnection.sendReport] on connection state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiConnectionTest {
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
    private lateinit var conn: WifiConnection

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        scope = TestScope(StandardTestDispatcher())
        conn =
            WifiConnection(
                id = WifiConnection.idFor(server),
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
        assertEquals("wifi:10.0.0.5:9876", WifiConnection.idFor(server))
    }

    @Test
    fun `initial state is IDLE with no handle or connectionId`() {
        assertEquals(WifiState.IDLE, conn.state.value)
        assertEquals(-1, conn.handle)
        assertNull(conn.connectionId)
    }

    @Test
    fun `markConnecting advances state to CONNECTING`() {
        conn.markConnecting()
        assertEquals(WifiState.CONNECTING, conn.state.value)
    }

    @Test
    fun `markConnected stores handle, id and starts heartbeat + ack reset`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns true

            conn.markConnecting()
            conn.markConnected(handle = 7, connectionId = "conn-abc") {}

            assertEquals(WifiState.CONNECTED, conn.state.value)
            assertEquals(7, conn.handle)
            assertEquals("conn-abc", conn.connectionId)
            verify { repo.resetControllerAck(7) }
            verify { repo.startHeartbeat(7) }
        }

    @Test
    fun `markDisconnected tears down heartbeat and clears handle`() {
        every { repo.resetControllerAck(any()) } just Runs
        every { repo.startHeartbeat(any()) } just Runs
        every { repo.stopHeartbeat(any()) } just Runs
        every { repo.closeSocket(any()) } just Runs
        every { repo.isConnectionAlive(any()) } returns true

        conn.markConnecting()
        conn.markConnected(handle = 3, connectionId = "c") {}
        conn.markDisconnected()

        assertEquals(WifiState.IDLE, conn.state.value)
        assertEquals(-1, conn.handle)
        assertNull(conn.connectionId)
        verify { repo.stopHeartbeat(3) }
        verify { repo.closeSocket(3) }
    }

    @Test
    fun `sendReport is a no-op when state is IDLE`() {
        conn.sendReport(buttons = 1, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)
        verify(exactly = 0) { repo.sendReport(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendReport forwards to repository when CONNECTED`() {
        every { repo.resetControllerAck(any()) } just Runs
        every { repo.startHeartbeat(any()) } just Runs
        every { repo.isConnectionAlive(any()) } returns true
        conn.markConnecting()
        conn.markConnected(handle = 9, connectionId = "c") {}

        conn.sendReport(buttons = 0x42, lt = 10, rt = 20, lx = 30, ly = -40, rx = 50, ry = -60)

        verify { repo.sendReport(9, 0, 0x42, 10, 20, 30, -40, 50, -60) }
    }

    @Test
    fun `detachSlot without prior attach is a no-op`() {
        conn.detachSlot()
        assertNull(conn.boundSlotId.value)
        verify(exactly = 0) { repo.removeController(any(), any()) }
    }

    @Test
    fun `attachSlot records the binding even when not connected`() =
        runTest {
            conn.attachSlot(slotId = "slot-1", controllerType = 0)
            assertEquals("slot-1", conn.boundSlotId.value)
            // Server-side registration is deferred until markConnected runs.
            coVerify(exactly = 0) { repo.addController(any(), any(), any()) }
        }

    @Test
    fun `attachSlot when already connected registers controller with server`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false
            every { repo.getLastControllerAck(any()) } returns 1

            conn.markConnecting()
            conn.markConnected(handle = 8, connectionId = "c") {}
            conn.attachSlot(slotId = "slot-1", controllerType = 0)

            coVerify { repo.addController(8, 0, any()) }
            verify { repo.sendControllerType(8, 0, 0) }
        }

    @Test
    fun `markConnected preserves a slot binding recorded before connect`() =
        runTest {
            every { repo.resetControllerAck(any()) } just Runs
            every { repo.startHeartbeat(any()) } just Runs
            every { repo.isConnectionAlive(any()) } returns false

            conn.attachSlot(slotId = "slot-1", controllerType = 0)
            assertEquals("slot-1", conn.boundSlotId.value)
            conn.markConnecting()
            conn.markConnected(handle = 5, connectionId = "c") {}

            assertEquals("slot-1", conn.boundSlotId.value)
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

        assertEquals(WifiState.IDLE, conn.state.value)
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

        assertEquals(WifiState.CONNECTED, conn.state.value)
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

        assertEquals(WifiState.CONNECTED, conn.state.value)
        assertEquals(4, conn.handle)
    }

    @Test
    fun `markDisconnected from IDLE is a no-op and touches no native handles`() {
        conn.markDisconnected()

        assertEquals(WifiState.IDLE, conn.state.value)
        verify(exactly = 0) { repo.stopHeartbeat(any()) }
        verify(exactly = 0) { repo.closeSocket(any()) }
    }
}
