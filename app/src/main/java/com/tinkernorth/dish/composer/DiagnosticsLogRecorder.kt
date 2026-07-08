// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.store.DiagnosticsLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds the flight recorder by diffing the connection and controller state flows. It only
 * observes StateFlows the UI already collects, so it adds nothing to the input path; the
 * first emissions double as the session's starting inventory.
 */
@Singleton
class DiagnosticsLogRecorder
    @Inject
    constructor(
        private val hub: ConnectionCoordinator,
        private val registry: PhysicalGamepadRegistry,
        private val log: DiagnosticsLogStore,
        private val scope: CoroutineScope,
    ) {
        private val installed = AtomicBoolean(false)

        fun install() {
            if (!installed.compareAndSet(false, true)) return
            scope.launch {
                var prev: List<ConnectionSummary> = emptyList()
                hub.connections.collect { next ->
                    DiagnosticsLogDiff.connectionEvents(prev, next).forEach { log.log(TAG_LINK, it) }
                    prev = next
                }
            }
            scope.launch {
                var prev: Map<Int, PhysicalGamepadRegistry.Device> = emptyMap()
                registry.devices.collect { next ->
                    DiagnosticsLogDiff.deviceEvents(prev, next).forEach { log.log(TAG_PAD, it) }
                    prev = next
                }
            }
        }

        private companion object {
            const val TAG_LINK = "link"
            const val TAG_PAD = "pad"
        }
    }
