// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped observer that re-establishes both Bluetooth and WiFi links
 * when the app returns to the foreground.
 *
 * The original reconnect bug was caused by holding a stale HID registration
 * across backgrounding: the OS-level Bluetooth link was still live, but the
 * app's proxy binding had been severed, so reports silently went nowhere.
 * WiFi has a symmetric hazard — a dropped native socket/handle stays torn
 * down until someone explicitly reconnects. Binding this observer to
 * [androidx.lifecycle.ProcessLifecycleOwner] guarantees we always kick
 * [ConnectionHub.autoReconnectAll] on every return to foreground, which in
 * turn rebuilds any session whose remembered target isn't currently live.
 * Safe to no-op when everything is already healthy — the hub's reconnect
 * paths are idempotent.
 */
@Singleton
class ConnectionForegroundObserver
    @Inject
    constructor(
        private val hub: ConnectionHub,
    ) : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            hub.autoReconnectAll()
        }
    }
