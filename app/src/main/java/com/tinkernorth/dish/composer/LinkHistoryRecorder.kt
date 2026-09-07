// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.source.store.LinkHistoryPolicy
import com.tinkernorth.dish.source.store.LinkHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinkHistoryRecorder
    @Inject
    constructor(
        private val hub: ConnectionCoordinator,
        private val store: LinkHistoryStore,
        private val scope: CoroutineScope,
    ) {
        private val installed = AtomicBoolean(false)

        fun install() {
            if (!installed.compareAndSet(false, true)) return
            scope.launch {
                hub.connections.collect { summaries ->
                    store.update { LinkHistoryPolicy.onConnections(it, summaries, System.currentTimeMillis()) }
                }
            }
            scope.launch {
                hub.bindings.collect { bindings ->
                    store.update { LinkHistoryPolicy.onBindings(it, bindings, System.currentTimeMillis()) }
                }
            }
        }
    }
