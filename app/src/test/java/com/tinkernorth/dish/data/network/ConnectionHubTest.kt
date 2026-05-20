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

    // ConnectionHub now subscribes to discoveredServers to split paired-idle
    // satellites into Ready (in scan) vs Saved (offline). Tests default to an
    // empty scan; push an entry into this flow to assert the Ready path.
    private val discoveredFlow =
        MutableStateFlow<List<com.tinkernorth.dish.data.model.DiscoveredServer>>(emptyList())

    // Client-side Stale markers: per-satellite (set on auto-reconnect's
    // pairing-rejected branch) and per-BT (set on KEY_MISSING / BOND_NONE
    // by BluetoothBondMonitor). Default empty; push an id to assert the
    // Stale-chip lift in buildSummaries.
    private val staleSatFlow = MutableStateFlow<Set<String>>(emptySet())
    private val staleBtFlow = MutableStateFlow<Map<String, com.tinkernorth.dish.ui.bluetooth.BtStaleReason>>(emptyMap())

    @Before
    fun setUp() {
        satellite = mockk(relaxed = true)
        bt = mockk(relaxed = true)
        store = mockk(relaxed = true)
        every { satellite.connections } returns satConnsFlow
        every { satellite.discoveredServers } returns discoveredFlow
        every { satellite.staleSatelliteIds } returns staleSatFlow
        every { bt.states } returns btStatesFlow
        every { bt.staleBtIds } returns staleBtFlow
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
        assertEquals(LinkState.Saved, summaries[0].live)
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
        assertEquals(LinkState.Saved, summary?.live)
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
        assertEquals(LinkState.Connected, summary.live)
    }

    // ── BT progress surfacing (acquiring / registered / transient slot) ───

    @Test
    fun `acquiring on a remembered bt host shows CONNECTING`() {
        // Without acquiring being treated as CONNECTING, the row would briefly
        // flicker through IDLE between start() and onAppRegistered, which the
        // user reads as "nothing happened".
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:X", name = "Xbox", mac = "X", profileName = "Xbox"),
            )
        btStatesFlow.value =
            mapOf("bt:X" to BluetoothGamepadRegistry.SlotState(acquiring = true))
        val hub = buildHub()

        val summary = hub.connections.value.first { it.kind == ConnectionKind.BLUETOOTH }
        assertEquals(LinkState.Connecting, summary.live)
    }

    @Test
    fun `transient bt-pending slot surfaces with profile label even before remembered`() {
        // Until the host actually connects, RememberedBt has no entry for the
        // session. The hub still emits a transient summary keyed by the
        // pending id so the user sees something happen.
        btStatesFlow.value =
            mapOf(
                "bt-pending-42" to
                    BluetoothGamepadRegistry.SlotState(
                        profileName = "PlayStation",
                        acquiring = true,
                    ),
            )
        val hub = buildHub()

        val transient = hub.connections.value.firstOrNull { it.id == "bt-pending-42" }
        assertEquals(ConnectionKind.BLUETOOTH, transient?.kind)
        assertEquals("PlayStation", transient?.label)
        assertEquals(LinkState.Connecting, transient?.live)
        assertEquals("PlayStation", transient?.btProfile)
    }

    @Test
    fun `transient bt-pending detail differentiates acquiring vs registered`() {
        btStatesFlow.value =
            mapOf(
                "bt-pending-1" to
                    BluetoothGamepadRegistry.SlotState(
                        profileName = "Xbox",
                        acquiring = true,
                    ),
            )
        val hub = buildHub()
        val acquiringDetail =
            hub.connections.value
                .first { it.id == "bt-pending-1" }
                .detail

        // Once Acquired → Registered, the pending entry's status should change
        // to "Ready to pair" so the user knows to look at their host's BT menu.
        btStatesFlow.value =
            mapOf(
                "bt-pending-1" to
                    BluetoothGamepadRegistry.SlotState(
                        profileName = "Xbox",
                        registered = true,
                    ),
            )
        scope.testScheduler.runCurrent()
        val readyDetail =
            hub.connections.value
                .first { it.id == "bt-pending-1" }
                .detail

        assert(acquiringDetail.contains("Acquiring", ignoreCase = true)) {
            "expected 'Acquiring…' tone, got: $acquiringDetail"
        }
        assert(readyDetail.contains("pair", ignoreCase = true)) {
            "expected 'Ready to pair' tone, got: $readyDetail"
        }
    }

    @Test
    fun `transient slot disappears once registry re-keys to bt-mac and host is remembered`() {
        // Simulates the hand-off: registry's onConnected re-keys "bt-pending-X"
        // to "bt:<MAC>", store.rememberBt() inserts the entry, the next
        // emission collapses both into a single row.
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:AA:BB", name = "PS5", mac = "AA:BB", profileName = "PlayStation"),
            )
        btStatesFlow.value =
            mapOf(
                "bt:AA:BB" to
                    BluetoothGamepadRegistry.SlotState(
                        profileName = "PlayStation",
                        registered = true,
                        connected = true,
                        connectedName = "PS5",
                    ),
            )
        val hub = buildHub()

        val btSummaries = hub.connections.value.filter { it.kind == ConnectionKind.BLUETOOTH }
        assertEquals(1, btSummaries.size)
        assertEquals("bt:AA:BB", btSummaries[0].id)
        assertEquals(LinkState.Connected, btSummaries[0].live)
        // Detail uses the persisted (friendly) profileName, not the enum name.
        assert(btSummaries[0].detail.startsWith("PlayStation")) {
            "expected friendly profile in detail, got: ${btSummaries[0].detail}"
        }
    }

    @Test
    fun `idle remembered bt host with no registry state stays IDLE`() {
        every { store.rememberedBt() } returns
            listOf(
                RememberedBt(id = "bt:Z", name = "Pad", mac = "Z", profileName = "Xbox"),
            )
        btStatesFlow.value = emptyMap()
        val hub = buildHub()

        val summary = hub.connections.value.first { it.kind == ConnectionKind.BLUETOOTH }
        assertEquals(LinkState.Saved, summary.live)
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
                every { state } returns MutableStateFlow(SessionState.Live)
            }
        every { satellite.get("s:live") } returns liveConn
        every { satellite.get("s:idle") } returns null
        val hub = buildHub()

        hub.autoReconnectAll()

        // connect() now takes a ConnectIntent. autoReconnectAll passes
        // AUTO_RECONNECT (so error events stay silent); the test only cares
        // that connect was/wasn't called for the right server.
        verify(exactly = 0) {
            satellite.connect(match { it.ip == "1.1.1.1" }, ConnectIntent.AUTO_RECONNECT)
        }
        verify {
            satellite.connect(match { it.ip == "2.2.2.2" }, ConnectIntent.AUTO_RECONNECT)
        }
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

    /**
     * Regression: previously the hub combined only on the outer
     * `Map<String, SatelliteConnection>` identity, so a session flipping
     * CONNECTING → CONNECTED inside the same map left the connections list
     * stuck on "Connecting…" until the screen was reopened.
     */
    @Test
    fun `connections re-emits when an inner SatelliteConnection state transitions`() {
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(
                    id = "satellite:1.1.1.1:9876",
                    name = "Pc",
                    ip = "1.1.1.1",
                    udpPort = 9876,
                    pairPort = 9878,
                    httpPort = 9877,
                ),
            )
        val state = MutableStateFlow(SessionState.Linking)
        val conn =
            mockk<SatelliteConnection>(relaxed = true) {
                every { id } returns "satellite:1.1.1.1:9876"
                every { this@mockk.state } returns state
                every { server } returns
                    MutableStateFlow(
                        com.tinkernorth.dish.data.model
                            .DiscoveredServer(name = "Pc", ip = "1.1.1.1", udpPort = 9876, pairPort = 9878, httpPort = 9877),
                    )
                every { slots } returns MutableStateFlow(emptyMap())
            }
        satConnsFlow.value = mapOf("satellite:1.1.1.1:9876" to conn)
        val hub = buildHub()

        // First snapshot: still CONNECTING.
        assertEquals(
            LinkState.Connecting,
            hub.connections.value
                .first { it.id == "satellite:1.1.1.1:9876" }
                .live,
        )

        // Inner state flips to CONNECTED — hub must re-emit even though the
        // outer satellite.connections map is unchanged.
        state.value = SessionState.Live
        scope.testScheduler.runCurrent()

        assertEquals(
            LinkState.Connected,
            hub.connections.value
                .first { it.id == "satellite:1.1.1.1:9876" }
                .live,
        )
    }

    @Test
    fun `connections re-emits when an inner SatelliteConnection drops back to IDLE`() {
        every { store.remembered() } returns
            listOf(
                RememberedSatellite(
                    id = "satellite:1.1.1.1:9876",
                    name = "Pc",
                    ip = "1.1.1.1",
                    udpPort = 9876,
                    pairPort = 9878,
                    httpPort = 9877,
                ),
            )
        val state = MutableStateFlow(SessionState.Live)
        val conn =
            mockk<SatelliteConnection>(relaxed = true) {
                every { id } returns "satellite:1.1.1.1:9876"
                every { this@mockk.state } returns state
                every { server } returns
                    MutableStateFlow(
                        com.tinkernorth.dish.data.model
                            .DiscoveredServer(name = "Pc", ip = "1.1.1.1", udpPort = 9876, pairPort = 9878, httpPort = 9877),
                    )
                every { slots } returns MutableStateFlow(emptyMap())
            }
        satConnsFlow.value = mapOf("satellite:1.1.1.1:9876" to conn)
        val hub = buildHub()
        state.value = SessionState.Idle
        scope.testScheduler.runCurrent()

        assertEquals(
            LinkState.Saved,
            hub.connections.value
                .first { it.id == "satellite:1.1.1.1:9876" }
                .live,
        )
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

    // ── LinkState.Stale lifting from upstream marker sets ─────────────────

    @Test
    fun `stale satellite id lifts an idle remembered satellite to LinkState Stale`() {
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
        staleSatFlow.value = setOf("satellite:10.0.0.1:9876")
        val hub = buildHub()

        // Stale wins over Saved/Ready — the row chip reads "Needs pairing".
        assertEquals(
            LinkState.Stale,
            hub.connections.value
                .first { it.id == "satellite:10.0.0.1:9876" }
                .live,
        )
    }

    @Test
    fun `stale satellite marker does NOT override a live session`() {
        // A satellite that's somehow both live AND in the Stale set (race
        // between markStale and reconnect) should render Connected — the
        // wire-level session takes precedence. The Stale set is for IDLE
        // rows that need to surface "Needs pairing".
        val state = MutableStateFlow(SessionState.Live)
        val conn =
            mockk<SatelliteConnection>(relaxed = true) {
                every { id } returns "satellite:10.0.0.1:9876"
                every { this@mockk.state } returns state
                every { server } returns
                    MutableStateFlow(
                        com.tinkernorth.dish.data.model.DiscoveredServer(
                            name = "A",
                            ip = "10.0.0.1",
                            udpPort = 9876,
                            pairPort = 9878,
                            httpPort = 9877,
                        ),
                    )
                every { slots } returns MutableStateFlow(emptyMap())
            }
        satConnsFlow.value = mapOf("satellite:10.0.0.1:9876" to conn)
        staleSatFlow.value = setOf("satellite:10.0.0.1:9876")
        val hub = buildHub()

        assertEquals(
            LinkState.Connected,
            hub.connections.value
                .first { it.id == "satellite:10.0.0.1:9876" }
                .live,
        )
    }

    @Test
    fun `stale bt id lifts an idle remembered host to LinkState Stale`() {
        every { store.rememberedBt() } returns
            listOf(RememberedBt(id = "bt:AA", name = "Xbox", mac = "AA", profileName = "Xbox"))
        every { bt.state(any()) } returns BluetoothGamepadRegistry.SlotState()
        staleBtFlow.value = mapOf("bt:AA" to com.tinkernorth.dish.ui.bluetooth.BtStaleReason.KEY_MISSING)
        val hub = buildHub()

        assertEquals(
            LinkState.Stale,
            hub.connections.value
                .first { it.id == "bt:AA" }
                .live,
        )
    }

    @Test
    fun `stale bt marker does not override a connected bt host`() {
        // Connected wins over the stale marker — if the host reconnects
        // before the registry's clearStale runs, the chip stays Online.
        every { store.rememberedBt() } returns
            listOf(RememberedBt(id = "bt:AA", name = "Xbox", mac = "AA", profileName = "Xbox"))
        every { bt.state(any()) } returns
            BluetoothGamepadRegistry.SlotState(
                registered = true,
                connected = true,
                connectedName = "Xbox",
            )
        btStatesFlow.value =
            mapOf(
                "bt:AA" to
                    BluetoothGamepadRegistry.SlotState(
                        registered = true,
                        connected = true,
                        connectedName = "Xbox",
                    ),
            )
        staleBtFlow.value = mapOf("bt:AA" to com.tinkernorth.dish.ui.bluetooth.BtStaleReason.KEY_MISSING)
        val hub = buildHub()

        assertEquals(
            LinkState.Connected,
            hub.connections.value
                .first { it.id == "bt:AA" }
                .live,
        )
    }
}
