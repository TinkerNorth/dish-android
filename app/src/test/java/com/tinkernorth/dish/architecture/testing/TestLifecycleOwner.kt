// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Minimal in-memory [LifecycleOwner] for driving an
 * [com.tinkernorth.dish.architecture.abstracts.AbstractStateSource] through
 * `onStart` / `onStop` in unit tests.
 */
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
