// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

// Pre-bind host runtime, from GET /api/server/capabilities. The post-bind equivalent
// (SatelliteMotionBackendStatus) only arrives in the apply response; this lets the setup
// table show a feature present-but-currently-down (e.g. no driver) before the user binds.
data class SatelliteHostRuntime(
    val motionBackendOk: Boolean,
)

@Singleton
class SatelliteHostRuntimeStore
    @Inject
    constructor() : AbstractStateSource<Map<String, SatelliteHostRuntime>>(emptyMap()) {
        fun runtimeFor(hostId: String): SatelliteHostRuntime? = state.value[hostId]

        fun setRuntime(
            hostId: String,
            runtime: SatelliteHostRuntime,
        ) {
            setState { it + (hostId to runtime) }
        }

        fun clearConnection(hostId: String) {
            setState { if (hostId in it) it - hostId else it }
        }
    }
