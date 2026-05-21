// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.abstracts

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pattern: **owns one piece of state and exposes it as a [StateFlow]**, with
 * optional lifecycle hooks. The `Abstract` prefix + the `abstracts/` package
 * announce that this is a base class, not a thing you instantiate directly.
 *
 * Use this when the class wraps a sensor, socket, BroadcastReceiver, system
 * service, timer, in-memory cache, or any other resource that has state-over-time.
 * The base gives you:
 *
 *   - a single `MutableStateFlow<S>` exposed read-only as [state];
 *   - protected helpers [setState] for atomic updates;
 *   - `DefaultLifecycleObserver` integration so the same shape works for
 *     [androidx.lifecycle.ProcessLifecycleOwner]-bound sources and per-Activity
 *     sources. Lifecycle hooks are **opt-in** — sources whose lifecycle is
 *     driven manually (e.g. registry-managed instances like
 *     [com.tinkernorth.dish.source.connection.SatelliteConnection]) simply do
 *     not override them.
 *
 * **No events channel.** Earlier iterations of this base had an `events`
 * `SharedFlow<E>` alongside `state`. Audit showed only one class
 * ([com.tinkernorth.dish.source.notification.DishNotifications]) actually
 * used it, and it needed *two* SharedFlows so the inheritance only covered half
 * its surface anyway. Rare event-emitting classes
 * ([com.tinkernorth.dish.source.connection.SatelliteConnectionManager],
 * [com.tinkernorth.dish.source.notification.DishNotifications]) own their
 * own `SharedFlow`s directly. The base stays tight: state + optional lifecycle.
 *
 * **Type parameter:**
 *   - [S] = the published state. Use [Unit] only when the class has no
 *     observable state and is here solely for the lifecycle hooks (e.g.
 *     [com.tinkernorth.dish.source.system.ConnectionForegroundObserver]). Most
 *     subclasses have a meaningful `S`.
 *
 * **Pairs with:** [AbstractComposer] (derived flows that combine multiple
 * sources) and [com.tinkernorth.dish.architecture.interfaces.Repository] (durable
 * CRUD). When a source needs durable state, compose it with a `Repository`; do
 * not fold persistence into a source subclass.
 *
 * **Testing:** see `StateSourceProbe` in
 * `com.tinkernorth.dish.architecture.testing`.
 */
abstract class AbstractStateSource<S>(
    initialState: S,
) : DefaultLifecycleObserver {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    /** Replace state. Thread-safe; uses `MutableStateFlow.update` semantics. */
    protected fun setState(reducer: (S) -> S) {
        _state.update(reducer)
    }

    /** Overwrite state unconditionally. */
    protected fun setState(value: S) {
        _state.value = value
    }

    override fun onStart(owner: LifecycleOwner) = Unit

    override fun onStop(owner: LifecycleOwner) = Unit
}
