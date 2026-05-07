// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ConnectionHub] binding semantics. Upstream flows from
 * [SatelliteConnectionManager] and [BluetoothGamepadRegistry] are driven with
 * [MutableStateFlow] stubs so the hub's `buildSummaries` logic can be exercised
 * without spinning real sockets or Bluetooth stacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHubTest {
    private lateinit var satellite: SatelliteConnectionManager
    private lateinit var bt: BluetoothGamepadRegistry
    private lateinit var store: ConnectionStore
    private lateinit var scope: TestScope

    private val satConnsFlow = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())
    private val btStatesFlow = MutableStateFlow<Map<String, BluetoothGamepadRegistry.SlotState>>(emptyMap())

    @Before
    fun setUp() {
        satellite = mockk(relaxed = true)
        bt = mockk(relaxed = true)
        store = mockk(relaxed = true)
        every { satellite.connections } returns satConnsFlow
        every { bt.states } returns btStatesFlow
        every { store.remembered() } returns emptyList()
        every { store.rememberedBt() } returns emptyList()
        scope = TestScope(StandardTestDispatcher())
    }

    /**
     * Build a hub *after* per-test mocks are set. The hub's combine emits its
     * first summary snapshot when collection starts inside `init`, so any
     * store-mocking must happen before this call.
     */
    private fun buildHub(): ConnectionHub {
        val hub = ConnectionHub(satellite, bt, store, scope)
        scope.testScheduler.runCurrent()
        return hub
    }

    @Test
    fun `initial connections list is empty when no sources have data`() =
        runTest {
            val hub = buildHub()
            assertEquals(emptyList<ConnectionSummary>(), hub.connections.value)
            assertEquals(emptyMap<String, String>(), hub.bindings.value)
        }

    @Test
    fun `remembered satellite entries appear as IDLE summaries`() {
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(
                    id = "satellite:10.0.0.1:9876",
                    name = "A",
                    ip = "10.0.0.1",
                    udpPort = 9876,
                    pairPort = 9878,
                    httpPort = 9877,
                ),
            )
        val hub = buildHub()

        val summaries = hub.connections.value
        assertEquals(1, summaries.size)
        assertEquals(ConnectionKind.SATELLITE, summaries[0].kind)
        assertEquals(ConnectionLive.IDLE, summaries[0].live)
        assertEquals("satellite:10.0.0.1:9876", summaries[0].id)
        assertTrue(summaries[0].boundSlotIds.isEmpty())
    }

    @Test
    fun `remembered bt entries surface their profile in detail`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:AA:BB", name = "PS5", mac = "AA:BB", profileName = "PLAYSTATION"),
            )
        val hub = buildHub()

        val summary = hub.connections.value.firstOrNull { it.kind == ConnectionKind.BLUETOOTH }
        assertEquals("bt:AA:BB", summary?.id)
        assertEquals("PLAYSTATION", summary?.btProfile)
        assertEquals(ConnectionLive.IDLE, summary?.live)
    }

    @Test
    fun `bt connected state propagates from registry`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:X", name = "Xbox", mac = "X", profileName = "XBOX"),
            )
        btStatesFlow.value =
            mapOf(
                "bt:X" to BluetoothGamepadRegistry.SlotState(registered = true, connected = true),
            )
        val hub = buildHub()

        val summary = hub.connections.value.first { it.kind == ConnectionKind.BLUETOOTH }
        assertEquals(ConnectionLive.CONNECTED, summary.live)
    }

    @Test
    fun `bind sets slot to connection and binding is reflected in summary`() {
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind(slotId = "slot-A", connectionId = "s:1")

        assertEquals(mapOf("slot-A" to "s:1"), hub.bindings.value)
        assertEquals(
            listOf("slot-A"),
            hub.connections.value
                .first()
                .boundSlotIds,
        )
    }

    @Test
    fun `binding two slots to the same satellite keeps both attached`() {
        val satConn = mockk<SatelliteConnection>(relaxed = true)
        every { satellite.get("s:1") } returns satConn
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1")
        hub.bind("slot-B", "s:1")

        assertEquals(
            mapOf("slot-A" to "s:1", "slot-B" to "s:1"),
            hub.bindings.value,
        )
        // Both slots show as bound on the satellite summary; nothing was detached.
        val summary = hub.connections.value.first { it.id == "s:1" }
        assertEquals(setOf("slot-A", "slot-B"), summary.boundSlotIds.toSet())
        verify(exactly = 0) { satConn.detachSlot(any()) }
    }

    @Test
    fun `bind evicts the prior slot for a bluetooth host`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:X", name = "Xbox", mac = "X", profileName = "XBOX"),
            )
        val hub = buildHub()

        hub.bind("slot-A", "bt:X")
        hub.bind("slot-B", "bt:X")

        // BT is single-host: the earlier slot is released so the new one owns it.
        assertEquals(mapOf("slot-B" to "bt:X"), hub.bindings.value)
    }

    @Test
    fun `binding a slot to a new satellite detaches it from the prior one`() {
        val sat1 = mockk<SatelliteConnection>(relaxed = true)
        val sat2 = mockk<SatelliteConnection>(relaxed = true)
        every { satellite.get("s:1") } returns sat1
        every { satellite.get("s:2") } returns sat2
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
                RememberedSatellite(id = "s:2", name = "B", ip = "2", udpPort = 4, pairPort = 5, httpPort = 6),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1")
        hub.bind("slot-A", "s:2")

        assertEquals(mapOf("slot-A" to "s:2"), hub.bindings.value)
        verify { sat1.detachSlot("slot-A") }
    }

    @Test
    fun `unbind removes the binding and detaches the satellite slot by id`() {
        val satConn = mockk<SatelliteConnection>(relaxed = true)
        every { satellite.get("s:1") } returns satConn
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1")
        hub.unbind("slot-A")

        assertNull(hub.bindings.value["slot-A"])
        verify { satConn.detachSlot("slot-A") }
    }

    @Test
    fun `bind seeds a default Xbox controller type for new satellite slots`() {
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1")

        val summary = hub.connections.value.first { it.id == "s:1" }
        assertEquals(CONTROLLER_TYPE_XBOX, summary.satelliteControllerTypes["slot-A"])
    }

    @Test
    fun `setSatelliteControllerType updates the summary and pushes to the connection`() {
        val satConn = mockk<SatelliteConnection>(relaxed = true)
        every { satellite.get("s:1") } returns satConn
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1")
        hub.setSatelliteControllerType("s:1", "slot-A", CONTROLLER_TYPE_PLAYSTATION)
        scope.testScheduler.runCurrent()

        val summary = hub.connections.value.first { it.id == "s:1" }
        assertEquals(CONTROLLER_TYPE_PLAYSTATION, summary.satelliteControllerTypes["slot-A"])
    }

    @Test
    fun `summary returns the entry matching id or null`() {
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        assertEquals("s:1", hub.summary("s:1")?.id)
        assertNull(hub.summary("missing"))
    }

    @Test
    fun `autoReconnectAll connects each remembered satellite that is not live`() {
        val live = RememberedSatellite(id = "s:live", name = "L", ip = "1.1.1.1", udpPort = 1, pairPort = 2, httpPort = 3)
        val idle = RememberedSatellite(id = "s:idle", name = "I", ip = "2.2.2.2", udpPort = 4, pairPort = 5, httpPort = 6)
        every { store.remembered() } returns listOf(live, idle)
        val liveConn =
            mockk<SatelliteConnection>(relaxed = true) {
                every { state } returns MutableStateFlow(SatelliteState.CONNECTED)
            }
        every { satellite.get("s:live") } returns liveConn
        every { satellite.get("s:idle") } returns null
        val hub = buildHub()

        hub.autoReconnectAll()

        verify(exactly = 0) { satellite.connect(match { it.ip == "1.1.1.1" }) }
        verify { satellite.connect(match { it.ip == "2.2.2.2" }) }
    }

    @Test
    fun `autoReconnectAll tries the first remembered bt host when none are live`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:A", name = "A", mac = "A", profileName = "XBOX"),
                RememberedBt(id = "bt:B", name = "B", mac = "B", profileName = "XBOX"),
            )
        val hub = buildHub()

        hub.autoReconnectAll()

        verify { bt.tryAutoReconnect("bt:A") }
        verify(exactly = 0) { bt.tryAutoReconnect("bt:B") }
    }

    @Test
    fun `autoReconnectAll skips bt when a remembered host is already registered`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:A", name = "A", mac = "A", profileName = "XBOX"),
            )
        every { bt.state("bt:A") } returns BluetoothGamepadRegistry.SlotState(registered = true)
        val hub = buildHub()

        hub.autoReconnectAll()

        verify(exactly = 0) { bt.tryAutoReconnect(any()) }
    }
}
