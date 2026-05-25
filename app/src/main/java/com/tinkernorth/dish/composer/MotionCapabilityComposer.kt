// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

data class MotionCapability(
    val hasGyro: Boolean,
    val carriesOnConnection: Boolean,
    val userEnabled: Boolean = true,
    val hostHasSinkForType: Boolean = true,
    val satelliteBackendStatus: SatelliteMotionBackendStatus? = null,
) {
    val effective: Boolean get() = hasGyro && carriesOnConnection && userEnabled

    // Not gated on carriesOnConnection: a satellite reconnect must recover motion without re-handshake.
    fun toCapBits(): Int = if (hasGyro && userEnabled) CAP_MOTION_BIT else 0

    companion object {
        // Mirror of satellite/src/core/types.h::CAP_MOTION.
        const val CAP_MOTION_BIT: Int = 0x0004

        val Off = MotionCapability(hasGyro = false, carriesOnConnection = false)
    }
}

@Suppress("UNCHECKED_CAST", "LongParameterList")
private inline fun <T1, T2, T3, T4, T5, T6, R> combine6(
    f1: Flow<T1>,
    f2: Flow<T2>,
    f3: Flow<T3>,
    f4: Flow<T4>,
    f5: Flow<T5>,
    f6: Flow<T6>,
    crossinline transform: suspend (T1, T2, T3, T4, T5, T6) -> R,
): Flow<R> =
    combine(f1, f2, f3, f4, f5, f6) { args ->
        transform(
            args[0] as T1,
            args[1] as T2,
            args[2] as T3,
            args[3] as T4,
            args[4] as T5,
            args[5] as T6,
        )
    }

@Singleton
class MotionCapabilityComposer
    @Inject
    constructor(
        private val phoneAvailability: PhoneMotionAvailability,
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionHub,
        private val motionEnabledStore: MotionEnabledStore,
        private val satelliteMotionBackendStatusStore: SatelliteMotionBackendStatusStore,
        scope: CoroutineScope,
    ) : AbstractComposer<Map<String, MotionCapability>>(scope, emptyMap()) {
        override fun upstream(): Flow<Map<String, MotionCapability>> =
            combine6(
                phoneAvailability.state,
                registry.devices,
                hub.bindings,
                hub.connections,
                motionEnabledStore.state,
                satelliteMotionBackendStatusStore.state,
            ) { phoneHasGyro, devices, bindings, summaries, enabledMap, backendStatusMap ->
                val byId = summaries.associateBy { it.id }
                val out = HashMap<String, MotionCapability>(devices.size + 1)

                out[VIRTUAL_SLOT_ID] =
                    MotionCapability(
                        hasGyro = phoneHasGyro,
                        carriesOnConnection = carriesMotion(VIRTUAL_SLOT_ID, bindings, byId),
                        userEnabled = enabledMap[VIRTUAL_SLOT_ID] ?: MotionEnabledStore.DEFAULT_ENABLED,
                        hostHasSinkForType = hostSinkForType(VIRTUAL_SLOT_ID, bindings, byId),
                        satelliteBackendStatus =
                            satelliteStatus(VIRTUAL_SLOT_ID, bindings, backendStatusMap),
                    )

                for ((deviceId, device) in devices) {
                    val slotId = deviceId.toString()
                    out[slotId] =
                        MotionCapability(
                            hasGyro = device.hasGyro,
                            carriesOnConnection = carriesMotion(slotId, bindings, byId),
                            userEnabled = enabledMap[slotId] ?: MotionEnabledStore.DEFAULT_ENABLED,
                            hostHasSinkForType = hostSinkForType(slotId, bindings, byId),
                            satelliteBackendStatus =
                                satelliteStatus(slotId, bindings, backendStatusMap),
                        )
                }
                out
            }

        fun capabilityFor(slotId: String): MotionCapability = state.value[slotId] ?: MotionCapability.Off

        private fun carriesMotion(
            slotId: String,
            bindings: Map<String, String>,
            summariesById: Map<String, ConnectionSummary>,
        ): Boolean {
            val cid = bindings[slotId] ?: return false
            val summary = summariesById[cid] ?: return false
            return summary.kind == ConnectionKind.SATELLITE &&
                summary.live == LinkState.Connected
        }

        private fun hostSinkForType(
            slotId: String,
            bindings: Map<String, String>,
            summariesById: Map<String, ConnectionSummary>,
        ): Boolean {
            val cid = bindings[slotId] ?: return true
            val summary = summariesById[cid] ?: return true
            if (summary.kind != ConnectionKind.SATELLITE) return true
            val type = summary.satelliteControllerTypes[slotId] ?: return true
            return type == CONTROLLER_TYPE_PLAYSTATION
        }

        private fun satelliteStatus(
            slotId: String,
            bindings: Map<String, String>,
            backendStatusMap: Map<Pair<String, String>, SatelliteMotionBackendStatus>,
        ): SatelliteMotionBackendStatus? {
            val cid = bindings[slotId] ?: return null
            return backendStatusMap[cid to slotId]
        }
    }
