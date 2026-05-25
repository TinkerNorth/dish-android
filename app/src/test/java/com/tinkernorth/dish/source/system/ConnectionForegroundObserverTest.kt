// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.tinkernorth.dish.composer.ConnectionHub
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ConnectionForegroundObserverTest {
    private class TestOwner : LifecycleOwner {
        // createUnsafe skips the main-thread check so the test runs without android.os.Looper mocks.
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `ON_START triggers autoReconnectAll on the hub`() {
        val hub = mockk<ConnectionHub>(relaxed = true)
        val owner = TestOwner()
        val observer = ConnectionForegroundObserver(hub)
        owner.registry.addObserver(observer)

        owner.registry.currentState = Lifecycle.State.STARTED

        verify(exactly = 1) { hub.autoReconnectAll() }
    }

    @Test
    fun `ON_STOP does not call autoReconnectAll`() {
        val hub = mockk<ConnectionHub>(relaxed = true)
        val owner = TestOwner()
        val observer = ConnectionForegroundObserver(hub)
        owner.registry.addObserver(observer)
        owner.registry.currentState = Lifecycle.State.STARTED

        owner.registry.currentState = Lifecycle.State.CREATED

        verify(exactly = 1) { hub.autoReconnectAll() }
    }

    @Test
    fun `repeated ON_START triggers autoReconnectAll each time the app comes back`() {
        val hub = mockk<ConnectionHub>(relaxed = true)
        val owner = TestOwner()
        val observer = ConnectionForegroundObserver(hub)
        owner.registry.addObserver(observer)

        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.STARTED

        verify(exactly = 2) { hub.autoReconnectAll() }
    }
}
