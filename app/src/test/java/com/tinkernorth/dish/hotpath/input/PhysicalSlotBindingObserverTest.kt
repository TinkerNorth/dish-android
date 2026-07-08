// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.source.connection.SatelliteConnection
import org.junit.Assert.assertEquals
import org.junit.Test

// Locks the physical-slot reconciliation: which device id binds to which server-side controller, and
// the departed-before-bind ordering that keeps a re-added id off a stale entry's controller index.
class PhysicalSlotBindingObserverTest {
    private fun satSummary(
        id: String,
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(
        id = id,
        kind = ConnectionKind.SATELLITE,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    private fun btSummary(
        id: String,
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(
        id = id,
        kind = ConnectionKind.BLUETOOTH,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    private fun slot(
        index: Int,
        registered: Boolean = true,
    ) = SatelliteConnection.SlotBinding(controllerIndex = index, controllerType = 0, registered = registered)

    private fun reconcile(
        present: Set<Int> = emptySet(),
        lastBound: Set<Int> = emptySet(),
        bindings: Map<String, String> = emptyMap(),
        summaries: List<ConnectionSummary> = emptyList(),
        slotInfo: Map<String, SatelliteSlotSnapshot> = emptyMap(),
        btConnectedIds: Set<String> = emptySet(),
    ) = reconcileSlots(present, lastBound, bindings, summaries, slotInfo, btConnectedIds)

    @Test
    fun `a departed device is unbound then forgotten then released, before any binds`() {
        // Device 7 left; device 5 is present and bound to a live satellite slot 0.
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5, 7),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a")),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = 9, slots = mapOf("5" to slot(0)))),
            )
        assertEquals(
            listOf(
                BindOp.Unbind(7),
                BindOp.Forget(7),
                BindOp.ReleaseHubBinding(7),
                BindOp.BindSatellite(deviceId = 5, handle = 9, controllerIndex = 0),
            ),
            ops,
        )
    }

    @Test
    fun `a departed synthetic negative id is unbound and released but not forgotten`() {
        // detachUsbDevice frees a claimed synthetic, so the negative id must skip Forget.
        val ops = reconcile(lastBound = setOf(-1000))
        assertEquals(listOf(BindOp.Unbind(-1000), BindOp.ReleaseHubBinding(-1000)), ops)
    }

    @Test
    fun `a present device on a live registered satellite slot binds to that controller index`() {
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a")),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = 9, slots = mapOf("5" to slot(2)))),
            )
        assertEquals(listOf(BindOp.BindSatellite(deviceId = 5, handle = 9, controllerIndex = 2)), ops)
    }

    @Test
    fun `a present device on an unregistered satellite slot is unbound not bound`() {
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a")),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = 9, slots = mapOf("5" to slot(0, registered = false)))),
            )
        assertEquals(listOf(BindOp.Unbind(5)), ops)
    }

