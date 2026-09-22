// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.repository.SatelliteCapabilitiesRepository
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import javax.inject.Inject

/**
 * What the app knows about each satellite host, keyed by its id: the features it advertised,
 * its live runtime state, the per-controller motion backend status, its controller-type
 * catalog, and the probe that refreshes the first two.
 */
class SatelliteHostFacts
    @Inject
    constructor(
        val features: SatelliteHostFeaturesStore,
        val runtime: SatelliteHostRuntimeStore,
        val motionBackend: SatelliteMotionBackendStatusStore,
        val catalog: SatelliteCatalogRepository,
        val capabilities: SatelliteCapabilitiesRepository,
    )
