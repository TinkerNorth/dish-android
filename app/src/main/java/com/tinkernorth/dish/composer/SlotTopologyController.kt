// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractController
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlotTopologyController
    @Inject
    constructor(
        private val composer: SlotTopologyComposer,
        private val satellite: SatelliteConnectionManager,
        scope: CoroutineScope,
    ) : AbstractController<SlotTopologyController.Snapshot>(scope) {
        data class Snapshot(
            val topology: SlotTopology,
            val connections: Map<String, SatelliteConnection>,
        )

        override fun upstream(): Flow<Snapshot> =
            combine(composer.state, satellite.connections) { topology, conns -> Snapshot(topology, conns) }

        override fun apply(value: Snapshot) {
            for ((id, conn) in value.connections) {
                conn.applyDesired(value.topology[id].orEmpty())
            }
        }
    }