    @Test
    fun `a present device on a satellite with a negative handle is unbound not bound`() {
        // handle < 0 means the session is not live, mirroring the registered re-check.
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a")),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = -1, slots = mapOf("5" to slot(0)))),
            )
        assertEquals(listOf(BindOp.Unbind(5)), ops)
    }

    @Test
    fun `a present device bound to an unknown satellite connection is unbound not bound`() {
        // No slotInfo entry for the connection => the old satellite.get(cid) == null branch.
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a")),
            )
        assertEquals(listOf(BindOp.Unbind(5)), ops)
    }

    @Test
    fun `a present device on an actually-connected bluetooth connection binds bluetooth`() {
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "bt:aa"),
                summaries = listOf(btSummary("bt:aa")),
                btConnectedIds = setOf("bt:aa"),
            )
        assertEquals(listOf(BindOp.BindBluetooth(deviceId = 5, connectionId = "bt:aa")), ops)
    }

    @Test
    fun `a bluetooth summary that says connected but whose registry is not connected is unbound`() {
        // The fix: the satellite-only liveness re-check now also guards the BT branch.
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "bt:aa"),
                summaries = listOf(btSummary("bt:aa", live = LinkState.Connected)),
                btConnectedIds = emptySet(),
            )
        assertEquals(listOf(BindOp.Unbind(5)), ops)
    }

    @Test
    fun `an unsteady satellite session keeps a registered slot bound`() {
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a", live = LinkState.Unstable)),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = 9, slots = mapOf("5" to slot(2)))),
            )
        assertEquals(listOf(BindOp.BindSatellite(deviceId = 5, handle = 9, controllerIndex = 2)), ops)
    }

    @Test
    fun `an unsteady satellite session still unbinds an unregistered slot`() {
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a", live = LinkState.Unstable)),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = 9, slots = mapOf("5" to slot(0, registered = false)))),
            )
        assertEquals(listOf(BindOp.Unbind(5)), ops)
    }

    @Test
    fun `an unsteady satellite session with a dead handle is unbound`() {
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a", live = LinkState.Unstable)),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = -1, slots = mapOf("5" to slot(0)))),
            )
        assertEquals(listOf(BindOp.Unbind(5)), ops)
    }

    @Test
    fun `an unsteady bluetooth connection binds only while the registry is still connected`() {
        val connected =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "bt:aa"),
                summaries = listOf(btSummary("bt:aa", live = LinkState.Unstable)),
                btConnectedIds = setOf("bt:aa"),
            )
        assertEquals(listOf(BindOp.BindBluetooth(deviceId = 5, connectionId = "bt:aa")), connected)

        val dropped =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "bt:aa"),
                summaries = listOf(btSummary("bt:aa", live = LinkState.Unstable)),
                btConnectedIds = emptySet(),
            )
        assertEquals(listOf(BindOp.Unbind(5)), dropped)
    }

    @Test
    fun `a present device whose summary is not connected is unbound regardless of kind`() {
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a", live = LinkState.Connecting)),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = 9, slots = mapOf("5" to slot(0)))),
            )
        assertEquals(listOf(BindOp.Unbind(5)), ops)
    }

    @Test
    fun `a present device with no binding is unbound`() {
        val ops = reconcile(present = setOf(5), lastBound = setOf(5))
        assertEquals(listOf(BindOp.Unbind(5)), ops)
    }

    @Test
    fun `with no bindings only departed unbinds and releases are emitted`() {
        // Two ids left, none present and none bound: each departed framework id is unbound, forgotten
        // and released; no bind ops at all.
        val ops = reconcile(lastBound = setOf(3, 8))
        assertEquals(
            listOf(
                BindOp.Unbind(3),
                BindOp.Forget(3),
                BindOp.ReleaseHubBinding(3),
                BindOp.Unbind(8),
                BindOp.Forget(8),
                BindOp.ReleaseHubBinding(8),
            ),
            ops,
        )
    }

    @Test
    fun `an empty snapshot yields no ops`() {
        assertEquals(emptyList<BindOp>(), reconcile())
    }

    @Test
    fun `a stale binding whose device is in neither present nor lastBound is swept`() {
        // Device 7 departed while the observer was stopped: it is in neither set, yet its binding
        // survived. Without the sweep its slot re-registers on the satellite on every reconnect.
        val ops =
            reconcile(
                bindings = mapOf("7" to "sat:a"),
                summaries = listOf(satSummary("sat:a")),
            )
        assertEquals(
            listOf(
                BindOp.Unbind(7),
                BindOp.Forget(7),
                BindOp.ReleaseHubBinding(7),
            ),
            ops,
        )
    }

    @Test
    fun `a stale synthetic binding is swept without a forget`() {
        val ops = reconcile(bindings = mapOf("-1000" to "sat:a"))
        assertEquals(listOf(BindOp.Unbind(-1000), BindOp.ReleaseHubBinding(-1000)), ops)
    }

    @Test
    fun `a non-numeric slot binding is never swept`() {
        val ops = reconcile(bindings = mapOf("virtual" to "sat:a"))
        assertEquals(emptyList<BindOp>(), ops)
    }

    @Test
    fun `a stale binding also departed this pass is swept once`() {
        val ops = reconcile(lastBound = setOf(7), bindings = mapOf("7" to "sat:a"))
        assertEquals(
            listOf(
                BindOp.Unbind(7),
                BindOp.Forget(7),
                BindOp.ReleaseHubBinding(7),
            ),
            ops,
        )
    }

    @Test
    fun `a swept stale binding precedes the present device's bind`() {
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a", "7" to "sat:a"),
                summaries = listOf(satSummary("sat:a")),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = 9, slots = mapOf("5" to slot(0)))),
            )
        assertEquals(
            listOf(
                BindOp.Unbind(7),
                BindOp.Forget(7),
                BindOp.ReleaseHubBinding(7),
                BindOp.BindSatellite(deviceId = 5, handle = 9, controllerIndex = 0),
            ),
            ops,
        )
    }

    @Test
    fun `a bound present device is not swept`() {
        val ops =
            reconcile(
                present = setOf(5),
                lastBound = setOf(5),
                bindings = mapOf("5" to "sat:a"),
                summaries = listOf(satSummary("sat:a")),
                slotInfo = mapOf("sat:a" to SatelliteSlotSnapshot(handle = 9, slots = mapOf("5" to slot(0)))),
            )
        assertEquals(listOf(BindOp.BindSatellite(deviceId = 5, handle = 9, controllerIndex = 0)), ops)
    }
}
