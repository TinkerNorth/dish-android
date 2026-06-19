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

        fun clearConnection(connectionId: String) {
            setState { if (connectionId in it) it - connectionId else it }
        }
    }
