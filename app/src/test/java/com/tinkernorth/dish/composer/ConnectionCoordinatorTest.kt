// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.repository.RememberedSatellite
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.BtStaleReason
import com.tinkernorth.dish.source.connection.ConnectIntent
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.system.LocalNetworkAccess
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {
    private lateinit var satellite: SatelliteConnectionManager
    private lateinit var bt: BluetoothGamepadRegistry
    private lateinit var store: ConnectionStore
    private lateinit var hostFeaturesStore: com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
    private lateinit var hostRuntimeStore: com.tinkernorth.dish.source.store.SatelliteHostRuntimeStore
    private lateinit var gamepadRegistry: com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
    private lateinit var scope: TestScope

    private val satConnsFlow = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())
    private val btStatesFlow = MutableStateFlow<Map<String, BluetoothGamepadRegistry.SlotState>>(emptyMap())
    private val registryDevicesFlow =
        MutableStateFlow<Map<Int, com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry.Device>>(emptyMap())

    private val discoveredFlow =
        MutableStateFlow<List<com.tinkernorth.dish.core.model.DiscoveredServer>>(emptyList())

    private val staleSatFlow = MutableStateFlow<Set<String>>(emptySet())
    private val staleBtFlow = MutableStateFlow<Map<String, com.tinkernorth.dish.source.bluetooth.BtStaleReason>>(emptyMap())

    private val satEntriesFlow = MutableStateFlow<List<RememberedSatellite>>(emptyList())
    private val btEntriesFlow = MutableStateFlow<List<RememberedBt>>(emptyList())

    @Before
    fun setUp() {
        satellite = mockk(relaxed = true)
        bt = mockk(relaxed = true)
        store = mockk(relaxed = true)
        hostFeaturesStore = mockk(relaxed = true)
        hostRuntimeStore = mockk(relaxed = true)
        gamepadRegistry = mockk(relaxed = true)
        every { gamepadRegistry.devices } returns registryDevicesFlow
        registryDevicesFlow.value = emptyMap()
        satEntriesFlow.value = emptyList()
        btEntriesFlow.value = emptyList()
        every { satellite.connections } returns satConnsFlow
        every { satellite.discoveredServers } returns discoveredFlow
        every { satellite.staleSatelliteIds } returns staleSatFlow
        every { bt.states } returns btStatesFlow
        every { bt.staleBtIds } returns staleBtFlow
        // The composer derives from the observable flows; the hub still reads remembered() directly,
        // so both are backed by the same per-test flow value.
        every { store.rememberedSatellitesFlow } returns satEntriesFlow
        every { store.rememberedBtFlow } returns btEntriesFlow
        every { store.remembered() } answers { satEntriesFlow.value }
        every { store.rememberedBt() } answers { btEntriesFlow.value }
        scope = TestScope(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun fakeStringContext(): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getString(com.tinkernorth.dish.R.string.bt_transient_acquiring) } returns
            "Acquiring HID profile…"
        every { ctx.getString(com.tinkernorth.dish.R.string.bt_transient_ready_to_pair) } returns
            "Ready to pair. Find this device on your host"
        every { ctx.getString(com.tinkernorth.dish.R.string.bt_transient_idle) } returns "Idle"
        every { ctx.getString(com.tinkernorth.dish.R.string.default_bluetooth_gamepad_label) } returns
            "Bluetooth gamepad"
        every {
            ctx.getString(com.tinkernorth.dish.R.string.bt_row_detail, any<Any>(), any<Any>())
        } answers {
            val args = invocation.args[1] as Array<*>
            "${args[0]} • ${args[1]}"
        }
        every {
            ctx.getString(com.tinkernorth.dish.R.string.discovered_row_detail, any<Any>(), any<Any>())
        } answers {
            val args = invocation.args[1] as Array<*>
            "${args[0]} • UDP ${args[1]}"
        }
        return ctx
    }

    private fun buildHub(): ConnectionCoordinator {
        val bindingStore =
            com.tinkernorth.dish.source.store
                .SlotBindingStore()
        val typeStore =
            com.tinkernorth.dish.source.store
                .ControllerTypeStore()
        val composer =
            ConnectionsComposer(
                context = fakeStringContext(),
                satellite = satellite,
                bt = bt,
                store = store,
                bindingStore = bindingStore,
                typeStore = typeStore,
                scope = scope,
            )
        val hub =
            ConnectionCoordinator(
                satellite = satellite,
                bt = bt,
                store = store,
                bindingStore = bindingStore,
                typeStore = typeStore,
                hostFeaturesStore = hostFeaturesStore,
                hostRuntimeStore = hostRuntimeStore,
                composer = composer,
                gamepadRegistry = gamepadRegistry,
                context = mockk(relaxed = true),
            )
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
    fun `forgetConnection clears the binding and type then forgets the satellite`() {
        val hub = buildHub()
        hub.bind("slot-A", "sat:1", CONTROLLER_TYPE_XBOX)
        hub.setSatelliteControllerType("sat:1", "slot-A", CONTROLLER_TYPE_PLAYSTATION)
        scope.testScheduler.runCurrent()
        assertEquals(CONTROLLER_TYPE_PLAYSTATION, hub.satTypes.value["sat:1" to "slot-A"])

        hub.forgetConnection("sat:1")
        scope.testScheduler.runCurrent()

        assertNull(hub.bindings.value["slot-A"])
        assertNull(hub.satTypes.value["sat:1" to "slot-A"])
        verify { satellite.forget("sat:1") }
        verify { hostFeaturesStore.clearConnection("sat:1") }
        verify { hostRuntimeStore.clearConnection("sat:1") }
    }

    @Test
    fun `forgetConnection forgets a remembered bluetooth host`() {
        btEntriesFlow.value =
            listOf(RememberedBt(id = "bt:AA", name = "Pad", mac = "AA", profileName = "XBOX"))
        val hub = buildHub()

        hub.forgetConnection("bt:AA")

        verify { store.forgetBt("bt:AA") }
    }

    @Test
    fun `remembered satellite entries appear as IDLE summaries`() {
        satEntriesFlow.value =
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
        btEntriesFlow.value =
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
        btEntriesFlow.value =
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

    @Test
    fun `acquiring on a remembered bt host shows CONNECTING`() {
        btEntriesFlow.value =
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
        btEntriesFlow.value =
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
        assert(btSummaries[0].detail.startsWith("PlayStation")) {
            "expected friendly profile in detail, got: ${btSummaries[0].detail}"
        }
    }

    @Test
    fun `idle remembered bt host with no registry state stays IDLE`() {
        btEntriesFlow.value =
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
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind(slotId = "slot-A", connectionId = "s:1", controllerType = CONTROLLER_TYPE_XBOX)
        scope.testScheduler.runCurrent()

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
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1", CONTROLLER_TYPE_XBOX)
        hub.bind("slot-B", "s:1", CONTROLLER_TYPE_XBOX)
        scope.testScheduler.runCurrent()

        assertEquals(
            mapOf("slot-A" to "s:1", "slot-B" to "s:1"),
            hub.bindings.value,
        )
        val summary = hub.connections.value.first { it.id == "s:1" }
        assertEquals(setOf("slot-A", "slot-B"), summary.boundSlotIds.toSet())
    }

    @Test
    fun `bind evicts the prior slot for a bluetooth host`() {
        btEntriesFlow.value =
            listOf(
                RememberedBt(id = "bt:X", name = "Xbox", mac = "X", profileName = "XBOX"),
            )
        val hub = buildHub()

        hub.bind("slot-A", "bt:X", CONTROLLER_TYPE_XBOX)
        hub.bind("slot-B", "bt:X", CONTROLLER_TYPE_XBOX)

        // BT is single-host: bind evicts the prior slot.
        assertEquals(mapOf("slot-B" to "bt:X"), hub.bindings.value)
    }

    @Test
    fun `binding a slot to a new satellite moves the binding and drops the prior type`() {
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
                RememberedSatellite(id = "s:2", name = "B", ip = "2", udpPort = 4, pairPort = 5, httpPort = 6),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1", CONTROLLER_TYPE_PLAYSTATION)
        hub.bind("slot-A", "s:2", CONTROLLER_TYPE_XBOX)

        assertEquals(mapOf("slot-A" to "s:2"), hub.bindings.value)
        assertNull(hub.satTypes.value["s:1" to "slot-A"])
        assertEquals(CONTROLLER_TYPE_XBOX, hub.satTypes.value["s:2" to "slot-A"])
    }

    @Test
    fun `unbind removes the binding and its type`() {
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1", CONTROLLER_TYPE_XBOX)
        hub.unbind("slot-A")

        assertNull(hub.bindings.value["slot-A"])
        assertNull(hub.satTypes.value["s:1" to "slot-A"])
    }

    @Test
    fun `bind records the caller's type - the descriptor travels with the bind`() {
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1", CONTROLLER_TYPE_PLAYSTATION)
        scope.testScheduler.runCurrent()

        val summary = hub.connections.value.first { it.id == "s:1" }
        assertEquals(CONTROLLER_TYPE_PLAYSTATION, summary.satelliteControllerTypes["slot-A"])
    }

    @Test
    fun `bind refuses a numeric slot id the registry no longer knows (zombie guard)`() {
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        registryDevicesFlow.value = emptyMap() // device 42 is gone
        val hub = buildHub()

        val bound = hub.bind("42", "s:1", CONTROLLER_TYPE_XBOX)

        assertEquals(false, bound)
        assertNull(hub.bindings.value["42"])
        assertNull(hub.satTypes.value["s:1" to "42"])
    }

    @Test
    fun `bind accepts a numeric slot id the registry knows`() {
        val satConn = mockk<SatelliteConnection>(relaxed = true)
        every { satellite.get("s:1") } returns satConn
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        registryDevicesFlow.value =
            mapOf(
                42 to
                    com.tinkernorth.dish.hotpath.input
                        .PhysicalGamepadRegistry
                        .Device(id = 42, name = "Pad"),
            )
        val hub = buildHub()

        assertTrue(hub.bind("42", "s:1", CONTROLLER_TYPE_XBOX))
        assertEquals("s:1", hub.bindings.value["42"])
    }

    @Test
    fun `bindClaimedSynthetic adopts the twin's remembered type for a fresh claim`() {
        val satConn = mockk<SatelliteConnection>(relaxed = true)
        every { satellite.get("satellite:mid:m1") } returns satConn
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(
                    id = "satellite:mid:m1",
                    name = "Pc",
                    ip = "1.1.1.1",
                    udpPort = 9876,
                    pairPort = 9443,
                    httpPort = 9443,
                    machineId = "m1",
                ),
            )
        val state = MutableStateFlow(SatelliteSessionState.Live)
        val liveConn =
            mockk<SatelliteConnection>(relaxed = true) {
                every { id } returns "satellite:mid:m1"
                every { this@mockk.state } returns state
                every { server } returns
                    MutableStateFlow(
                        DiscoveredServer(name = "Pc", ip = "1.1.1.1", udpPort = 9876, machineId = "m1"),
                    )
                every { slots } returns MutableStateFlow(emptyMap())
            }
        satConnsFlow.value = mapOf("satellite:mid:m1" to liveConn)
        val hub = buildHub()
        scope.testScheduler.runCurrent()

        // The user's choice for the framework twin (never bound) is remembered
        // in the type store; the claim must adopt it, not default Xbox.
        hub.setSatelliteControllerType("satellite:mid:m1", "7", CONTROLLER_TYPE_PLAYSTATION)
        registryDevicesFlow.value =
            mapOf(
                99 to
                    com.tinkernorth.dish.hotpath.input
                        .PhysicalGamepadRegistry
                        .Device(id = 99, name = "Pad", isUsbSynthetic = true),
            )
        hub.bindClaimedSynthetic(twinSlotId = "7", syntheticSlotId = "99")
        scope.testScheduler.runCurrent()

        assertEquals("satellite:mid:m1", hub.bindings.value["99"])
        assertEquals(CONTROLLER_TYPE_PLAYSTATION, hub.satTypes.value["satellite:mid:m1" to "99"])
    }

    @Test
    fun `setSatelliteControllerType updates the summary through the type store`() {
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-A", "s:1", CONTROLLER_TYPE_XBOX)
        hub.setSatelliteControllerType("s:1", "slot-A", CONTROLLER_TYPE_PLAYSTATION)
        scope.testScheduler.runCurrent()

        val summary = hub.connections.value.first { it.id == "s:1" }
        assertEquals(CONTROLLER_TYPE_PLAYSTATION, summary.satelliteControllerTypes["slot-A"])
    }

    @Test
    fun `summary returns the entry matching id or null`() {
        satEntriesFlow.value =
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
        satEntriesFlow.value = listOf(live, idle)
        val liveConn =
            mockk<SatelliteConnection>(relaxed = true) {
                every { state } returns MutableStateFlow(SatelliteSessionState.Live)
            }
        every { satellite.get("s:live") } returns liveConn
        every { satellite.get("s:idle") } returns null
        val hub = buildHub()

        hub.autoReconnectAll()

        verify(exactly = 0) {
            satellite.connect(match { it.ip == "1.1.1.1" }, ConnectIntent.AUTO_RECONNECT)
        }
        verify {
            satellite.connect(match { it.ip == "2.2.2.2" }, ConnectIntent.AUTO_RECONNECT)
        }
    }

    @Test
    fun `autoReconnectAll skips satellites when local network access is missing`() {
        mockkObject(LocalNetworkAccess)
        every { LocalNetworkAccess.isGranted(any(), any()) } returns false
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:idle", name = "I", ip = "2.2.2.2", udpPort = 4, pairPort = 5, httpPort = 6),
            )
        every { satellite.get("s:idle") } returns null
        val hub = buildHub()

        hub.autoReconnectAll()

        verify(exactly = 0) { satellite.connect(any(), ConnectIntent.AUTO_RECONNECT) }
    }

    @Test
    fun `autoReconnectAll tries the first remembered bt host when none are live`() {
        btEntriesFlow.value =
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
    fun `connections re-emits when an inner SatelliteConnection state transitions`() {
        satEntriesFlow.value =
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
        val state = MutableStateFlow(SatelliteSessionState.Linking)
        val conn =
            mockk<SatelliteConnection>(relaxed = true) {
                every { id } returns "satellite:1.1.1.1:9876"
                every { this@mockk.state } returns state
                every { server } returns
                    MutableStateFlow(
                        com.tinkernorth.dish.core.model.DiscoveredServer(
                            name = "Pc",
                            ip = "1.1.1.1",
                            udpPort = 9876,
                            pairPort = 9878,
                            httpPort = 9877,
                        ),
                    )
                every { slots } returns MutableStateFlow(emptyMap())
            }
        satConnsFlow.value = mapOf("satellite:1.1.1.1:9876" to conn)
        val hub = buildHub()

        assertEquals(
            LinkState.Connecting,
            hub.connections.value
                .first { it.id == "satellite:1.1.1.1:9876" }
                .live,
        )

        state.value = SatelliteSessionState.Live
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
        satEntriesFlow.value =
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
        val state = MutableStateFlow(SatelliteSessionState.Live)
        val conn =
            mockk<SatelliteConnection>(relaxed = true) {
                every { id } returns "satellite:1.1.1.1:9876"
                every { this@mockk.state } returns state
                every { server } returns
                    MutableStateFlow(
                        com.tinkernorth.dish.core.model.DiscoveredServer(
                            name = "Pc",
                            ip = "1.1.1.1",
                            udpPort = 9876,
                            pairPort = 9878,
                            httpPort = 9877,
                        ),
                    )
                every { slots } returns MutableStateFlow(emptyMap())
            }
        satConnsFlow.value = mapOf("satellite:1.1.1.1:9876" to conn)
        val hub = buildHub()
        state.value = SatelliteSessionState.Idle
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
        btEntriesFlow.value =
            listOf(
                RememberedBt(id = "bt:A", name = "A", mac = "A", profileName = "XBOX"),
            )
        every { bt.state("bt:A") } returns BluetoothGamepadRegistry.SlotState(registered = true)
        val hub = buildHub()

        hub.autoReconnectAll()

        verify(exactly = 0) { bt.tryAutoReconnect(any()) }
    }

    @Test
    fun `stale satellite id lifts an idle remembered satellite to LinkState Stale`() {
        satEntriesFlow.value =
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

        assertEquals(
            LinkState.Stale,
            hub.connections.value
                .first { it.id == "satellite:10.0.0.1:9876" }
                .live,
        )
    }

    @Test
    fun `stale satellite marker does NOT override a live session`() {
        val state = MutableStateFlow(SatelliteSessionState.Live)
        val conn =
            mockk<SatelliteConnection>(relaxed = true) {
                every { id } returns "satellite:10.0.0.1:9876"
                every { this@mockk.state } returns state
                every { server } returns
                    MutableStateFlow(
                        com.tinkernorth.dish.core.model.DiscoveredServer(
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
        btEntriesFlow.value =
            listOf(RememberedBt(id = "bt:AA", name = "Xbox", mac = "AA", profileName = "Xbox"))
        every { bt.state(any()) } returns BluetoothGamepadRegistry.SlotState()
        staleBtFlow.value = mapOf("bt:AA" to com.tinkernorth.dish.source.bluetooth.BtStaleReason.KEY_MISSING)
        val hub = buildHub()

        assertEquals(
            LinkState.Stale,
            hub.connections.value
                .first { it.id == "bt:AA" }
                .live,
        )
    }

    @Test
    fun `migrateSlotBinding carries binding and type to the new slot id`() {
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-fw", "s:1", CONTROLLER_TYPE_XBOX)
        hub.setSatelliteControllerType("s:1", "slot-fw", CONTROLLER_TYPE_PLAYSTATION)
        scope.testScheduler.runCurrent()

        hub.migrateSlotBinding("slot-fw", "slot-usb")
        scope.testScheduler.runCurrent()

        assertEquals(mapOf("slot-usb" to "s:1"), hub.bindings.value)
        val summary = hub.connections.value.first { it.id == "s:1" }
        assertEquals(CONTROLLER_TYPE_PLAYSTATION, summary.satelliteControllerTypes["slot-usb"])
        assertNull(summary.satelliteControllerTypes["slot-fw"])
    }

    @Test
    fun `migrateSlotBinding re-keys the binding in one emission so the topology diff reads a rename`() {
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()
        hub.bind("slot-fw", "s:1", CONTROLLER_TYPE_XBOX)

        // Unconfined collector: sees every distinct value synchronously, so a two-step
        // unbind+bind implementation could not hide behind StateFlow conflation.
        val observed = mutableListOf<Map<String, String>>()
        val job =
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                hub.bindings.collect { observed += it }
            }

        hub.migrateSlotBinding("slot-fw", "slot-usb")
        job.cancel()

        assertTrue(observed.none { "slot-fw" in it && "slot-usb" in it })
        assertTrue(observed.none { "slot-fw" !in it && "slot-usb" !in it })
    }

    @Test
    fun `migrateSlotBinding does nothing when the source slot is unbound`() {
        val hub = buildHub()

        hub.migrateSlotBinding("ghost", "slot-usb")
        scope.testScheduler.runCurrent()

        assertEquals(emptyMap<String, String>(), hub.bindings.value)
    }

    @Test
    fun `stale bt marker does not override a connected bt host`() {
        btEntriesFlow.value =
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
        staleBtFlow.value = mapOf("bt:AA" to com.tinkernorth.dish.source.bluetooth.BtStaleReason.KEY_MISSING)
        val hub = buildHub()

        assertEquals(
            LinkState.Connected,
            hub.connections.value
                .first { it.id == "bt:AA" }
                .live,
        )
    }

    private fun liveConn(
        id: String,
        ip: String,
    ): SatelliteConnection =
        mockk(relaxed = true) {
            every { this@mockk.id } returns id
            every { state } returns MutableStateFlow(SatelliteSessionState.Live)
            every { server } returns
                MutableStateFlow(DiscoveredServer(name = id, ip = ip, udpPort = 1, pairPort = 2, httpPort = 3))
            every { slots } returns MutableStateFlow(emptyMap())
        }

    @Test
    fun `bindClaimedSynthetic carries a bound twin's binding and type to the synthetic slot`() {
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bind("slot-fw", "s:1", CONTROLLER_TYPE_PLAYSTATION)
        scope.testScheduler.runCurrent()

        hub.bindClaimedSynthetic(twinSlotId = "slot-fw", syntheticSlotId = "-1000")
        scope.testScheduler.runCurrent()

        assertEquals(mapOf("-1000" to "s:1"), hub.bindings.value)
        assertEquals(CONTROLLER_TYPE_PLAYSTATION, hub.satTypes.value["s:1" to "-1000"])
    }

    // The claim registers the synthetic in the gamepad registry BEFORE the
    // bind (UsbGamepadManager.doClaim ordering); the seeding below mirrors that.
    private fun seedSyntheticInRegistry(id: Int) {
        registryDevicesFlow.value =
            mapOf(
                id to
                    com.tinkernorth.dish.hotpath.input
                        .PhysicalGamepadRegistry
                        .Device(id = id, name = "Pad", isUsbSynthetic = true),
            )
    }

    @Test
    fun `bindClaimedSynthetic auto-binds to the sole live satellite when the twin was unbound`() {
        val conn = liveConn("s:1", "1.1.1.1")
        every { satellite.get("s:1") } returns conn
        satConnsFlow.value = mapOf("s:1" to conn)
        seedSyntheticInRegistry(-1000)
        val hub = buildHub()

        hub.bindClaimedSynthetic(twinSlotId = null, syntheticSlotId = "-1000")
        scope.testScheduler.runCurrent()

        assertEquals(mapOf("-1000" to "s:1"), hub.bindings.value)
    }

    @Test
    fun `bindClaimedSynthetic with a never-bound twin still falls back to the sole live satellite`() {
        val conn = liveConn("s:1", "1.1.1.1")
        every { satellite.get("s:1") } returns conn
        satConnsFlow.value = mapOf("s:1" to conn)
        seedSyntheticInRegistry(-1000)
        val hub = buildHub()

        hub.bindClaimedSynthetic(twinSlotId = "ghost-twin", syntheticSlotId = "-1000")
        scope.testScheduler.runCurrent()

        assertEquals(mapOf("-1000" to "s:1"), hub.bindings.value)
    }

    @Test
    fun `bindClaimedSynthetic leaves the device unbound when no satellite is live`() {
        satEntriesFlow.value =
            listOf(
                RememberedSatellite(id = "s:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
            )
        val hub = buildHub()

        hub.bindClaimedSynthetic(twinSlotId = null, syntheticSlotId = "-1000")
        scope.testScheduler.runCurrent()

        assertEquals(emptyMap<String, String>(), hub.bindings.value)
    }

    @Test
    fun `bindClaimedSynthetic leaves the device unbound when more than one satellite is live`() {
        val a = liveConn("s:1", "1.1.1.1")
        val b = liveConn("s:2", "2.2.2.2")
        every { satellite.get("s:1") } returns a
        every { satellite.get("s:2") } returns b
        satConnsFlow.value = mapOf("s:1" to a, "s:2" to b)
        val hub = buildHub()

        hub.bindClaimedSynthetic(twinSlotId = null, syntheticSlotId = "-1000")
        scope.testScheduler.runCurrent()

        assertEquals(emptyMap<String, String>(), hub.bindings.value)
    }
}
