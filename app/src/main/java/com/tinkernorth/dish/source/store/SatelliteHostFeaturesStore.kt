// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.core.model.HostFeatureSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SatelliteHostFeaturesStore
    @Inject
    constructor() : AbstractStateSource<Map<String, HostFeatureSet>>(emptyMap()) {
        fun featuresFor(connectionId: String): HostFeatureSet? = state.value[connectionId]

        fun setFeatures(
            connectionId: String,
            features: HostFeatureSet,
        ) {
            setState { it + (connectionId to features) }
        }

        // Pre-bind/pre-catalog publish: fills the host layer from a capabilities probe
        // only if a richer catalog read has not already populated it, so the catalog
        // (with touchpad modes) always wins when present.
        fun setIfAbsent(
            connectionId: String,
            features: HostFeatureSet,
        ) {
            setState { if (connectionId in it) it else it + (connectionId to features) }
        }

        // Session negotiation is the freshest protocol read (it beats a stale cached
        // catalog after a satellite update); merged in so the chips and the extended
        // mouse gate follow the live truth without waiting for a catalog refetch.
        fun noteProtocolVersion(
            connectionId: String,
            protocolVersion: Int,
        ) {
            if (protocolVersion <= 0) return
            setState { current ->
                val base = current[connectionId] ?: HostFeatureSet.SATELLITE_DEFAULT
                if (base.protocolVersion == protocolVersion) {
                    current
                } else {
                    current + (connectionId to base.copy(protocolVersion = protocolVersion))
                }
            }
        }

        fun clearConnection(connectionId: String) {
            setState { if (connectionId in it) it - connectionId else it }
        }
    }
