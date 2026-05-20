// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
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

/**
 * Process-scoped owner of the native pipeline's per-device → connection
 * bindings. Drives [SatelliteNative.bindPhysicalSlotSatellite] /
 * [SatelliteNative.bindPhysicalSlotBluetooth] / [SatelliteNative.unbindPhysicalSlot]
 * from the cross-product of:
 *   - [PhysicalGamepadRegistry.devices] — currently-attached gamepads
 *   - [ConnectionHub.bindings] — slot → connection routing
 *   - [ConnectionHub.connections] — live status of each routing target
 *   - each [SatelliteConnection.slots] — per-slot `registered` + `controllerIndex`
 *
 * Subscribing at the *process* lifecycle (not any activity's) means the
 * bindings survive the [com.tinkernorth.dish.ui.main.MainActivity] →
 * [com.tinkernorth.dish.ui.main.GamepadOverlayActivity] hand-off — without
 * this, the overlay would stop physical gamepads from passing through to the
 * server because the dashboard's onStop would tear every binding down right
 * as the overlay took focus.
 */
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
            // Drop every native binding when the *app* fully backgrounds so no
            // stale (deviceId → handle) mapping survives a session teardown.
            // Re-derived on the next onStart from the freshest hub state.
            SatelliteNative.clearAllPhysicalSlots()
            lastBoundDeviceIds = emptySet()
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun stream(): Flow<BindingState> =
            satellite.connections.flatMapLatest { conns ->
                // The outer Map only re-emits when a session is added/removed;
                // we also need to re-push whenever an existing session's slot
                // table flips `registered` so reports can actually flow.
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
            // Release hub bindings for devices that just went away. Without
            // this the satellite would keep the slot's controller index
            // allocated and reports for re-added device ids could land on the
            // wrong server-side controller.
            val disappeared = lastBoundDeviceIds - present
            for (id in disappeared) {
                SatelliteNative.unbindPhysicalSlot(id)
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
