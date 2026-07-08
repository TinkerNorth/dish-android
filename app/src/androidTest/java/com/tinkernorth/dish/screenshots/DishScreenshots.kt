// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.screenshots

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkernorth.dish.DishApplication
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import com.tinkernorth.dish.source.store.TouchpadModeStore
import com.tinkernorth.dish.ui.connections.ConnectionsActivity
import com.tinkernorth.dish.ui.main.BatteryUi
import com.tinkernorth.dish.ui.main.GamepadOverlayActivity
import com.tinkernorth.dish.ui.main.MainActivity
import com.tinkernorth.dish.ui.main.MainViewModel
import com.tinkernorth.dish.ui.main.TouchpadOverlayActivity
import com.tinkernorth.dish.ui.settings.SettingsActivity
import com.tinkernorth.dish.ui.setup.SetupConnectionActivity
import com.tinkernorth.dish.ui.setup.SetupFlow
import com.tinkernorth.dish.ui.setup.SetupInputActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DishScreenshots {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext
    private val app: DishApplication get() = targetContext.applicationContext as DishApplication
    private val locale: String =
        InstrumentationRegistry.getArguments().getString("testLocale") ?: "en-US"

    private lateinit var satellite: SatelliteConnectionManager
    private lateinit var hub: ConnectionCoordinator
    private lateinit var store: ConnectionStore
    private lateinit var bindingStore: SlotBindingStore
    private lateinit var typeStore: ControllerTypeStore
    private lateinit var batteryStore: BatteryStatusStore
    private lateinit var touchpadStore: TouchpadModeStore
    private lateinit var registry: PhysicalGamepadRegistry

    @Before
    fun grabSingletons() {
        targetContext
            .getSharedPreferences("user_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_welcome_completed", true)
            .putLong("donate_pill_dismissed_at", System.currentTimeMillis())
            .commit()
        registry = app.physicalGamepadRegistry
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                satellite = activity.satellite
                hub = activity.hub
                val vm =
                    androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
                batteryStore = vm.fieldValue("batteryStatusStore") as BatteryStatusStore
                touchpadStore = vm.fieldValue("touchpadModeStore") as TouchpadModeStore
            }
        }
        bindingStore = hub.fieldValue("bindingStore") as SlotBindingStore
        typeStore = hub.fieldValue("typeStore") as ControllerTypeStore
        store = satellite.fieldValue("store") as ConnectionStore
        resetAll()
    }

    private fun resetAll() {
        pushFlow(satellite, "_connections", emptyMap<String, SatelliteConnection>())
        store.remembered().forEach { store.forgetSatellite(it.id) }
        store.rememberedBt().forEach { store.forgetBt(it.id) }
        bindingStore.state.value.keys
            .toList()
            .forEach { bindingStore.unbind(it) }
        typeStore.state.value.keys
            .toList()
            .forEach { typeStore.clear(it.first, it.second) }
        pushFlow(registry, "_devices", emptyMap<Int, PhysicalGamepadRegistry.Device>())
        batteryClear("virtual")
        batteryClear("1001")
        pushFlow(app.wakeStateController, "_streamingSlotCount", 0)
        settle()
    }

    private val gamingServer =
        DiscoveredServer(name = "Gaming PC", ip = "192.168.1.50", machineId = "gaming-pc")
    private val officeServer =
        DiscoveredServer(name = "Office PC", ip = "192.168.1.52", machineId = "office-pc")
    private val livingRoomServer =
        DiscoveredServer(name = "Living Room PC", ip = "192.168.1.51", machineId = "living-room-pc")
    private val gamingId get() = SatelliteConnection.idFor(gamingServer)

    private fun liveSession(server: DiscoveredServer): SatelliteConnection {
        val session =
            SatelliteConnection(
                id = SatelliteConnection.idFor(server),
                server = server,
                scope = MainScope(),
                controllerRepo = satellite.controllerRepo,
            )
        pushFlow(session, "_state", SatelliteSessionState.Live)
        return session
    }

    private fun seedBasicHero() {
        store.rememberSatellite(gamingServer)
        store.rememberSatellite(officeServer)
        pushFlow(satellite, "_connections", mapOf(gamingId to liveSession(gamingServer)))
        touchpadStore.setMode(gamingId, "ds4")
        bindingStore.bind("virtual", gamingId)
        bindingStore.bind("1001", gamingId)
        typeStore.setType(gamingId, "1001", CONTROLLER_TYPE_PLAYSTATION)
        pushFlow(
            registry,
            "_devices",
            mapOf(
                1001 to
                    PhysicalGamepadRegistry.Device(
                        id = 1001,
                        name = "Wired Controller",
                        hasGyro = true,
                        hasRumble = true,
                        isUsbSynthetic = true,
                        vendorId = 0x054C,
                        productId = 0x09CC,
                        pollRateHz = 250,
                        transport = Transport.Usb,
                    ),
            ),
        )
        batteryPut("virtual", 91, BatteryValidator.STATUS_DISCHARGING)
        batteryPut("1001", 78, BatteryValidator.STATUS_DISCHARGING)
        pushFlow(app.wakeStateController, "_streamingSlotCount", 2)
    }

    private fun seedInGame() {
        store.rememberSatellite(gamingServer)
        pushFlow(satellite, "_connections", mapOf(gamingId to liveSession(gamingServer)))
        touchpadStore.setMode(gamingId, "ds4")
        bindingStore.bind("virtual", gamingId)
        batteryPut("virtual", 91, BatteryValidator.STATUS_DISCHARGING)
        pushFlow(app.wakeStateController, "_streamingSlotCount", 1)
    }

    private fun seedMixedConnections() {
        store.rememberSatellite(gamingServer)
        store.rememberSatellite(livingRoomServer)
        store.rememberBt(
            RememberedBt(id = "bt:AA:BB:CC:DD:EE:FF", name = "Steam Deck", mac = "AA:BB:CC:DD:EE:FF", profileName = "Xbox"),
        )
        store.rememberBt(
            RememberedBt(id = "bt:11:22:33:44:55:66", name = "ROG Ally X", mac = "11:22:33:44:55:66", profileName = "Xbox"),
        )
        pushFlow(satellite, "_connections", mapOf(gamingId to liveSession(gamingServer)))
    }

    @Test
    fun shot01_dashboard() {
        seedBasicHero()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle(1500)
            freezeHeroUi(scenario)
            shoot("01_dashboard")
        }
    }

    private fun freezeHeroUi(scenario: ActivityScenario<MainActivity>) {
        waitForScanToFinish()
        pushFlow(satellite, "_discoveredServers", emptyList<DiscoveredServer>())
        settle(600)
        scenario.onActivity { activity ->
            val vm = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
            vm.viewModelScope.cancel()
            val current = vm.uiState.value
            val frozenConnections =
                current.connections.map { summary ->
                    summary.copy(
                        live =
                            if (summary.id == gamingId) {
                                com.tinkernorth.dish.composer.LinkState.Connected
                            } else {
                                com.tinkernorth.dish.composer.LinkState.Saved
                            },
                    )
                }
            val frozenSlots =
                current.slots.map { slot ->
                    slot.copy(
                        boundStatus = frozenConnections.firstOrNull { it.id == slot.boundConnectionId },
                        battery =
                            when (slot.id) {
                                "virtual" -> BatteryUi(level = 91, charging = false)
                                "1001" -> BatteryUi(level = 78, charging = false)
                                else -> slot.battery
                            },
                    )
                }
            pushFlow(vm, "_uiState", current.copy(slots = frozenSlots, connections = frozenConnections))
        }
        settle(600)
    }

    @Test
    fun shot02_setup_input() {
        ActivityScenario.launch(SetupInputActivity::class.java).use {
            settle(1200)
            shoot("02_setup_input")
        }
    }

    @Test
    fun shot03_setup_connection() {
        val intent =
            Intent(targetContext, SetupConnectionActivity::class.java).apply {
                putExtra(SetupFlow.EXTRA_INPUT_TYPE, SetupFlow.INPUT_ONSCREEN)
                putExtra(SetupFlow.EXTRA_SLOT_ID, "virtual")
            }
        ActivityScenario.launch<SetupConnectionActivity>(intent).use {
            settle(1200)
            shoot("03_setup_connection")
        }
    }

    @Test
    fun shot04_connections() {
        seedMixedConnections()
        ActivityScenario.launch(ConnectionsActivity::class.java).use {
            waitForScanToFinish()
            pushFlow(satellite, "_discoveredServers", emptyList<DiscoveredServer>())
            settle(800)
            shoot("04_connections")
        }
    }

    @Test
    fun shot05_slot_detail() {
        seedBasicHero()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle(1500)
            freezeHeroUi(scenario)
            scenario.onActivity { activity ->
                val rv =
                    activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                        targetContext.resources.getIdentifier("rvControllers", "id", targetContext.packageName),
                    )
                rv?.scrollToPosition(1)
            }
            settle(800)
            shoot("05_slot_detail")
        }
    }

    @Test
    fun shot06_settings() {
        seedBasicHero()
        ActivityScenario.launch(SettingsActivity::class.java).use {
            settle(1200)
            shoot("06_settings")
        }
    }

    @Test
    fun shot08_gamepad() {
        seedInGame()
        val intent =
            Intent(targetContext, GamepadOverlayActivity::class.java).apply {
                putExtra("extra_connection_id", gamingId)
                putExtra("extra_use_ps_layout", false)
            }
        ActivityScenario.launch<GamepadOverlayActivity>(intent).use {
            settle(2000)
            shoot("08_gamepad")
        }
    }

    @Test
    fun shot09_touchpad() {
        seedInGame()
        val intent =
            Intent(targetContext, TouchpadOverlayActivity::class.java).apply {
                putExtra("extra_connection_id", gamingId)
                putExtra("extra_slot_id", "virtual")
            }
        ActivityScenario.launch<TouchpadOverlayActivity>(intent).use {
            settle(2000)
            shoot("09_touchpad")
        }
    }

    private fun waitForScanToFinish() {
        val deadline = System.currentTimeMillis() + 20_000
        while (satellite.isScanning.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
    }

    private fun settle(extraMs: Long = 400) {
        instrumentation.waitForIdleSync()
        Thread.sleep(extraMs)
        instrumentation.waitForIdleSync()
    }

    private fun shoot(name: String) {
        val bitmap: Bitmap =
            instrumentation.uiAutomation.takeScreenshot()
                ?: error("takeScreenshot returned null for $name")
        val dir = File(targetContext.filesDir, "screengrab/$locale").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun batteryPut(
        slotId: String,
        level: Int,
        status: Int,
    ) {
        batteryStore.put(slotId, BatteryValidator.BatterySample(level = level, status = status))
    }

    private fun batteryClear(slotId: String) {
        batteryStore.clear(slotId)
    }

    private fun Any.fieldValue(name: String): Any {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val f = cls.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return f.get(this) ?: error("field $name is null on ${javaClass.name}")
            }
            cls = cls.superclass
        }
        error("field $name not found on ${javaClass.name}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun pushFlow(
        owner: Any,
        field: String,
        value: Any?,
    ) {
        (owner.fieldValue(field) as MutableStateFlow<Any?>).value = value
    }
}
