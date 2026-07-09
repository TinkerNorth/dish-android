// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.ui.main.MainActivity

/**
 * Grabs the app's real singletons once per instrumentation process by
 * bouncing through MainActivity's public injection fields: the suite
 * exercises production wiring, so there is no Hilt test component to
 * ask. The welcome flag is seeded first or MainActivity would bounce
 * to setup and finish before the fields can be read.
 */
object AppSingletons {
    lateinit var satellite: SatelliteConnectionManager
        private set

    lateinit var hub: ConnectionCoordinator
        private set

    lateinit var store: ConnectionStore
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
        // Wipe persisted satellites/keys/pins BEFORE the first launch so
        // startup auto-reconnect has no ghost to chase on a dead port left by
        // a prior run. The stores read their initial state from this file
        // when Hilt first constructs them during the launch below.
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
        grabbed = true
    }

    fun resetConnections() {
        grab()
        // Forget every id the manager knows about, from BOTH the live map and
        // the persisted list: a session that failed before it was remembered,
        // or one a death-retry re-added, would otherwise linger and flake the
        // next test. forget() drops key/pin/row synchronously, removes the
        // connection from the map, and neutralises any pending retry (its guard
        // sees the connection gone). The network unpair is fire-and-forget, so
        // this never depends on a fake still listening.
        val ids = (satellite.connections.value.keys + store.remembered().map { it.id }).toSet()
        ids.forEach { id -> satellite.forget(id) }
        await(timeoutMs = 5_000) { satellite.connections.value.isEmpty() }
        // Let any in-flight teardown/retry coroutines observe the removal before
        // the next test starts pairing, so nothing bleeds across the boundary.
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
