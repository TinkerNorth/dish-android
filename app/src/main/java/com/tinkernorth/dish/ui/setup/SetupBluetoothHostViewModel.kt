// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.input.BluetoothGamepad
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.repository.ConnectionStore
import com.tinkernorth.dish.repository.RememberedBt
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.sensor.PhoneMotionAvailability
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Stage 3 Bluetooth host (design 5H). The phone advertises itself as a gamepad
// to a PC, so this wizard owns a BluetoothGamepadRegistry session rather than a
// claim: it commits the controller type the PC will see (locked per host, since
// re-pairing is the only way to change it), drives the system discoverable +
// passkey prompts from the Activity, and proceeds once the registry reports the
// host link connected. The connectionId handed forward is the registry's stable
// id (idFor(mac) == "bt:<mac>"), which is also the coordinator's summary id, so
// the configure screen resolves the same host. The input slotId rides in from
// the prior step's extras unchanged.
@HiltViewModel
class SetupBluetoothHostViewModel
    @Inject
    constructor(
        private val registry: BluetoothGamepadRegistry,
        private val permission: BluetoothPermissionStateObserver,
        private val store: ConnectionStore,
        private val hub: ConnectionCoordinator,
        private val capabilityComposer: CapabilityComposer,
        motion: PhoneMotionAvailability,
    ) : ViewModel() {
        // PICK_PC lists known hosts + a "pair new" row; PERMISSION gates on the
        // runtime BLUETOOTH grants; PICK_TYPE locks a type for a brand-new host;
        // ADVERTISING waits out the discoverable + bond handshake.
        enum class Stage { PICK_PC, PERMISSION, PICK_TYPE, ADVERTISING }

        data class HostRow(
            val id: String,
            val name: String,
            val mac: String,
            val profile: BluetoothGamepad.GamepadProfile,
        )

        data class State(
            val stage: Stage = Stage.PICK_PC,
            val hosts: List<HostRow> = emptyList(),
            val permissionMissing: Boolean = false,
            val hasGyro: Boolean = false,
            // Set once advertising starts so the screen can name the committed type.
            val advertisingProfile: BluetoothGamepad.GamepadProfile? = null,
            val discoverable: Boolean = false,
        )

        sealed interface Event {
            // Asks the Activity to fire the system discoverable prompt; the reply
            // routes back through onDiscoverableResult.
            data object RequestDiscoverable : Event

            // The host bonded; the input is already bound, so the wizard finishes to
            // the dashboard. Carries what the success toast needs.
            data class Done(
                val hostName: String,
                val profile: BluetoothGamepad.GamepadProfile,
                val bound: Boolean,
            ) : Event
        }

        private val _state = MutableStateFlow(State(hasGyro = motion.hasGyro))
        val state: StateFlow<State> = _state.asStateFlow()

        private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
        val events: SharedFlow<Event> = _events.asSharedFlow()

        // The connId currently driving the registry session (transient until the
        // host bonds, then the registry rewrites it to "bt:<mac>").
        private var activeConnId: String? = null
        private var slotId: String = ""
        private var proceeded = false

        // Hosts already on the link when our session starts, so the proceed below
        // fires for the host WE just brought up, not one that was already bonded.
        private var baselineLiveKeys: Set<String> = emptySet()

        init {
            permission.refresh()
            combine(
                store.rememberedBtFlow,
                permission.state,
            ) { remembered, perm ->
                project(remembered, perm)
            }.onEach { next -> _state.value = next }.launchIn(viewModelScope)

            // A registry slot reaching connected for our active session means the PC
            // finished the bond; bind the input and finish.
            registry.states.onEach { onRegistryStates(it) }.launchIn(viewModelScope)
        }

        fun bindArgs(slotId: String) {
            this.slotId = slotId
        }

        // Capability table for a candidate type over THIS Bluetooth host. The BT transport
        // carries only the gamepad, so motion/touchpad/rumble resolve off regardless of type.
        fun capabilityForType(candidateType: Int): SlotCapabilities =
            capabilityComposer.capabilityForCandidate(
                slotId = slotId,
                candidateType = candidateType,
                candidateHostKind = ConnectionKind.BLUETOOTH,
                candidateHostId = null,
            )

        // Refresh the grant snapshot on every foreground; the OS never broadcasts
        // a revoke, and a grant landed in the Activity launcher needs reflecting.
        fun refresh() = permission.refresh()

        private fun project(
            remembered: List<RememberedBt>,
            perm: BluetoothPermissionState,
        ): State {
            val current = _state.value
            return current.copy(
                hosts = remembered.map { it.toRow() },
                permissionMissing = perm.anyMissing,
            )
        }

        // Tapping a known host: its type is already locked, so skip the picker and
        // advertise straight away (gated only by the BT permission).
        fun onHostSelected(row: HostRow) {
            if (_state.value.permissionMissing) {
                _state.update { it.copy(stage = Stage.PERMISSION) }
                return
            }
            beginAdvertising(row.profile, autoConnectMac = row.mac)
        }

        // "Pair a new device": go to the permission gate first if needed, else to
        // the type picker (the type chosen there is locked to the new host).
        fun onPairNewDevice() {
            _state.update {
                it.copy(stage = if (it.permissionMissing) Stage.PERMISSION else Stage.PICK_TYPE)
            }
        }

        // The Activity's permission launcher returned; reflect the grant and, if
        // satisfied, advance to the type picker the user was heading for.
        fun onPermissionResult() {
            permission.refresh()
            if (!_state.value.permissionMissing && _state.value.stage == Stage.PERMISSION) {
                _state.update { it.copy(stage = Stage.PICK_TYPE) }
            }
        }

        fun onTypeChosen(profile: BluetoothGamepad.GamepadProfile) = beginAdvertising(profile, autoConnectMac = null)

        private fun beginAdvertising(
            profile: BluetoothGamepad.GamepadProfile,
            autoConnectMac: String?,
        ) {
            proceeded = false
            baselineLiveKeys =
                registry.states.value
                    .filterValues { it.connected || it.registered }
                    .keys
            val connId = autoConnectMac?.let { BluetoothGamepadRegistry.idFor(it) } ?: pendingId()
            activeConnId = connId
            registry.start(connId, profile, autoConnectMac)
            _state.update {
                it.copy(stage = Stage.ADVERTISING, advertisingProfile = profile, discoverable = false)
            }
            // Discoverability is the one piece the registry can't do for us; ask
            // the Activity to fire the system prompt.
            viewModelScope.launch { _events.emit(Event.RequestDiscoverable) }
        }

        // The Activity reports the system discoverable prompt's outcome. We only
        // surface it as an indicator; the bond can still complete either way.
        fun onDiscoverableResult(granted: Boolean) {
            _state.update { it.copy(discoverable = granted) }
        }

        private fun onRegistryStates(states: Map<String, BluetoothGamepadRegistry.SlotState>) {
            if (proceeded) return
            val active = activeConnId ?: return
            // A freshly-paired session starts under a transient id; the registry
            // rewrites it to "bt:<mac>" on bond. Match our active key, else the
            // newly-connected key that wasn't already bonded when we started. Gate on
            // CONNECTED only: the HID app registers the instant we start (long before
            // the PC bonds), and proceeding on that flashes past the advertising step.
            val entry =
                states.entries.firstOrNull { it.key == active && it.value.connected }
                    ?: states.entries.firstOrNull { it.value.connected && it.key !in baselineLiveKeys }
                    ?: return
            val profile = _state.value.advertisingProfile ?: return
            proceeded = true
            activeConnId = entry.key
            // Bind the chosen input to this host so its state drives the advertised
            // pad; a Bluetooth host needs no further configuration. Binding fails
            // only if the chosen controller vanished during the bond wait.
            val bound = hub.bind(slotId, entry.key, typeFor(profile))
            emitDone(entry.value.connectedName ?: entry.key, profile, bound)
        }

        // True for "back was handled in-flow"; the Activity finishes only when
        // false. Leaving ADVERTISING tears the session down so it doesn't keep
        // advertising behind the user's back.
        fun back(): Boolean =
            when (_state.value.stage) {
                Stage.ADVERTISING -> {
                    stopActive()
                    _state.update { it.copy(stage = Stage.PICK_TYPE, advertisingProfile = null, discoverable = false) }
                    true
                }
                Stage.PICK_TYPE -> {
                    _state.update {
                        it.copy(stage = if (it.permissionMissing) Stage.PERMISSION else Stage.PICK_PC)
                    }
                    true
                }
                Stage.PERMISSION -> {
                    _state.update { it.copy(stage = Stage.PICK_PC) }
                    true
                }
                Stage.PICK_PC -> false
            }

        private fun stopActive() {
            activeConnId?.let { registry.stop(it) }
            activeConnId = null
        }

        override fun onCleared() {
            // Don't keep advertising once the wizard goes away unless we already
            // bonded and finished (the dashboard owns the live session from here).
            if (!proceeded) stopActive()
            super.onCleared()
        }

        private fun emitDone(
            hostName: String,
            profile: BluetoothGamepad.GamepadProfile,
            bound: Boolean,
        ) {
            viewModelScope.launch { _events.emit(Event.Done(hostName, profile, bound)) }
        }

        private fun typeFor(profile: BluetoothGamepad.GamepadProfile): Int =
            if (profile == BluetoothGamepad.GamepadProfile.PLAYSTATION) CONTROLLER_TYPE_PLAYSTATION else CONTROLLER_TYPE_XBOX

        private fun RememberedBt.toRow(): HostRow =
            HostRow(
                id = id,
                name = name,
                mac = mac,
                profile = profileOf(profileName),
            )

        private fun pendingId(): String = "bt-pending-${System.currentTimeMillis()}"

        private fun profileOf(profileName: String): BluetoothGamepad.GamepadProfile =
            BluetoothGamepad.GamepadProfile.entries
                .firstOrNull { it.profileName == profileName || it.name == profileName }
                ?: BluetoothGamepad.GamepadProfile.XBOX
    }
