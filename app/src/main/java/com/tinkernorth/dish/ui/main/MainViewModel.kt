package com.tinkernorth.dish.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.data.network.ConnectionEvent
import com.tinkernorth.dish.data.network.ConnectionHub
import com.tinkernorth.dish.data.network.WifiConnectionManager
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
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Dashboard view-model. The heavy lifting (connection sessions, persistence,
 * heartbeat) lives in [WifiConnectionManager] and [ConnectionHub] — this just
 * adapts their state for rendering and relays bind/unbind actions from slot
 * rows.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        val wifi: WifiConnectionManager,
        val hub: ConnectionHub,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MainUiState())
        val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

        private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<MainEvent> = _events.asSharedFlow()

        init {
            combine(hub.connections, hub.bindings) { conns, bindings ->
                val prev = _uiState.value
                val newSlots =
                    prev.slots.map { slot ->
                        val cid = bindings[slot.id]
                        slot.copy(
                            boundConnectionId = cid,
                            boundStatus = cid?.let { id -> conns.firstOrNull { it.id == id } },
                        )
                    }
                prev.copy(slots = newSlots, connections = conns)
            }.onEach { _uiState.value = it }.launchIn(viewModelScope)

            wifi.events
                .onEach { event ->
                    when (event) {
                        is ConnectionEvent.PairingRequired ->
                            _events.emit(
                                MainEvent.ShowPairingDialog(
                                    com.tinkernorth.dish.data.network.WifiConnection
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

        // ── Physical controller lifecycle ─────────────────────────────────────

        fun onControllerConnected(
            id: Int,
            name: String,
        ) {
            _uiState.update { st ->
                if (st.slots.any { it.id == id.toString() }) {
                    st
                } else {
                    st.copy(
                        slots =
                            st.slots +
                                ControllerSlot(
                                    id = id.toString(),
                                    inputType = SlotInputType.PHYSICAL,
                                    name = name,
                                    physicalDeviceId = id,
                                ),
                    )
                }
            }
        }

        fun onControllerDisconnected(id: Int) {
            val slotId = id.toString()
            hub.unbind(slotId)
            _uiState.update { st -> st.copy(slots = st.slots.filter { it.id != slotId }) }
        }
    }
