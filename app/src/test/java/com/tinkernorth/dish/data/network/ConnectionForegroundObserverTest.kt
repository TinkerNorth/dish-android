// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * The observer is the process-wide answer to the original bug: when the app
 * returns to the foreground, any stale BT/satellite session needs to be
 * rebuilt so input keeps flowing. We drive the observer directly against a
 * [LifecycleRegistry] via [LifecycleRegistry.createUnsafe] so the test is
 * not tied to a Looper.
 */
class ConnectionForegroundObserverTest {
    private class TestOwner : LifecycleOwner {
        // createUnsafe skips the main-thread check so the test runs on a
        // plain JVM without android.os.Looper mocks.
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

        owner.registry.currentState = Lifecycle.State.CREATED // emits ON_STOP

        // Only the ON_START call was expected; a second call from ON_STOP
        // would show up as exactly=2.
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
