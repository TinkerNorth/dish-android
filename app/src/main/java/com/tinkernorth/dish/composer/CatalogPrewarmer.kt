// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// Warms each satellite's controller-type catalog once its link goes Live (TOFU pinned), so the
// configure screen resolves the "Emulate as" type from cache instead of a loader. Observes the raw
// connection states, NOT the composed connections flow: re-collecting the composer's own output
// into its scope perturbs its flatMapLatest. catalogFor caches + ETag-dedupes; each id warms once.
@Singleton
class CatalogPrewarmer
    @Inject
    constructor(
        private val satellite: SatelliteConnectionManager,
        private val catalogRepo: SatelliteCatalogRepository,
        private val scope: CoroutineScope,
    ) {
        private val warmed = ConcurrentHashMap.newKeySet<String>()

        fun start() {
            satellite.connections
                .onEach { conns ->
                    for ((id, conn) in conns) {
                        if (!warmed.add(id)) continue
                        scope.launch {
                            conn.state.first { it == SatelliteSessionState.Live }
                            catalogRepo.catalogFor(conn.server.value, id)
                        }
                    }
                }.launchIn(scope)
        }
    }
