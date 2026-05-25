// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.testing

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

class TestLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = Lifecycle.State.INITIALIZED
    }

    fun start() {
        registry.currentState = Lifecycle.State.STARTED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
