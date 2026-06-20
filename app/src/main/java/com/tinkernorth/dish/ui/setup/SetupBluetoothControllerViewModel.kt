// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver
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
import kotlinx.coroutines.launch
import javax.inject.Inject

// Stage 2 Bluetooth controller (design 2C). Pairing happens in the system
// Bluetooth settings, so this screen only gates on the runtime permission and
// lists the controllers actually CONNECTED right now (read from the gamepad
// registry, which only carries live input devices). Tapping a controller commits
// it and advances; not-yet-connected bonded devices are intentionally not shown,
// since the next stages need a live input slot to bind.
@HiltViewModel
class SetupBluetoothControllerViewModel
    @Inject
    constructor(
        private val permission: BluetoothPermissionStateObserver,
        private val registry: PhysicalGamepadRegistry,
    ) : ViewModel() {
        data class Controller(
            val slotId: String,
            val name: String,
        )

        data class State(
            val permissionMissing: Boolean = false,
            val controllers: List<Controller> = emptyList(),
        )

        sealed interface Event {
            data class Proceed(
                val slotId: String,
            ) : Event
        }

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
        val events: SharedFlow<Event> = _events.asSharedFlow()

        init {
            permission.refresh()
            combine(permission.state, registry.devices) { perm, devices ->
                State(
                    permissionMissing = perm.anyMissing,
                    controllers =
                        devices.values
                            .filter { it.transport == Transport.Bluetooth && !it.isDisconnecting }
                            .map { Controller(slotId = it.id.toString(), name = it.name) },
                )
            }.onEach { next -> _state.value = next }.launchIn(viewModelScope)
        }

        // The grant happens in the Activity's permission launcher; reflect it on return.
        fun refresh() = permission.refresh()

        fun onPermissionResult() = permission.refresh()

        // Tapping a connected controller is the commit: hand its live slot id forward.
        fun onControllerTapped(controller: Controller) = emitProceed(controller.slotId)

        private fun emitProceed(slotId: String) {
            viewModelScope.launch { _events.emit(Event.Proceed(slotId)) }
        }
    }
