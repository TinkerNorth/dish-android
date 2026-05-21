// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import app.cash.turbine.test
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnection.SlotBinding
import com.tinkernorth.dish.source.sensor.PhysicalBatterySource
import com.tinkernorth.dish.source.sensor.PhysicalMotionSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for [PhysicalReachability] — the shared "which physical pads can
 * currently accept a satellite report" logic behind [PhysicalBatterySource]
 * and [PhysicalMotionSource].
 *
 * The headline regression test is [reachableSlots re-emits when a slot flips
 * registered with no other change]. A physical pad's slot flips `registered`
 * on the `addController` ACK, 0–2 s **after** the connection reaches
 * CONNECTED — and on auto-reconnect the binding already exists, so at that
 * moment nothing in `devices` / `bindings` / `connections` changes. A
 * reachability flow that observes only those three inputs evaluates the pad
 * once (while still unregistered) and never re-evaluates it, so physical-pad
 * motion and battery silently never start. The flow must also fold in every
 * live connection's `slots` table.
 */
class PhysicalReachabilityTest {
    private fun device(id: Int) = PhysicalGamepadRegistry.Device(id, "Pad-$id")

    private fun summary(
        id: String,
        live: LinkState = LinkState.Connected,
        kind: ConnectionKind = ConnectionKind.SATELLITE,
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    private fun slot(registered: Boolean) = SlotBinding(controllerIndex = 0, controllerType = 0, registered = registered)

    /** A mock [SatelliteConnection] whose slot table is a flow the test drives. */
    private fun connection(slots: StateFlow<Map<String, SlotBinding>>): SatelliteConnection {
        val conn = mockk<SatelliteConnection>()
        every { conn.slots } returns slots
        return conn
    }

    // ── connectionFor (pure) ─────────────────────────────────────────────

    @Test
    fun `connectionFor returns the connection for a bound, connected, registered slot`() {
        val conn = connection(MutableStateFlow(mapOf("9" to slot(registered = true))))
        assertSame(
            conn,
            PhysicalReachability.connectionFor(
                slotId = "9",
                bindings = mapOf("9" to "c"),
                summariesById = mapOf("c" to summary("c")),
                connections = mapOf("c" to conn),
            ),
        )
    }

    @Test
    fun `connectionFor is null for an unbound pad`() {
        assertNull(
            PhysicalReachability.connectionFor(
                slotId = "9",
                bindings = emptyMap(),
                summariesById = mapOf("c" to summary("c")),
                connections = emptyMap(),
            ),
        )
    }

    @Test
    fun `connectionFor is null for a Bluetooth-bound pad — no motion or battery channel`() {
        val conn = connection(MutableStateFlow(mapOf("9" to slot(registered = true))))
        assertNull(
            PhysicalReachability.connectionFor(
                slotId = "9",
                bindings = mapOf("9" to "c"),
                summariesById = mapOf("c" to summary("c", kind = ConnectionKind.BLUETOOTH)),
                connections = mapOf("c" to conn),
            ),
        )
    }

    @Test
    fun `connectionFor is null while the satellite is still CONNECTING`() {
        val conn = connection(MutableStateFlow(mapOf("9" to slot(registered = true))))
        assertNull(
            PhysicalReachability.connectionFor(
                slotId = "9",
                bindings = mapOf("9" to "c"),
                summariesById = mapOf("c" to summary("c", live = LinkState.Connecting)),
                connections = mapOf("c" to conn),
            ),
        )
    }

    @Test
    fun `connectionFor is null until the slot has registered`() {
        // CONNECTED, but the addController ACK has not landed: sendBattery /
        // sendMotion would drop the report, so the pad is not yet reachable.
        val conn = connection(MutableStateFlow(mapOf("9" to slot(registered = false))))
        assertNull(
            PhysicalReachability.connectionFor(
                slotId = "9",
                bindings = mapOf("9" to "c"),
                summariesById = mapOf("c" to summary("c")),
                connections = mapOf("c" to conn),
            ),
        )
    }

    @Test
    fun `connectionFor is null when the connection object is gone`() {
        // A summary says CONNECTED but the SatelliteConnection map has no entry
        // (a teardown race) — must not NPE, must resolve to not-reachable.
        assertNull(
            PhysicalReachability.connectionFor(
                slotId = "9",
                bindings = mapOf("9" to "c"),
                summariesById = mapOf("c" to summary("c")),
                connections = emptyMap(),
            ),
        )
    }

    @Test
    fun `resolve keeps only the reachable pads`() {
        val reachableConn = connection(MutableStateFlow(mapOf("9" to slot(registered = true))))
        val resolved =
            PhysicalReachability.resolve(
                deviceIds = setOf(9, 11),
                bindings = mapOf("9" to "c"), // device 11 is unbound
                summaries = listOf(summary("c")),
                connections = mapOf("c" to reachableConn),
            )
        assertEquals(mapOf("9" to reachableConn), resolved)
    }

    // ── reachableSlots (flow) — the registration-timing regression ───────

    @Test
    fun `reachableSlots re-emits when a slot flips registered with no other change`() =
        runTest {
            val slots = MutableStateFlow(mapOf("9" to slot(registered = false)))
            val conn = connection(slots)
            val devices = MutableStateFlow(mapOf(9 to device(9)))
            val bindings = MutableStateFlow(mapOf("9" to "c"))
            val summaries = MutableStateFlow(listOf(summary("c")))
            val connections = MutableStateFlow(mapOf("c" to conn))

            PhysicalReachability
                .reachableSlots(devices, bindings, summaries, connections)
                .test {
                    // Bound + CONNECTED, but the addController ACK has not
                    // landed yet, so the pad is not reachable.
                    assertEquals(emptyMap<String, SatelliteConnection>(), awaitItem())

                    // The ACK flips `registered`. NOTHING in devices / bindings
                    // / connections changes — this is the auto-reconnect path.
                    // A flow that doesn't observe the slot table never re-emits
                    // here and the pad's motion/battery silently never start.
                    slots.value = mapOf("9" to slot(registered = true))

                    assertEquals(mapOf("9" to conn), awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
        }

    @Test
    fun `reachableSlots drops a pad when its satellite disconnects`() =
        runTest {
            val slots = MutableStateFlow(mapOf("9" to slot(registered = true)))
            val conn = connection(slots)
            val devices = MutableStateFlow(mapOf(9 to device(9)))
            val bindings = MutableStateFlow(mapOf("9" to "c"))
            val summaries = MutableStateFlow(listOf(summary("c")))
            val connections = MutableStateFlow(mapOf("c" to conn))

            PhysicalReachability
                .reachableSlots(devices, bindings, summaries, connections)
                .test {
                    assertEquals(mapOf("9" to conn), awaitItem())
                    summaries.value = listOf(summary("c", live = LinkState.Connecting))
                    assertEquals(emptyMap<String, SatelliteConnection>(), awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
        }

    @Test
    fun `reachableSlots emits an empty map and does not hang when there are no connections`() =
        runTest {
            // The empty-connection case must still produce an emission — a bare
            // combine(slotFlows) over zero flows would never emit, stalling the
            // whole observer. The flowOf(Unit) guard prevents that.
            val devices = MutableStateFlow(mapOf(9 to device(9)))
            val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
            val summaries = MutableStateFlow<List<ConnectionSummary>>(emptyList())
            val connections = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())

            PhysicalReachability
                .reachableSlots(devices, bindings, summaries, connections)
                .test {
                    assertEquals(emptyMap<String, SatelliteConnection>(), awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
        }
}
