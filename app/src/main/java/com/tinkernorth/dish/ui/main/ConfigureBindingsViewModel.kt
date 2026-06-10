// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.TouchpadModeStore
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.UsbPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    // Used to re-resolve the slot after a USB path switch replaces the
    // framework device with a synthetic twin (or vice versa).
    val vendorId: Int = 0,
    val productId: Int = 0,
)

// One "Emulate as" choice. Rendered from the satellite's catalog; `id` is the
// wire enum value the descriptor carries.
data class TypeOption(
    val id: Int,
    val label: String,
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
    val typeOptions: List<TypeOption> = emptyList(),
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
class ConfigureBindingsViewModel
    @Inject
    @Suppress("LongParameterList")
    constructor(
        @ApplicationContext private val context: Context,
        private val hub: ConnectionCoordinator,
        private val gamepadRegistry: PhysicalGamepadRegistry,
        private val motionEnabledStore: MotionEnabledStore,
        private val motionCapability: MotionCapabilityComposer,
        private val touchpadModeStore: TouchpadModeStore,
        private val satellite: SatelliteConnectionManager,
        private val usbGamepadManager: UsbGamepadManager,
        private val catalogRepo: SatelliteCatalogRepository,
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
            val draft = buildSeedDraft(slotId)
            _ui.value =
                ConfigUiState(
                    loaded = true,
                    snapshot = snapshot,
                    hosts = hosts,
                    draft = draft,
                    typeOptions = bundledTypeOptions(),
                )
            draft.hostId?.let { refreshTypeOptions(it) }
            // Refresh the host list as connections come and go, without disturbing the in-progress draft.
            hub.connections
                .onEach { _ui.update { state -> state.copy(hosts = buildHosts()) } }
                .launchIn(viewModelScope)
        }

        fun setHost(hostId: String) {
            _ui.update { it.copy(draft = it.draft?.copy(hostId = hostId)) }
            refreshTypeOptions(hostId)
        }

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

        /**
         * Nothing the user edits commits until here. The whole binding is ONE
         * declarative call to the satellite — the descriptor (type, caps,
         * touchpad routing) travels with the bind, so the overlay shows one
         * spinner per real async action: the USB-direct switch (which can wait
         * on a system permission prompt) and the single REST round-trip.
         */
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

                // Step 1 (USB only): switch the input path. Direct shows a
                // system permission prompt, so this single step can take as
                // long as the user takes to answer it.
                var directFellBack = false
                if (snapshot.link == BindingLink.USB && snapshot.directCapable) {
                    val achieved = applyUsbPath(snapshot.slotId, draft.directOn)
                    directFellBack = draft.directOn && !achieved
                    done++
                    _applyState.value = ApplyState.Running(steps, done)
                }

                // The path switch may have swapped the framework device for a
                // synthetic twin (or back); bind whatever id is live NOW.
                val slotId = resolveCurrentSlotId(snapshot)

                // Step 2: the binding itself — bind with the FULL descriptor,
                // then await the satellite's applied confirmation (the session
                // or controller PUT round-trip). One user action, one call.
                if (state.motionAvailable) {
                    // Local gate; its capability bit rides the same descriptor.
                    motionEnabledStore.setEnabled(slotId, draft.motionOn)
                }
                val mode = if (TouchpadModeValue.isValid(draft.touchpadMode)) draft.touchpadMode else TouchpadModeValue.OFF
                if (state.touchpadAvailable) touchpadModeStore.setMode(hostId, mode)
                val bound = hub.bind(slotId, hostId, draft.type, mode)
                if (!bound) {
                    _applyState.value =
                        ApplyState.Finished(
                            errorMessage = context.getString(R.string.binding_apply_error_slot_gone, snapshot.name),
                            warningMessage = null,
                            hostName = host.label,
                            controllerName = snapshot.name,
                        )
                    return@launch
                }
                val applied = awaitApplied(host, slotId)
                done++
                _applyState.value = ApplyState.Running(steps, done)
                if (!applied) {
                    _applyState.value =
                        ApplyState.Finished(
                            errorMessage = context.getString(R.string.binding_apply_error_no_connect, host.label),
                            warningMessage = null,
                            hostName = host.label,
                            controllerName = snapshot.name,
                        )
                    return@launch
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

        // One spinner per async action: the USB path switch (waits on the
        // permission prompt), then the single satellite round-trip.
        private fun buildSteps(state: ConfigUiState): List<ApplyStep> {
            val out = mutableListOf<ApplyStep>()
            val snapshot = state.snapshot
            if (snapshot?.link == BindingLink.USB && snapshot.directCapable) {
                out += ApplyStep(STEP_DIRECT, context.getString(R.string.binding_label_connection))
            }
            out += ApplyStep(STEP_APPLY, context.getString(R.string.binding_label_destination))
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

        // A USB path switch can retire the slot id the screen was opened with:
        // Direct replaces the framework id with a synthetic twin; Standard
        // re-enumerates the framework id. Prefer whichever exists right now.
        private fun resolveCurrentSlotId(snapshot: BindingSnapshot): String {
            val original = snapshot.slotId.toIntOrNull() ?: return snapshot.slotId
            if (gamepadRegistry.devices.value.containsKey(original)) return snapshot.slotId
            if (snapshot.vendorId == 0 && snapshot.productId == 0) return snapshot.slotId
            val twin =
                gamepadRegistry.devices.value.values.firstOrNull {
                    it.vendorId == snapshot.vendorId && it.productId == snapshot.productId
                }
            return twin?.id?.toString() ?: snapshot.slotId
        }

        /**
         * The bind's REST round-trip, observed: a satellite host is applied
         * once the slot's descriptor is confirmed (`registered`); a Bluetooth
         * host once the link is live. Times out into the error overlay rather
         * than spinning forever.
         */
        private suspend fun awaitApplied(
            host: BindingHost,
            slotId: String,
        ): Boolean {
            val hostUp =
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    hub.connections.first { conns ->
                        val live = conns.firstOrNull { it.id == host.id }?.live
                        live == LinkState.Connected || live == LinkState.Unstable
                    }
                    true
                } ?: false
            if (!hostUp) return false
            if (host.kind != ConnectionKind.SATELLITE) return true
            val conn = satellite.get(host.id) ?: return false
            return withTimeoutOrNull(APPLY_TIMEOUT_MS) {
                conn.slots.first { it[slotId]?.registered == true }
                true
            } ?: false
        }

        // Bundled fallback when no catalog has ever been fetched (offline
        // first-run): the two types this app ships art for.
        private fun bundledTypeOptions(): List<TypeOption> =
            listOf(
                TypeOption(CONTROLLER_TYPE_PLAYSTATION, context.getString(R.string.picker_type_playstation)),
                TypeOption(CONTROLLER_TYPE_XBOX, context.getString(R.string.picker_type_xbox)),
            )

        // The "Emulate" picker renders from the satellite's catalog. Known
        // slugs keep the bundled labels (native feel); an UNKNOWN slug still
        // renders from the server-provided name — a type newer than this app
        // is offered, never blank, never an error.
        private fun refreshTypeOptions(hostId: String) {
            val conn = satellite.get(hostId)
            if (conn == null) {
                _ui.update { it.copy(typeOptions = bundledTypeOptions()) }
                return
            }
            catalogRepo.cached(hostId)?.let { cached ->
                _ui.update { state -> state.copy(typeOptions = typeOptionsFrom(cached.controllerTypes)) }
            }
            viewModelScope.launch {
                val catalog = catalogRepo.catalogFor(conn.server.value, hostId) ?: return@launch
                _ui.update { state -> state.copy(typeOptions = typeOptionsFrom(catalog.controllerTypes)) }
            }
        }

        private fun typeOptionsFrom(types: List<com.tinkernorth.dish.core.model.CatalogTypeDto>): List<TypeOption> {
            if (types.isEmpty()) return bundledTypeOptions()
            return types.map { t ->
                val bundled =
                    when (t.slug) {
                        SLUG_XBOX360 -> context.getString(R.string.picker_type_xbox)
                        SLUG_DS4 -> context.getString(R.string.picker_type_playstation)
                        else -> null
                    }
                TypeOption(t.id, bundled ?: t.name.ifBlank { t.slug })
            }
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
                vendorId = vid,
                productId = pid,
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
            const val DIRECT_TIMEOUT_MS = 20_000L
            const val CONNECT_TIMEOUT_MS = 8_000L
            const val APPLY_TIMEOUT_MS = 8_000L
            const val STEP_DIRECT = "direct"
            const val STEP_APPLY = "apply"
            const val SLUG_XBOX360 = "xbox360"
            const val SLUG_DS4 = "ds4"
        }
    }
