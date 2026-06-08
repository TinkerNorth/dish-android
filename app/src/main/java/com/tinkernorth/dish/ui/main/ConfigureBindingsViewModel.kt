// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.TouchpadModeStore
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.UsbPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class BindingLink { USB, BLUETOOTH, ONSCREEN }

data class BindingHost(
    val id: String,
    val label: String,
    val kind: ConnectionKind,
)

data class BindingSnapshot(
    val slotId: String,
    val name: String,
    val link: BindingLink,
    val directCapable: Boolean,
    val directVerified: Boolean,
    val hasRumble: Boolean,
    val hasGyro: Boolean,
    val hasTouchpad: Boolean,
    val bound: Boolean,
    val directPollHz: Int,
)

data class BindingDraft(
    val hostId: String?,
    val type: Int,
    val directOn: Boolean,
    val motionOn: Boolean,
    val touchpadMode: String,
)

data class ConfigUiState(
    val loaded: Boolean = false,
    val snapshot: BindingSnapshot? = null,
    val hosts: List<BindingHost> = emptyList(),
    val draft: BindingDraft? = null,
) {
    val selectedHost: BindingHost? get() = hosts.firstOrNull { it.id == draft?.hostId }
    val noHosts: Boolean get() = hosts.isEmpty()
    val hostChosen: Boolean get() = selectedHost != null

    // Motion only carries on a Satellite host emulating PlayStation (Xbox has no gyro sink,
    // Bluetooth no motion channel); touchpad only on Satellite; DS4 "Pad" is PlayStation-only.
    val motionAvailable: Boolean
        get() =
            snapshot?.hasGyro == true &&
                selectedHost?.kind == ConnectionKind.SATELLITE &&
                draft?.type == CONTROLLER_TYPE_PLAYSTATION

    val touchpadAvailable: Boolean
        get() = selectedHost?.kind == ConnectionKind.SATELLITE

    val padModeAvailable: Boolean
        get() = touchpadAvailable && draft?.type == CONTROLLER_TYPE_PLAYSTATION

    val isBluetoothHost: Boolean get() = selectedHost?.kind == ConnectionKind.BLUETOOTH
}

data class ApplyStep(
    val key: String,
    val label: String,
)

sealed interface ApplyState {
    data object Idle : ApplyState

    data class Running(
        val steps: List<ApplyStep>,
        val doneCount: Int,
    ) : ApplyState

    data class Finished(
        val errorMessage: String?,
        val warningMessage: String?,
        val hostName: String,
        val controllerName: String,
    ) : ApplyState
}

