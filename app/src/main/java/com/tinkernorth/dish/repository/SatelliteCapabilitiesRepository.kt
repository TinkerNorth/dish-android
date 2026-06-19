// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.model.ServerCapabilitiesDto
import com.tinkernorth.dish.core.net.DiscoveryGateway
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SatelliteHostRuntime
import com.tinkernorth.dish.source.store.SatelliteHostRuntimeStore
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GET /api/server/capabilities — the satellite's live dynamic state. Read before binding
 * (and before the catalog round-trip) so the setup flow reflects the real receiver
 * instead of the optimistic [HostFeatureSet.SATELLITE_DEFAULT]:
 *
 * - the host-capability block (presence) seeds the host layer when the catalog has not
 *   yet populated it ([SatelliteHostFeaturesStore.setIfAbsent]); a fetched catalog, being
 *   richer, always wins;
 * - the runtime read (motion backend up/down) populates [SatelliteHostRuntimeStore], so a
 *   feature can show as present-but-currently-down pre-bind.
 *
 * Unlike the catalog this is live state, so it is not cached or ETag'd.
 */
@Singleton
class SatelliteCapabilitiesRepository
    @Inject
    constructor(
        private val gateway: DiscoveryGateway,
        private val json: Json,
        private val hostFeaturesStore: SatelliteHostFeaturesStore,
        private val runtimeStore: SatelliteHostRuntimeStore,
    ) {
        suspend fun refresh(
            server: DiscoveredServer,
            satelliteId: String,
        ): ServerCapabilitiesDto? {
            val reply =
                runCatching {
                    gateway.serverCapabilities(server.ip, server.httpPort, satelliteId)
                }.getOrNull() ?: return null
            if (reply.status != 200 || reply.body.isBlank()) return null
            val caps =
                runCatching { json.decodeFromString(ServerCapabilitiesDto.serializer(), reply.body) }
                    .getOrNull() ?: return null

            // An older satellite omits the host block (catalog.supported stays false): leave
            // the optimistic default in place rather than reporting everything unsupported.
            if (caps.host.catalog.supported) {
                hostFeaturesStore.setIfAbsent(satelliteId, HostFeatureSet.fromServerCapabilities(caps))
            }
            runtimeStore.setRuntime(satelliteId, SatelliteHostRuntime(motionBackendOk = caps.motion.available))
            return caps
        }
    }
