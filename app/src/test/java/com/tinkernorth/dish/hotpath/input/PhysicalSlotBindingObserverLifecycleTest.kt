// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.moonlight.MoonlightConnectionManager
import com.tinkernorth.dish.source.lights.FrameworkLightGateway
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

// The observer's own lifecycle, as far as the JVM can follow it: with no pads present nothing
// reaches PhysicalSlotNative, whose class init loads the native library and so cannot be mocked.
class PhysicalSlotBindingObserverLifecycleTest {
    private val deviceFlow = MutableStateFlow<Map<Int, PhysicalGamepadRegistry.Device>>(emptyMap())
    private val registry = mockk<PhysicalGamepadRegistry> { every { devices } returns deviceFlow }
    private val hub =
        mockk<ConnectionCoordinator> {
            every { bindings } returns MutableStateFlow(emptyMap())
            every { connections } returns MutableStateFlow(emptyList<ConnectionSummary>())
        }
    private val satellite =
        mockk<SatelliteConnectionManager> {
            every { connections } returns MutableStateFlow(emptyMap<String, SatelliteConnection>())
        }
    private val observer =
        PhysicalSlotBindingObserver(
            registry = registry,
            hub = hub,
            satellite = satellite,
            bt = mockk<BluetoothGamepadRegistry>(),
            moonlight = mockk<MoonlightConnectionManager>(),
            frameworkLights = mockk<FrameworkLightGateway>(relaxed = true),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    private val owner = mockk<LifecycleOwner>()

    @Test
    fun `onStart subscribes to the registry once`() {
        observer.onStart(owner)
        assertEquals(1, deviceFlow.subscriptionCount.value)
    }

    @Test
    fun `onStart twice keeps one collector`() {
        observer.onStart(owner)
        observer.onStart(owner)
        assertEquals(1, deviceFlow.subscriptionCount.value)
    }
}
