package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ConnectionHub] binding semantics. Upstream flows from
 * [WifiConnectionManager] and [BluetoothGamepadRegistry] are driven with
 * [MutableStateFlow] stubs so the hub's `buildSummaries` logic can be exercised
 * without spinning real sockets or Bluetooth stacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHubTest {

    private lateinit var wifi: WifiConnectionManager
    private lateinit var bt: BluetoothGamepadRegistry
    private lateinit var store: ConnectionStore
    private lateinit var scope: TestScope

    private val wifiConnsFlow = MutableStateFlow<Map<String, WifiConnection>>(emptyMap())
    private val btStatesFlow = MutableStateFlow<Map<String, BluetoothGamepadRegistry.SlotState>>(emptyMap())

    @Before
    fun setUp() {
        wifi = mockk(relaxed = true)
        bt = mockk(relaxed = true)
        store = mockk(relaxed = true)
        every { wifi.connections } returns wifiConnsFlow
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
        val hub = ConnectionHub(wifi, bt, store, scope)
        scope.testScheduler.runCurrent()
        return hub
    }

    @Test
    fun `initial connections list is empty when no sources have data`() = runTest {
        val hub = buildHub()
        assertEquals(emptyList<ConnectionSummary>(), hub.connections.value)
        assertEquals(emptyMap<String, String>(), hub.bindings.value)
    }

    @Test
    fun `remembered wifi entries appear as IDLE summaries`() {
        every { store.remembered() } returns listOf(
            RememberedWifi(
                id = "wifi:10.0.0.1:9876", name = "A", ip = "10.0.0.1",
                udpPort = 9876, pairPort = 9878, httpPort = 9877,
            ),
        )
        val hub = buildHub()

        val summaries = hub.connections.value
        assertEquals(1, summaries.size)
        assertEquals(ConnectionKind.WIFI, summaries[0].kind)
        assertEquals(ConnectionLive.IDLE, summaries[0].live)
        assertEquals("wifi:10.0.0.1:9876", summaries[0].id)
    }

    @Test
    fun `remembered bt entries surface their profile in detail`() {
        every { store.rememberedBt() } returns listOf(
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
        every { store.rememberedBt() } returns listOf(
            RememberedBt(id = "bt:X", name = "Xbox", mac = "X", profileName = "XBOX"),
        )
        btStatesFlow.value = mapOf(
            "bt:X" to BluetoothGamepadRegistry.SlotState(registered = true, connected = true),
        )
        val hub = buildHub()

        val summary = hub.connections.value.first { it.kind == ConnectionKind.BLUETOOTH }
        assertEquals(ConnectionLive.CONNECTED, summary.live)
    }

    @Test
    fun `bind sets slot to connection and binding is reflected in summary`() {
        every { store.remembered() } returns listOf(
            RememberedWifi(id = "w:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
        )
        val hub = buildHub()

        hub.bind(slotId = "slot-A", connectionId = "w:1")

        assertEquals(mapOf("slot-A" to "w:1"), hub.bindings.value)
        assertEquals("slot-A", hub.connections.value.first().boundSlotId)
    }

    @Test
    fun `bind evicts a prior slot holding the same connection`() {
        every { store.remembered() } returns listOf(
            RememberedWifi(id = "w:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
        )
        val hub = buildHub()

        hub.bind("slot-A", "w:1")
        hub.bind("slot-B", "w:1")

        assertEquals(mapOf("slot-B" to "w:1"), hub.bindings.value)
    }

    @Test
    fun `unbind removes the binding and detaches the wifi slot`() {
        val wifiConn = mockk<WifiConnection>(relaxed = true)
        every { wifi.get("w:1") } returns wifiConn
        every { store.remembered() } returns listOf(
            RememberedWifi(id = "w:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
        )
        val hub = buildHub()

        hub.bind("slot-A", "w:1")
        hub.unbind("slot-A")

        assertNull(hub.bindings.value["slot-A"])
        verify { wifiConn.detachSlot() }
    }

    @Test
    fun `summary returns the entry matching id or null`() {
        every { store.remembered() } returns listOf(
            RememberedWifi(id = "w:1", name = "A", ip = "1", udpPort = 1, pairPort = 2, httpPort = 3),
        )
        val hub = buildHub()

        assertEquals("w:1", hub.summary("w:1")?.id)
        assertNull(hub.summary("missing"))
    }

    @Test
    fun `autoReconnectAll connects each remembered wifi that is not live`() {
        val live = RememberedWifi(id = "w:live", name = "L", ip = "1.1.1.1", udpPort = 1, pairPort = 2, httpPort = 3)
        val idle = RememberedWifi(id = "w:idle", name = "I", ip = "2.2.2.2", udpPort = 4, pairPort = 5, httpPort = 6)
        every { store.remembered() } returns listOf(live, idle)
        val liveConn = mockk<WifiConnection>(relaxed = true) {
            every { state } returns MutableStateFlow(WifiState.CONNECTED)
        }
        every { wifi.get("w:live") } returns liveConn
        every { wifi.get("w:idle") } returns null
        val hub = buildHub()

        hub.autoReconnectAll()

        verify(exactly = 0) { wifi.connect(match { it.ip == "1.1.1.1" }) }
        verify { wifi.connect(match { it.ip == "2.2.2.2" }) }
    }

    @Test
    fun `autoReconnectAll tries the first remembered bt host when none are live`() {
        every { store.rememberedBt() } returns listOf(
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
        every { store.rememberedBt() } returns listOf(
            RememberedBt(id = "bt:A", name = "A", mac = "A", profileName = "XBOX"),
        )
        every { bt.state("bt:A") } returns BluetoothGamepadRegistry.SlotState(registered = true)
        val hub = buildHub()

        hub.autoReconnectAll()

        verify(exactly = 0) { bt.tryAutoReconnect(any()) }
    }
}