@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions")
class ConfigureBindingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val hub: ConnectionHub,
        private val gamepadRegistry: PhysicalGamepadRegistry,
        private val motionEnabledStore: MotionEnabledStore,
        private val motionCapability: MotionCapabilityComposer,
        private val touchpadModeStore: TouchpadModeStore,
        private val satellite: SatelliteConnectionManager,
        private val usbGamepadManager: UsbGamepadManager,
        private val native: PhysicalInputNative,
    ) : ViewModel() {
        private val _ui = MutableStateFlow(ConfigUiState())
        val ui: StateFlow<ConfigUiState> = _ui.asStateFlow()

        private val _applyState = MutableStateFlow<ApplyState>(ApplyState.Idle)
        val applyState: StateFlow<ApplyState> = _applyState.asStateFlow()

        private var loadedSlotId: String? = null

        fun load(slotId: String) {
            if (loadedSlotId == slotId) return
            loadedSlotId = slotId
            val snapshot = buildSnapshot(slotId)
            val hosts = buildHosts()
            _ui.value = ConfigUiState(loaded = true, snapshot = snapshot, hosts = hosts, draft = buildSeedDraft(slotId))
            // Refresh the host list as connections come and go, without disturbing the in-progress draft.
            hub.connections
                .onEach { _ui.update { state -> state.copy(hosts = buildHosts()) } }
                .launchIn(viewModelScope)
        }

        fun setHost(hostId: String) = _ui.update { it.copy(draft = it.draft?.copy(hostId = hostId)) }

        fun setType(type: Int) =
            _ui.update { state ->
                val d = state.draft ?: return@update state
                val nextTouchpad =
                    if (type != CONTROLLER_TYPE_PLAYSTATION && d.touchpadMode == TouchpadModeValue.DS4) {
                        TouchpadModeValue.OFF
                    } else {
                        d.touchpadMode
                    }
                state.copy(draft = d.copy(type = type, touchpadMode = nextTouchpad))
            }

        fun setDirect(on: Boolean) = _ui.update { it.copy(draft = it.draft?.copy(directOn = on)) }

        fun setMotion(on: Boolean) = _ui.update { it.copy(draft = it.draft?.copy(motionOn = on)) }

        fun setTouchpad(mode: String) = _ui.update { it.copy(draft = it.draft?.copy(touchpadMode = mode)) }

        fun dismissApplyResult() {
            _applyState.value = ApplyState.Idle
        }

        fun unbind() {
            loadedSlotId?.let { hub.unbind(it) }
        }

        // Nothing the user edits commits until here; Apply pushes each setting in turn so the overlay can
        // show the sequence, and awaits the touchpad round-trip so a real satellite rejection surfaces.
        fun apply() {
            val state = _ui.value
            val snapshot = state.snapshot ?: return
            val draft = state.draft ?: return
            val hostId = draft.hostId ?: return
            val host = state.hosts.firstOrNull { it.id == hostId } ?: return
            if (_applyState.value is ApplyState.Running) return

            val steps = buildSteps(state)
            viewModelScope.launch {
                var done = 0
                _applyState.value = ApplyState.Running(steps, done)

                var directFellBack = false
                if (snapshot.link == BindingLink.USB && snapshot.directCapable) {
                    val achieved = applyUsbPath(snapshot.slotId, draft.directOn)
                    directFellBack = draft.directOn && !achieved
                }
                done = advance(steps, done)

                hub.bind(snapshot.slotId, hostId)
                if (!awaitHostConnected(hostId)) {
                    _applyState.value =
                        ApplyState.Finished(
                            errorMessage = context.getString(R.string.binding_apply_error_no_connect, host.label),
                            warningMessage = null,
                            hostName = host.label,
                            controllerName = snapshot.name,
                        )
                    return@launch
                }
                done = advance(steps, done)

                if (host.kind == ConnectionKind.SATELLITE) {
                    hub.setSatelliteControllerType(hostId, snapshot.slotId, draft.type)
                }
                done = advance(steps, done)

                if (state.motionAvailable) {
                    motionEnabledStore.setEnabled(snapshot.slotId, draft.motionOn)
                    done = advance(steps, done)
                }

                if (state.touchpadAvailable) {
                    val error = commitTouchpad(hostId, draft.touchpadMode)
                    advance(steps, done, delayAfter = false)
                    if (error != null) {
                        _applyState.value =
                            ApplyState.Finished(
                                errorMessage = error,
                                warningMessage = null,
                                hostName = host.label,
                                controllerName = snapshot.name,
                            )
                        return@launch
                    }
                }

                val warningMessage =
                    if (directFellBack) {
                        context.getString(R.string.binding_apply_warn_detail, snapshot.name, host.label)
                    } else {
                        null
                    }
                _applyState.value =
                    ApplyState.Finished(
                        errorMessage = null,
                        warningMessage = warningMessage,
                        hostName = host.label,
                        controllerName = snapshot.name,
                    )
            }
        }

        private suspend fun advance(
            steps: List<ApplyStep>,
            done: Int,
            delayAfter: Boolean = true,
        ): Int {
            val next = done + 1
            _applyState.value = ApplyState.Running(steps, next)
            if (delayAfter) delay(STEP_DELAY_MS)
            return next
        }

        private fun buildSteps(state: ConfigUiState): List<ApplyStep> {
            val out =
                mutableListOf(
                    ApplyStep(STEP_CONNECTION, context.getString(R.string.binding_label_connection)),
                    ApplyStep(STEP_DESTINATION, context.getString(R.string.binding_label_destination)),
                    ApplyStep(STEP_TYPE, context.getString(R.string.binding_label_emulate)),
                )
            if (state.motionAvailable) out += ApplyStep(STEP_MOTION, context.getString(R.string.binding_func_motion))
            if (state.touchpadAvailable) out += ApplyStep(STEP_TOUCHPAD, context.getString(R.string.binding_func_touchpad))
            return out
        }

        private suspend fun applyUsbPath(
            slotId: String,
            wantDirect: Boolean,
        ): Boolean {
            val deviceId = slotId.toIntOrNull() ?: return !wantDirect
            val device = gamepadRegistry.devices.value[deviceId] ?: return !wantDirect
            usbGamepadManager.setPathChoice(
                device.vendorId,
                device.productId,
                if (wantDirect) PathChoice.Direct else PathChoice.Standard,
            )
            if (!wantDirect) return true
            val key = (device.vendorId shl 16) or device.productId
            // Direct shows a system permission prompt; wait out the FSM (Routed while still wanting Direct = prompt open).
            val settled =
                withTimeoutOrNull(DIRECT_TIMEOUT_MS) {
                    usbGamepadManager.controllers.first { map ->
                        val c = map[key]
                        when (c?.phase) {
                            UsbPhase.Direct, UsbPhase.NeedsReplug, UsbPhase.RestoreStuck -> true
                            UsbPhase.Routed -> c.desired != PathChoice.Direct
                            UsbPhase.Claiming, UsbPhase.AwaitingFramework, null -> false
                        }
                    }
                }
            return settled?.get(key)?.phase == UsbPhase.Direct
        }

        // Apply only records routing intent, so confirm the host reaches a live link before calling it a success.
        private suspend fun awaitHostConnected(hostId: String): Boolean =
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                hub.connections.first { conns ->
                    val live = conns.firstOrNull { it.id == hostId }?.live
                    live == LinkState.Connected || live == LinkState.Unstable
                }
                true
            } ?: false

        private suspend fun commitTouchpad(
            connectionId: String,
            mode: String,
        ): String? {
            if (!TouchpadModeValue.isValid(mode)) return null
            touchpadModeStore.setMode(connectionId, mode)
            val reply = satellite.setTouchpadMode(connectionId, mode)
            if (reply.contains("\"ok\":true")) return null
            return touchpadErrorMessage(reply)
        }

        private fun touchpadErrorMessage(reply: String): String {
            val err = extractJsonErrorField(reply)
            return when {
                reply.contains("\"supported\":false") ->
                    context.getString(R.string.touchpad_mode_unsupported_here)
                err == "device not paired" ->
                    context.getString(R.string.touchpad_mode_error_not_paired)
                err == "unauthorized" ->
                    context.getString(R.string.touchpad_mode_error_unauthorized)
                err?.startsWith("request failed:") == true ->
                    context.getString(R.string.touchpad_mode_error_transport)
                else ->
                    context.getString(R.string.touchpad_mode_error_unknown, err ?: reply)
            }
        }

        private fun extractJsonErrorField(json: String): String? {
            val keyIdx = json.indexOf("\"error\"")
            if (keyIdx < 0) return null
            val colon = json.indexOf(':', startIndex = keyIdx + "\"error\"".length)
            if (colon < 0) return null
            val openQuote = json.indexOf('"', startIndex = colon + 1)
            if (openQuote < 0) return null
            val closeQuote = json.indexOf('"', startIndex = openQuote + 1)
            if (closeQuote < 0) return null
            return json.substring(openQuote + 1, closeQuote)
        }

        private fun buildSnapshot(slotId: String): BindingSnapshot {
            val bound = hub.bindings.value[slotId] != null
            if (slotId == VIRTUAL_SLOT_ID) {
                return BindingSnapshot(
                    slotId = slotId,
                    name = context.getString(R.string.default_virtual_controller_name),
                    link = BindingLink.ONSCREEN,
                    directCapable = false,
                    directVerified = false,
                    hasRumble = false,
                    hasGyro = motionCapability.capabilityFor(slotId).hasGyro,
                    hasTouchpad = true,
                    bound = bound,
                    directPollHz = 0,
                )
            }
            val device = slotId.toIntOrNull()?.let { gamepadRegistry.devices.value[it] }
            val isUsb = device?.transport != Transport.Bluetooth
            val vid = device?.vendorId ?: 0
            val pid = device?.productId ?: 0
            return BindingSnapshot(
                slotId = slotId,
                name = device?.name ?: "",
                link = if (isUsb) BindingLink.USB else BindingLink.BLUETOOTH,
                directCapable = isUsb,
                directVerified = native.isKnownFastLaneModel(vid, pid),
                hasRumble = (device?.hasRumble ?: false) || native.modelHasRumble(vid, pid),
                hasGyro = (device?.hasGyro ?: false) || native.modelHasImu(vid, pid),
                hasTouchpad = false,
                bound = bound,
                directPollHz = device?.pollRateHz ?: 0,
            )
        }

        private fun buildHosts(): List<BindingHost> =
            connectionsVisibleInPicker(hub.connections.value, loadedSlotId?.let { hub.bindings.value[it] })
                .map { BindingHost(it.id, it.label, it.kind) }

        private fun buildSeedDraft(slotId: String): BindingDraft {
            val hostId = hub.bindings.value[slotId]
            val type = hostId?.let { hub.satTypes.value[it to slotId] } ?: CONTROLLER_TYPE_XBOX
            val device = slotId.toIntOrNull()?.let { gamepadRegistry.devices.value[it] }
            val touchpad = hostId?.let { touchpadModeStore.modeFor(it) } ?: TouchpadModeValue.OFF
            return BindingDraft(
                hostId = hostId,
                type = type,
                directOn = device?.isUsbSynthetic ?: false,
                motionOn = motionEnabledStore.isEnabled(slotId),
                touchpadMode = if (TouchpadModeValue.isValid(touchpad)) touchpad else TouchpadModeValue.OFF,
            )
        }

        private companion object {
            const val STEP_DELAY_MS = 360L
            const val DIRECT_TIMEOUT_MS = 20_000L
            const val CONNECT_TIMEOUT_MS = 8_000L
            const val STEP_CONNECTION = "connection"
            const val STEP_DESTINATION = "destination"
            const val STEP_TYPE = "type"
            const val STEP_MOTION = "motion"
            const val STEP_TOUCHPAD = "touchpad"
        }
    }
