// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Dashboard view-model. The heavy lifting (connection sessions, persistence,
 * heartbeat) lives in [SatelliteConnectionManager] and [ConnectionHub] — this
 * just adapts their state for rendering and relays bind/unbind actions from
 * slot rows.
 *
 * Physical slots are derived from [PhysicalGamepadRegistry] (a process-scoped
 * singleton) so the dashboard stays in sync with the gamepad set the native
 * input pipeline sees, regardless of which activity is foreground.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        val satellite: SatelliteConnectionManager,
        val hub: ConnectionHub,
        private val gamepadRegistry: PhysicalGamepadRegistry,
        private val batteryStatusStore: BatteryStatusStore,
        private val motionEnabledStore: MotionEnabledStore,
        private val motionCapability: MotionCapabilityComposer,
    ) : ViewModel() {
        /**
         * Reactive `slotId -> enabled` map for the per-slot motion toggle.
         * Hydrated at process start from [MotionEnabledStore]'s durable
         * backing repo, so the dashboard renders yesterday's toggle state
         * with no first-frame flicker.
         *
         * Absence from the map means "user has not toggled" — call sites
         * use [isMotionEnabled] to apply the default rather than reading
         * the map directly.
         */
        val motionEnabled: StateFlow<Map<String, Boolean>> = motionEnabledStore.state

        private val _uiState = MutableStateFlow(MainUiState())
        val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

        private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<MainEvent> = _events.asSharedFlow()

        init {
            combine(
                hub.connections,
                hub.bindings,
                gamepadRegistry.devices,
                batteryStatusStore.samples,
                motionCapability.state,
            ) { conns, bindings, devices, batteries, motionCaps ->
                val virtual =
                    ControllerSlot(
                        id = VIRTUAL_SLOT_ID,
                        inputType = SlotInputType.VIRTUAL,
                        name = context.getString(R.string.default_virtual_controller_name),
                    )
                val physical =
                    devices.values.map { dev ->
                        ControllerSlot(
                            id = dev.id.toString(),
                            inputType = SlotInputType.PHYSICAL,
                            name = dev.name,
                            physicalDeviceId = dev.id,
                            isDisconnecting = dev.isDisconnecting,
                            disconnectTimeLeft = dev.disconnectingTimeLeftSec ?: 0,
                        )
                    }
                val slots =
                    (listOf(virtual) + physical).map { slot ->
                        val cid = bindings[slot.id]
                        slot.copy(
                            boundConnectionId = cid,
                            boundStatus = cid?.let { id -> conns.firstOrNull { it.id == id } },
                            battery =
                                batteries[slot.id]?.let { s ->
                                    BatteryUi.fromWire(s.level, s.status)
                                },
                        )
                    }
                MainUiState(slots = slots, connections = conns, motionCapabilities = motionCaps)
            }.onEach { _uiState.value = it }.launchIn(viewModelScope)

            satellite.events
                .onEach { event ->
                    when (event) {
                        is ConnectionEvent.PairingRequired ->
                            _events.emit(
                                MainEvent.ShowPairingDialog(
                                    com.tinkernorth.dish.source.connection.SatelliteConnection
                                        .idFor(event.server),
                                ),
                            )
                        is ConnectionEvent.Error -> _events.emit(MainEvent.ShowToast(event.message))
                    }
                }.launchIn(viewModelScope)
        }

        // ── Slot binding ──────────────────────────────────────────────────────

        fun bindSlot(
            slotId: String,
            connectionId: String,
        ) {
            hub.bind(slotId, connectionId)
        }

        fun unbindSlot(slotId: String) {
            hub.unbind(slotId)
        }

        /**
         * Switch the controller type (Xbox/PS) for a slot bound to a satellite.
         * No-op for Bluetooth bindings — BT type is fixed by the remembered
         * host's HID profile.
         */
        fun setSatelliteControllerType(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            hub.setSatelliteControllerType(connectionId, slotId, type)
        }

        // ── Motion toggle ─────────────────────────────────────────────────────

        /**
         * Persist the user's per-slot motion preference. The store writes
         * through to the durable [com.tinkernorth.dish.repository.MotionPreferenceRepository]
         * AND republishes its state flow so the
         * [com.tinkernorth.dish.composer.MotionCapabilityComposer] (wired
         * in a follow-up PR) re-derives the slot's `CAP_MOTION` bit and
         * the sensor-listener gate flips on the next emission.
         *
         * No-op semantics: writing the same value twice is harmless;
         * the store still re-emits so any downstream observer that
         * relies on identity equality (none today) is unaffected.
         */
        fun setMotionEnabled(
            slotId: String,
            enabled: Boolean,
        ) {
            motionEnabledStore.setEnabled(slotId, enabled)
        }

        /**
         * Resolve the effective motion-enabled boolean for [slotId],
         * collapsing an absent entry onto [MotionEnabledStore.DEFAULT_ENABLED].
         * Use this in render code — never read [motionEnabled] directly,
         * since absence and `false` mean different things in the store
         * but the same thing to the user.
         */
        fun isMotionEnabled(slotId: String): Boolean = motionEnabledStore.isEnabled(slotId)
    }
