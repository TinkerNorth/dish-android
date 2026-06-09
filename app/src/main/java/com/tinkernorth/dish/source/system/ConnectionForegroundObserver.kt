// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.ConnectionHub
import javax.inject.Inject
import javax.inject.Singleton

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
