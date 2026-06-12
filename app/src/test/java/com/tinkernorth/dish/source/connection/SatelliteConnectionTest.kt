// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.ControllerApplyDto
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.HostFeatureGrantDto
import com.tinkernorth.dish.core.model.HostFeaturesDto
import com.tinkernorth.dish.core.model.SessionMotionDto
import com.tinkernorth.dish.core.model.SessionViewControllerDto
import com.tinkernorth.dish.core.model.SessionViewDto
import com.tinkernorth.dish.core.net.ControllerDescriptor
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            pairPort = 9443,
            httpPort = 9443,
            machineId = "abc123",
        )
    private lateinit var repo: ControllerRepository
    private lateinit var scope: TestScope
    private lateinit var conn: SatelliteConnection
    private val slotSyncs = mutableListOf<String>()
    private val slotRemovals = mutableListOf<Int>()

    private fun okApply(
        ctrlIdx: Int,
        appliedType: Int = 0,
        sinkSupported: Boolean = false,
        backendOk: Boolean = true,
    ) = ControllerApplyDto(
        ctrlIdx = ctrlIdx,
        result = ControllerApplyDto.APPLY_OK,
        appliedType = appliedType,
        motion = SessionMotionDto(sinkSupportedForType = sinkSupported, backendOk = backendOk),
    )

    private fun failedApply(
        ctrlIdx: Int,
        result: String = "backendUnavailable",
    ) = ControllerApplyDto(ctrlIdx = ctrlIdx, result = result)

    private fun newConnection(
        motionCapsBitsFor: (String) -> Int = { ControllerDescriptor.CAP_MOTION },
        store: SatelliteMotionBackendStatusStore? = null,
    ): SatelliteConnection =
        SatelliteConnection(
            id = SatelliteConnection.idFor(server),
            server = server,
            scope = scope,
            controllerRepo = repo,
            motionCapsBitsFor = motionCapsBitsFor,
            motionBackendStatusStore = store,
            onSlotChanged = { slotSyncs += it },
            onSlotRemoved = { slotRemovals += it },
        )

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        // -1 = dead socket so the RX-drain loop exits after one call (the
        // mock has no real socket to block on).
        every { repo.receiveAck(any()) } returns -1
        // No enriched ack / close-notify seen unless a test says otherwise.
        every { repo.getServerEpoch(any()) } returns -1
        every { repo.getActiveBitmap(any()) } returns -1
        every { repo.getSessionCloseReason(any()) } returns -1
        scope = TestScope(StandardTestDispatcher())
        slotSyncs.clear()
        slotRemovals.clear()
        conn = newConnection()
    }

    @After
    fun tearDown() {
        conn.markDisconnected()
        scope.cancel()
        clearAllMocks()
    }

    private fun connectLive(
        target: SatelliteConnection = conn,
        handle: Int = 7,
        epoch: Int = 1,
        applied: List<ControllerApplyDto> = emptyList(),
        mouseControlGranted: Boolean = false,
        onDead: () -> Unit = {},
        onClosedByServer: (Int) -> Unit = {},
        onReconcileNeeded: () -> Unit = {},
        onApplyFailures: (List<ControllerApplyDto>) -> Unit = {},
    ) {
        target.markConnecting()
        target.markConnected(
            handle = handle,
            connectionId = "conn_abc",
            epoch = epoch,
            applied = applied,
            mouseControlGranted = mouseControlGranted,
            onDead = onDead,
            onClosedByServer = onClosedByServer,
            onReconcileNeeded = onReconcileNeeded,
            onApplyFailures = onApplyFailures,
        )
    }

    // The alive-poll loop never goes idle on its own, so a connected test MUST
    // stop the connection before the body returns — runTest drains the shared
    // scheduler until idle, and an undisconnected poll spins virtual time
    // forever while MockK records every stubbed call (OOM, not a failure).
    private fun connTest(block: suspend TestScope.() -> Unit) =
        runTest(scope.testScheduler) {
            try {
                block()
            } finally {
                conn.markDisconnected()
            }
        }

    @Test
    fun `idFor derives stable id from the machineId`() {
        assertEquals("satellite:mid:abc123", SatelliteConnection.idFor(server))
    }

    @Test
    fun `initial state is IDLE with no handle, connectionId or slots`() {
        assertEquals(SatelliteSessionState.Idle, conn.state.value)
        assertEquals(-1, conn.handle)
        assertNull(conn.connectionId)
        assertTrue(conn.slots.value.isEmpty())
    }

    @Test
    fun `markConnected stores handle, epoch and applied results, starts heartbeat`() {
        every { repo.startHeartbeat(any()) } just Runs
        every { repo.isConnectionAlive(any()) } returns true

        conn.attachSlot("slot-1", controllerType = 1)
        connectLive(handle = 7, epoch = 5, applied = listOf(okApply(0, appliedType = 1)))

        assertEquals(SatelliteSessionState.Live, conn.state.value)
        assertEquals(7, conn.handle)
        assertEquals("conn_abc", conn.connectionId)
        assertEquals(5, conn.lastAppliedEpoch)
        assertEquals(true, conn.slots.value["slot-1"]?.registered)
        verify { repo.startHeartbeat(7) }
    }

    @Test
    fun `markDisconnected tears down heartbeat, clears handle and unregisters slots`() {
        every { repo.startHeartbeat(any()) } just Runs
        every { repo.isConnectionAlive(any()) } returns true

        conn.attachSlot("slot-1", controllerType = 0)
        connectLive(handle = 3, applied = listOf(okApply(0)))
        conn.markDisconnected()

        assertEquals(SatelliteSessionState.Idle, conn.state.value)
        assertEquals(-1, conn.handle)
        assertNull(conn.connectionId)
        assertEquals(-1, conn.lastAppliedEpoch)
        verify { repo.stopHeartbeat(3) }
        verify { repo.closeSocket(3) }
        assertEquals(false, conn.slots.value["slot-1"]?.registered)
    }

    @Test
    fun `attachSlot records the full descriptor without any default-type phase`() {
        conn.attachSlot("slot-1", controllerType = 1, touchpadMode = "ds4")

        val descriptor = conn.descriptorFor("slot-1")
        assertEquals(1, descriptor?.type)
        assertEquals("ds4", descriptor?.touchpadMode)
        assertEquals(0, descriptor?.ctrlIdx)
        // Idle: nothing to sync against yet — it rides the session PUT.
        assertTrue(slotSyncs.isEmpty())
    }

    @Test
    fun `desiredDescriptors carries caps from the per-slot capability lambda`() {
        val perSlot =
            newConnection(motionCapsBitsFor = { slot -> if (slot == "slot-A") ControllerDescriptor.CAP_MOTION else 0 })
        perSlot.attachSlot("slot-A", controllerType = 1)
        perSlot.attachSlot("slot-B", controllerType = 0)

        val descriptors = perSlot.desiredDescriptors().associateBy { it.ctrlIdx }
        assertTrue((descriptors[0]!!.caps and ControllerDescriptor.CAP_MOTION) != 0)
        assertTrue((descriptors[1]!!.caps and ControllerDescriptor.CAP_MOTION) == 0)
        assertTrue((descriptors[1]!!.caps and ControllerDescriptor.CAP_RUMBLE) != 0)
        assertTrue((descriptors[1]!!.caps and ControllerDescriptor.CAP_ANALOG_TRIGGERS) != 0)
        // Lightbar never advertised (no controller-LED API on Android).
        assertTrue((descriptors[0]!!.caps and ControllerDescriptor.CAP_LIGHTBAR) == 0)
    }

    @Test
    fun `attachSlot while live requests one slot sync`() {
        every { repo.isConnectionAlive(any()) } returns true
        connectLive()

        conn.attachSlot("slot-1", controllerType = 1)

        assertEquals(listOf("slot-1"), slotSyncs)
    }

    @Test
    fun `setControllerType updates the descriptor and requests a sync while live`() {
        every { repo.isConnectionAlive(any()) } returns true
        conn.attachSlot("slot-1", controllerType = 0)
        connectLive(applied = listOf(okApply(0)))
        slotSyncs.clear()

        conn.setControllerType("slot-1", controllerType = 1)

        assertEquals(1, conn.slots.value["slot-1"]?.controllerType)
        assertEquals(listOf("slot-1"), slotSyncs)
    }

    @Test
    fun `setControllerType while idle only updates local desired state`() {
        conn.attachSlot("slot-1", controllerType = 0)
        conn.setControllerType("slot-1", controllerType = 1)

        assertEquals(1, conn.slots.value["slot-1"]?.controllerType)
        assertTrue(slotSyncs.isEmpty())
    }

    @Test
    fun `setControllerType to the same value requests no sync`() {
        every { repo.isConnectionAlive(any()) } returns true
        conn.attachSlot("slot-1", controllerType = 1)
        connectLive(applied = listOf(okApply(0, appliedType = 1)))
        slotSyncs.clear()

        conn.setControllerType("slot-1", controllerType = 1)

        assertTrue(slotSyncs.isEmpty())
    }

    @Test
    fun `setTouchpadMode rides the descriptor`() {
        every { repo.isConnectionAlive(any()) } returns true
        conn.attachSlot("slot-1", controllerType = 1)
        connectLive(applied = listOf(okApply(0, appliedType = 1)))
        slotSyncs.clear()

        conn.setTouchpadMode("slot-1", "mouse")

        assertEquals("mouse", conn.slots.value["slot-1"]?.touchpadMode)
        assertEquals(listOf("slot-1"), slotSyncs)
        assertTrue(conn.wantsMouseControl())
    }

    @Test
    fun `declareSlot attaches when absent and updates whole descriptor when present`() {
        conn.declareSlot("slot-1", controllerType = 0, touchpadMode = "off")
        assertEquals(0, conn.slots.value["slot-1"]?.controllerType)

        conn.declareSlot("slot-1", controllerType = 1, touchpadMode = "ds4")
        assertEquals(1, conn.slots.value["slot-1"]?.controllerType)
        assertEquals("ds4", conn.slots.value["slot-1"]?.touchpadMode)
        assertEquals(1, conn.slots.value.size)
        assertEquals(0, conn.slots.value["slot-1"]?.controllerIndex)
    }

    @Test
    fun `detachSlot requests a slot removal only for an applied slot`() {
        every { repo.isConnectionAlive(any()) } returns true
        conn.attachSlot("slot-1", controllerType = 0)
        connectLive(applied = listOf(okApply(0)))

        conn.detachSlot("slot-1")

        assertEquals(listOf(0), slotRemovals)
        assertNull(conn.slots.value["slot-1"])
    }

    @Test
    fun `detachSlot for an unknown slot is a no-op`() {
        conn.detachSlot("ghost")
        assertTrue(slotRemovals.isEmpty())
    }

    @Test
    fun `applyResults flips registered and feeds the motion backend store`() {
        val store = SatelliteMotionBackendStatusStore()
        val withStore = newConnection(store = store)
        withStore.attachSlot("slot-1", controllerType = 1)

        withStore.applyResults(listOf(okApply(0, appliedType = 1, sinkSupported = true, backendOk = true)))

        assertEquals(true, withStore.slots.value["slot-1"]?.registered)
        val status = store.statusFor(withStore.id, "slot-1")
        assertEquals(true, status?.sinkSupportedForType)
        assertEquals(true, status?.backendOk)
    }

    @Test
    fun `a failed apply keeps the slot unregistered and surfaces the failure`() {
        conn.attachSlot("slot-1", controllerType = 0)
        var surfaced: List<ControllerApplyDto>? = null

        conn.applyResults(listOf(failedApply(0, result = "noSlots")), onApplyFailures = { surfaced = it })

        assertEquals(false, conn.slots.value["slot-1"]?.registered)
        assertEquals("noSlots", surfaced?.single()?.result)
    }

    @Test
    fun `replugFailed keeps streaming to the still-working previous pad`() {
        conn.attachSlot("slot-1", controllerType = 1) // wanted PlayStation
        var surfaced: List<ControllerApplyDto>? = null

        // The satellite kept the old Xbox pad (transactional replug failed).
        conn.applyResults(
            listOf(
                ControllerApplyDto(
                    ctrlIdx = 0,
                    result = ControllerApplyDto.APPLY_REPLUG_FAILED,
                    appliedType = 0,
                    motion = SessionMotionDto(sinkSupportedForType = false, backendOk = true),
                ),
            ),
            onApplyFailures = { surfaced = it },
        )

        assertEquals(true, conn.slots.value["slot-1"]?.registered)
        assertEquals(ControllerApplyDto.APPLY_REPLUG_FAILED, surfaced?.single()?.result)
    }

    @Test
    fun `streams stay gated until the descriptor is applied`() {
        every { repo.isConnectionAlive(any()) } returns true
        conn.attachSlot("slot-1", controllerType = 0)
        connectLive(applied = emptyList()) // nothing confirmed yet

        conn.sendReport("slot-1", buttons = 1, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)
        verify(exactly = 0) { repo.sendReport(any(), any(), any(), any(), any(), any(), any(), any(), any()) }

        conn.applyResults(listOf(okApply(0)))
        conn.sendReport("slot-1", buttons = 0x42, lt = 10, rt = 20, lx = 30, ly = -40, rx = 50, ry = -60)
        verify { repo.sendReport(7, 0, 0x42, 10, 20, 30, -40, 50, -60) }
    }

    @Test
    fun `sendReport routes to the attached slot's controller index`() {
        every { repo.isConnectionAlive(any()) } returns true
        conn.attachSlot("slot-A", controllerType = 0)
        conn.attachSlot("slot-B", controllerType = 0)
        connectLive(handle = 11, applied = listOf(okApply(0), okApply(1)))

        conn.sendReport("slot-B", buttons = 1, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)

        verify { repo.sendReport(11, 1, 1, 0, 0, 0, 0, 0, 0) }
    }

    @Test
    fun `sendReport for an unknown slot is dropped`() {
        every { repo.isConnectionAlive(any()) } returns true
        conn.attachSlot("slot-1", controllerType = 0)
        connectLive(applied = listOf(okApply(0)))

        conn.sendReport("ghost", buttons = 1, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0)

        verify(exactly = 0) { repo.sendReport(any(), eq(99), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `attaching a second slot allocates a fresh controller index`() {
        conn.attachSlot("slot-A", controllerType = 0)
        conn.attachSlot("slot-B", controllerType = 0)

        assertEquals(0, conn.slots.value["slot-A"]?.controllerIndex)
        assertEquals(1, conn.slots.value["slot-B"]?.controllerIndex)
    }

    @Test
    fun `detaching one slot frees its index for the next attach`() {
        conn.attachSlot("slot-A", controllerType = 0)
        conn.attachSlot("slot-B", controllerType = 0)
        conn.detachSlot("slot-A")

        conn.attachSlot("slot-C", controllerType = 0)
        assertEquals(0, conn.slots.value["slot-C"]?.controllerIndex)
    }

    @Test
    fun `attachSlot for an already-attached slot keeps the original descriptor`() {
        conn.attachSlot("slot-A", controllerType = 0)
        conn.attachSlot("slot-A", controllerType = 1)

        assertEquals(0, conn.slots.value["slot-A"]?.controllerType)
        assertEquals(1, conn.slots.value.size)
    }

    @Test
    fun `renameSlot re-keys a present slot, preserves its descriptor, and returns true`() {
        conn.attachSlot("a", controllerType = 1, touchpadMode = "ds4")
        assertTrue(conn.renameSlot("a", "b"))
        assertTrue(conn.slots.value.containsKey("b"))
        assertFalse(conn.slots.value.containsKey("a"))
        assertEquals(1, conn.slots.value["b"]?.controllerType)
        assertEquals("ds4", conn.slots.value["b"]?.touchpadMode)
    }

    @Test
    fun `renameSlot onto an already-present target does not merge`() {
        conn.attachSlot("a", controllerType = 0)
        conn.attachSlot("b", controllerType = 0)
        assertTrue(conn.renameSlot("a", "b"))
        assertTrue(conn.slots.value.containsKey("a"))
        assertTrue(conn.slots.value.containsKey("b"))
    }

    @Test
    fun `renameSlot of an absent source to an absent target returns false`() {
        assertFalse(conn.renameSlot("ghost", "new"))
        assertFalse(conn.slots.value.containsKey("new"))
    }

    @Test
    fun `refreshCapsIfChanged requests a sync when the capability lambda moved`() {
        every { repo.isConnectionAlive(any()) } returns true
        var capsBit = ControllerDescriptor.CAP_MOTION
        val reactive = newConnection(motionCapsBitsFor = { capsBit })
        reactive.attachSlot("slot-1", controllerType = 1)
        connectLive(target = reactive, applied = listOf(okApply(0, appliedType = 1)))
        slotSyncs.clear()

        capsBit = 0
        reactive.refreshCapsIfChanged()

        assertEquals(listOf("slot-1"), slotSyncs)
        reactive.markDisconnected()
    }

    @Test
    fun `refreshCapsIfChanged is idempotent when nothing moved`() {
        every { repo.isConnectionAlive(any()) } returns true
        val steady = newConnection(motionCapsBitsFor = { ControllerDescriptor.CAP_MOTION })
        steady.attachSlot("slot-1", controllerType = 1)
        connectLive(target = steady, applied = listOf(okApply(0, appliedType = 1)))
        slotSyncs.clear()

        steady.refreshCapsIfChanged()
        steady.refreshCapsIfChanged()

        assertTrue(slotSyncs.isEmpty())
        steady.markDisconnected()
    }

    @Test
    fun `refreshCapsIfChanged skips unapplied slots and idle sessions`() {
        var capsBit = ControllerDescriptor.CAP_MOTION
        val pending = newConnection(motionCapsBitsFor = { capsBit })
        pending.attachSlot("slot-pending", controllerType = 0)

        capsBit = 0
        pending.refreshCapsIfChanged() // idle → no sync
        assertTrue(slotSyncs.isEmpty())
    }

    @Test
    fun `epoch mismatch in the heartbeat ack triggers a reconcile`() =
        connTest {
            every { repo.isConnectionAlive(any()) } returns true
            every { repo.getServerEpoch(any()) } returns 9 // server moved past us
            every { repo.getActiveBitmap(any()) } returns 0

            var reconciles = 0
            conn.attachSlot("slot-1", controllerType = 0)
            connectLive(epoch = 5, applied = listOf(okApply(0)), onReconcileNeeded = { reconciles++ })

            scope.advanceTimeBy(1100)
            assertTrue(reconciles > 0)
        }

    @Test
    fun `bitmap mismatch triggers a reconcile even when the epoch matches`() =
        connTest {
            every { repo.isConnectionAlive(any()) } returns true
            every { repo.getServerEpoch(any()) } returns 5
            // Server says slot 0 is gone (failed replug / reap).
            every { repo.getActiveBitmap(any()) } returns 0

            var reconciles = 0
            conn.attachSlot("slot-1", controllerType = 0)
            connectLive(epoch = 5, applied = listOf(okApply(0)), onReconcileNeeded = { reconciles++ })

            scope.advanceTimeBy(1100)
            assertTrue(reconciles > 0)
        }

    @Test
    fun `matching epoch and bitmap stays quiet`() =
        connTest {
            every { repo.isConnectionAlive(any()) } returns true
            every { repo.getServerEpoch(any()) } returns 5
            every { repo.getActiveBitmap(any()) } returns 0b1

            var reconciles = 0
            conn.attachSlot("slot-1", controllerType = 0)
            connectLive(epoch = 5, applied = listOf(okApply(0)), onReconcileNeeded = { reconciles++ })

            scope.advanceTimeBy(3100)
            assertEquals(0, reconciles)
        }

    @Test
    fun `no enriched ack yet means no reconcile`() =
        connTest {
            every { repo.isConnectionAlive(any()) } returns true
            every { repo.getServerEpoch(any()) } returns -1

            var reconciles = 0
            conn.attachSlot("slot-1", controllerType = 0)
            connectLive(epoch = 5, applied = listOf(okApply(0)), onReconcileNeeded = { reconciles++ })

            scope.advanceTimeBy(3100)
            assertEquals(0, reconciles)
        }

    @Test
    fun `close-notify fires onClosedByServer with the reason immediately`() =
        connTest {
            every { repo.isConnectionAlive(any()) } returns true
            every { repo.getSessionCloseReason(any()) } returns SatelliteConnection.CLOSE_REASON_UNPAIRED

            var closedReason = -1
            var died = false
            connectLive(onDead = { died = true }, onClosedByServer = { closedReason = it })

            // One alive-poll tick, not the 5-miss death window.
            scope.advanceTimeBy(1100)
            assertEquals(SatelliteConnection.CLOSE_REASON_UNPAIRED, closedReason)
            assertFalse(died)
        }

    @Test
    fun `heartbeat death still fires onDead after the miss window`() =
        connTest {
            every { repo.isConnectionAlive(any()) } returns false

            var died = false
            connectLive(onDead = { died = true })

            scope.advanceTimeBy(5100)
            assertTrue(died)
        }

    @Test
    fun `matchesAppliedView is true when applied set equals desired set`() {
        conn.attachSlot("slot-A", controllerType = 1)
        conn.attachSlot("slot-B", controllerType = 0)
        val view =
            SessionViewDto(
                connectionId = "conn_abc",
                epoch = 3,
                controllers =
                    listOf(
                        SessionViewControllerDto(ctrlIdx = 0, active = true, appliedType = 1),
                        SessionViewControllerDto(ctrlIdx = 1, active = true, appliedType = 0),
                    ),
            )
        assertTrue(conn.matchesAppliedView(view))
    }

    @Test
    fun `matchesAppliedView is false on a missing slot or a type divergence`() {
        conn.attachSlot("slot-A", controllerType = 1)
        val missing = SessionViewDto(controllers = emptyList())
        assertFalse(conn.matchesAppliedView(missing))

        val wrongType =
            SessionViewDto(
                controllers = listOf(SessionViewControllerDto(ctrlIdx = 0, active = true, appliedType = 0)),
            )
        assertFalse(conn.matchesAppliedView(wrongType))
    }

    @Test
    fun `matchesAppliedView is false when mouse is wanted but not granted`() {
        conn.attachSlot(
            "slot-1",
            controllerType = 1,
            touchpadMode = ControllerDescriptor.TOUCHPAD_MODE_MOUSE,
        )
        val view =
            SessionViewDto(
                connectionId = "conn_abc",
                epoch = 1,
                controllers = listOf(SessionViewControllerDto(ctrlIdx = 0, active = true, appliedType = 1)),
                hostFeatures = HostFeaturesDto(HostFeatureGrantDto(granted = false)),
            )
        assertFalse(conn.matchesAppliedView(view))
        val granted = view.copy(hostFeatures = HostFeaturesDto(HostFeatureGrantDto(granted = true)))
        assertTrue(conn.matchesAppliedView(granted))
    }

    @Test
    fun `markConnected records the mouse grant and markDisconnected clears it`() {
        every { repo.startHeartbeat(any()) } just Runs
        every { repo.isConnectionAlive(any()) } returns true

        conn.attachSlot("slot-1", controllerType = 0)
        connectLive(applied = listOf(okApply(0)), mouseControlGranted = true)
        assertTrue(conn.mouseControlGranted)

        conn.markDisconnected()
        assertFalse(conn.mouseControlGranted)
    }

    @Test
    fun `markConnected from IDLE is rejected and leaves state IDLE`() {
        conn.markConnected(handle = 11, connectionId = "c", epoch = 0, applied = emptyList(), onDead = {})

        assertEquals(SatelliteSessionState.Idle, conn.state.value)
        assertEquals(-1, conn.handle)
        verify(exactly = 0) { repo.startHeartbeat(any()) }
    }

    @Test
    fun `second markConnected while already CONNECTED preserves original handle`() {
        every { repo.isConnectionAlive(any()) } returns true

        connectLive(handle = 1)
        conn.markConnected(handle = 2, connectionId = "second", epoch = 0, applied = emptyList(), onDead = {})

        assertEquals(SatelliteSessionState.Live, conn.state.value)
        assertEquals(1, conn.handle)
        assertEquals("conn_abc", conn.connectionId)
        verify(exactly = 1) { repo.startHeartbeat(any()) }
    }

    @Test
    fun `markConnecting from CONNECTED is ignored so a live session isn't demoted`() {
        every { repo.isConnectionAlive(any()) } returns true
        connectLive(handle = 4)
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
    fun `updateServer replaces server snapshot`() {
        val newServer = server.copy(name = "Renamed")
        conn.updateServer(newServer)
        assertEquals("Renamed", conn.server.value.name)
    }

    @Test
    fun `adoptEpoch updates the reconcile reference`() {
        every { repo.isConnectionAlive(any()) } returns true
        connectLive(epoch = 1)
        conn.adoptEpoch(8)
        assertEquals(8, conn.lastAppliedEpoch)
    }
}
