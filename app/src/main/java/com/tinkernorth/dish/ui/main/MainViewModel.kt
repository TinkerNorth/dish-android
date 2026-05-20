// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.data.network.BatteryStatusStore
import com.tinkernorth.dish.data.network.ConnectionEvent
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.SatelliteConnectionManager
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
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
        val satellite: SatelliteConnectionManager,
        val hub: ConnectionHub,
        private val gamepadRegistry: PhysicalGamepadRegistry,
        private val batteryStatusStore: BatteryStatusStore,
    ) : ViewModel() {
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
            ) { conns, bindings, devices, batteries ->
                val virtual =
                    ControllerSlot(
                        id = VIRTUAL_SLOT_ID,
                        inputType = SlotInputType.VIRTUAL,
                        name = "Virtual Controller",
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
                MainUiState(slots = slots, connections = conns)
            }.onEach { _uiState.value = it }.launchIn(viewModelScope)

            satellite.events
                .onEach { event ->
                    when (event) {
                        is ConnectionEvent.PairingRequired ->
                            _events.emit(
                                MainEvent.ShowPairingDialog(
                                    com.tinkernorth.dish.data.network.SatelliteConnection
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
    }
