// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import app.cash.turbine.test
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnection.SlotBinding
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

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

    private fun connection(slots: StateFlow<Map<String, SlotBinding>>): SatelliteConnection {
        val conn = mockk<SatelliteConnection>()
        every { conn.slots } returns slots
        return conn
    }

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
    fun `connectionFor is null for a Bluetooth-bound pad, no motion or battery channel`() {
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
                bindings = mapOf("9" to "c"),
                summaries = listOf(summary("c")),
                connections = mapOf("c" to reachableConn),
            )
        assertEquals(mapOf("9" to reachableConn), resolved)
    }

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
                    assertEquals(emptyMap<String, SatelliteConnection>(), awaitItem())

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
            // Empty connections must still emit; bare combine over zero flows would stall.
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
