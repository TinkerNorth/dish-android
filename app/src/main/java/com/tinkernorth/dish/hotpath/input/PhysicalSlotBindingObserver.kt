// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

// Process-scoped (not activity-scoped) so bindings survive MainActivity → GamepadOverlayActivity hand-off.
@Singleton
class PhysicalSlotBindingObserver
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionHub,
        private val satellite: SatelliteConnectionManager,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private data class BindingState(
            val devices: Map<Int, PhysicalGamepadRegistry.Device>,
            val bindings: Map<String, String>,
            val summaries: List<ConnectionSummary>,
        )

        private var job: Job? = null
        private var lastBoundDeviceIds: Set<Int> = emptySet()

        override fun onStart(owner: LifecycleOwner) {
            if (job != null) return
            job = stream().onEach(::push).launchIn(scope)
        }

        override fun onStop(owner: LifecycleOwner) {
            job?.cancel()
            job = null
            SatelliteNative.clearAllPhysicalSlots()
            lastBoundDeviceIds = emptySet()
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun stream(): Flow<BindingState> =
            satellite.connections.flatMapLatest { conns ->
                // Outer Map only re-emits on session add/remove; slotsTrigger re-pushes when a session's slot flips `registered`.
                val slotFlows = conns.values.map { it.slots }
                val slotsTrigger: Flow<Unit> =
                    if (slotFlows.isEmpty()) flowOf(Unit) else combine(slotFlows) { Unit }
                combine(
                    registry.devices,
                    hub.bindings,
                    hub.connections,
                    slotsTrigger,
                ) { devs, bindings, summaries, _ -> BindingState(devs, bindings, summaries) }
            }

        private fun push(state: BindingState) {
            val present = state.devices.keys
            // Without this, re-added device ids could land on the wrong server-side controller index.
            val disappeared = lastBoundDeviceIds - present
            for (id in disappeared) {
                SatelliteNative.unbindPhysicalSlot(id)
                // Reclaim the native input-state entry for a departed framework device; a claimed
                // USB synthetic (negative id) is freed by detachUsbDevice instead.
                if (id >= 0) SatelliteNative.forgetPhysicalDevice(id)
                hub.unbind(id.toString())
            }
            for (id in present) {
                val slotId = id.toString()
                val cid = state.bindings[slotId]
                val summary = cid?.let { lookup -> state.summaries.firstOrNull { it.id == lookup } }
                if (cid == null || summary == null || summary.live != LinkState.Connected) {
                    SatelliteNative.unbindPhysicalSlot(id)
                    continue
                }
                when (summary.kind) {
                    ConnectionKind.SATELLITE -> {
                        val sat = satellite.get(cid)
                        val info = sat?.slots?.value?.get(slotId)
                        if (sat == null || sat.handle < 0 || info == null || !info.registered) {
                            SatelliteNative.unbindPhysicalSlot(id)
                        } else {
                            SatelliteNative.bindPhysicalSlotSatellite(
                                id,
                                sat.handle,
                                info.controllerIndex,
                            )
                        }
                    }
                    ConnectionKind.BLUETOOTH ->
                        SatelliteNative.bindPhysicalSlotBluetooth(id, cid)
                }
            }
            lastBoundDeviceIds = present
        }
    }
