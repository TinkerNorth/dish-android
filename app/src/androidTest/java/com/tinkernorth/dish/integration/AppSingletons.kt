// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import com.tinkernorth.dish.ui.main.MainActivity

/**
 * The app's real @Singleton graph, grabbed once per instrumentation process by
 * bouncing through MainActivity's public injection fields and reflecting the
 * rest off the manager/hub/composer they hold. The suite exercises production
 * wiring, so there is no Hilt test component to ask. Prefs are wiped and the
 * welcome flag set before the first launch so MainActivity does not bounce to
 * setup and startup auto-reconnect has no ghost to chase.
 */
object AppSingletons {
    lateinit var satellite: SatelliteConnectionManager
        private set

    lateinit var hub: ConnectionCoordinator
        private set

    lateinit var store: ConnectionStore
        private set

    lateinit var bindingStore: SlotBindingStore
        private set

    lateinit var typeStore: ControllerTypeStore
        private set

    lateinit var catalogRepo: SatelliteCatalogRepository
        private set

    lateinit var hostFeaturesStore: SatelliteHostFeaturesStore
        private set

    lateinit var capabilityComposer: CapabilityComposer
        private set

    private var grabbed = false

    @Synchronized
    fun grab() {
        if (grabbed) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context
            .getSharedPreferences("user_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_welcome_completed", true)
            .putLong("donate_pill_dismissed_at", System.currentTimeMillis())
            .commit()
        context
            .getSharedPreferences("connection_store", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                satellite = activity.satellite
                hub = activity.hub
            }
        }
        store = satellite.fieldValue("store") as ConnectionStore
        bindingStore = hub.fieldValue("bindingStore") as SlotBindingStore
        typeStore = hub.fieldValue("typeStore") as ControllerTypeStore
        capabilityComposer =
            satellite.fieldValue("capabilityProvider").let { provider ->
                provider.javaClass
                    .getMethod("get")
                    .invoke(provider) as CapabilityComposer
            }
        catalogRepo = capabilityComposer.fieldValue("catalogRepo") as SatelliteCatalogRepository
        hostFeaturesStore = capabilityComposer.fieldValue("hostFeatures") as SatelliteHostFeaturesStore
        grabbed = true
    }

    fun resetConnections() {
        grab()
        // Forget every id the manager knows about, from BOTH the live map and
        // the persisted list: a session that failed before it was remembered,
        // or one a death-retry re-added, would otherwise linger and flake the
        // next test. forget() drops key/pin/row synchronously, removes the
        // connection from the map, and neutralises any pending retry.
        val ids = (satellite.connections.value.keys + store.remembered().map { it.id }).toSet()
        ids.forEach { id -> satellite.forget(id) }
        await(timeoutMs = 5_000) { satellite.connections.value.isEmpty() }
        bindingStore.state.value.keys
            .toList()
            .forEach { bindingStore.unbind(it) }
        Thread.sleep(300)
    }

    fun await(
        timeoutMs: Long = 25_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    fun Any.fieldValue(name: String): Any {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field.get(this) ?: error("field $name is null on ${javaClass.name}")
            }
            cls = cls.superclass
        }
        error("field $name not found on ${javaClass.name}")
    }
}
