// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

data class SatelliteMotionBackendStatus(
    val sinkSupportedForType: Boolean,
    val backendOk: Boolean,
) {
    val effective: Boolean get() = sinkSupportedForType && backendOk

    companion object {
        // Caller short-circuits on sentinel -1 ("no extended ACK"); this only handles 0..255.
        fun fromFlags(flags: Int): SatelliteMotionBackendStatus =
            SatelliteMotionBackendStatus(
                sinkSupportedForType = (flags and FLAG_SINK_SUPPORTED_FOR_TYPE) != 0,
                backendOk = (flags and FLAG_BACKEND_OK) != 0,
            )

        const val FLAG_SINK_SUPPORTED_FOR_TYPE: Int = 0x01
        const val FLAG_BACKEND_OK: Int = 0x02
    }
}

@Singleton
class SatelliteMotionBackendStatusStore
    @Inject
    constructor() :
    AbstractStateSource<Map<Pair<String, String>, SatelliteMotionBackendStatus>>(emptyMap()) {
        fun statusFor(
            connectionId: String,
            slotId: String,
        ): SatelliteMotionBackendStatus? = state.value[connectionId to slotId]

        fun setStatus(
            connectionId: String,
            slotId: String,
            status: SatelliteMotionBackendStatus,
        ) {
            setState { it + ((connectionId to slotId) to status) }
        }

        fun clear(
            connectionId: String,
            slotId: String,
        ) {
            setState {
                val key = connectionId to slotId
                if (key in it) it - key else it
            }
        }

        fun clearConnection(connectionId: String) {
            setState { current -> current.filterNot { (k, _) -> k.first == connectionId } }
        }

        fun slotStatusesFor(
            connectionId: String,
            boundSlotIds: List<String>,
        ): Map<String, SatelliteMotionBackendStatus> {
            val snapshot = state.value
            val out = mutableMapOf<String, SatelliteMotionBackendStatus>()
            for (slotId in boundSlotIds) {
                val status = snapshot[connectionId to slotId] ?: continue
                out[slotId] = status
            }
            return out
        }
    }
