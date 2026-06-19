// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.bluetooth.BluetoothConnections
import com.tinkernorth.dish.source.bluetooth.BluetoothDeviceScanner
import com.tinkernorth.dish.source.system.BluetoothPermissionState
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

// Stage 2 Bluetooth controller (design 2C). Unlike the USB branch this wizard
// owns no claim: pairing happens in the system Bluetooth settings, so the screen
// only reflects three live signals and proceeds when a BT gamepad actually
// connects. The signals are the runtime permission gate (BLUETOOTH_CONNECT/SCAN),
// the bonded-device list from the scanner (the "on this phone" rows), and the
// PhysicalGamepadRegistry (which BT pad is connected right now). readySlotId is
// the live framework id of a connected, non-disconnecting BT pad; the later
// stages bind that id.
@HiltViewModel
class SetupBluetoothControllerViewModel
    @Inject
    constructor(
        private val permission: BluetoothPermissionStateObserver,
        private val scanner: BluetoothDeviceScanner,
        private val registry: PhysicalGamepadRegistry,
        private val btConnections: BluetoothConnections,
    ) : ViewModel() {
        data class PairedRow(
            val name: String,
            val connected: Boolean,
        )

        data class State(
            val permissionMissing: Boolean = false,
            val paired: List<PairedRow> = emptyList(),
            val readySlotId: String? = null,
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
            // The bonded list (and the live "Connected" flag on each row) and the connected-pad
            // signal are derived together so a row's status and the Continue gate never disagree.
            combine(
                permission.state,
                scanner.state,
                registry.devices,
            ) { perm, scan, devices ->
                project(perm, scan, devices.values)
            }.onEach { next -> _state.value = next }.launchIn(viewModelScope)

            startScanForBonded()
        }

        // Re-seed the bonded list whenever the screen returns to foreground or a permission grant
        // lands; the scanner re-reads adapter.bondedDevices on each start(). canScan stays false:
        // 2C only needs the already-paired list, not active discovery.
        fun refresh() {
            permission.refresh()
            startScanForBonded()
        }

        private fun startScanForBonded() {
            scanner.start(canScan = false)
        }

        private fun project(
            perm: BluetoothPermissionState,
            scan: BluetoothDeviceScanner.State,
            devices: Collection<PhysicalGamepadRegistry.Device>,
        ): State {
            val connectedPad =
                devices.firstOrNull { it.transport == Transport.Bluetooth && !it.isDisconnecting }
            val paired =
                scan.devices
                    .filter { it.bonded }
                    .map { device ->
                        val name = device.name?.takeIf { it.isNotBlank() } ?: device.mac
                        PairedRow(name = name, connected = btConnections.isConnected(name))
                    }
            return State(
                permissionMissing = perm.anyMissing,
                paired = paired,
                readySlotId = connectedPad?.id?.toString(),
            )
        }

        // The runtime grant happened in the Activity's permission launcher; reflect it immediately
        // and re-seed the bonded list rather than waiting for the next foreground.
        fun onPermissionResult() = refresh()

        fun continueToConnection() {
            _state.value.readySlotId?.let { emitProceed(it) }
        }

        // Tapping the connected row is the same commit as Continue.
        fun onPairedRowTapped(row: PairedRow) {
            if (row.connected) _state.value.readySlotId?.let { emitProceed(it) }
        }

        override fun onCleared() {
            scanner.stop()
            super.onCleared()
        }

        private fun emitProceed(slotId: String) {
            viewModelScope.launch { _events.emit(Event.Proceed(slotId)) }
        }
    }
