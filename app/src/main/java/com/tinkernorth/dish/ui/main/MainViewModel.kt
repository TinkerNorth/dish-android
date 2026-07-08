// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.TouchpadSource
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.inputrate.InputRateStore
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.TouchpadModeStore
import com.tinkernorth.dish.source.store.UsbPathPreferenceStore
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.UsbGamepadManager
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
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
@Suppress("LongParameterList")
class MainViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        val satellite: SatelliteConnectionManager,
        val hub: ConnectionCoordinator,
        private val gamepadRegistry: PhysicalGamepadRegistry,
        private val batteryStatusStore: BatteryStatusStore,
        private val motionEnabledStore: MotionEnabledStore,
        private val capabilityComposer: CapabilityComposer,
        private val touchpadModeStore: TouchpadModeStore,
        private val native: PhysicalInputNative,
        private val pathPrefs: UsbPathPreferenceStore,
        private val usbGamepadManager: UsbGamepadManager,
        private val inputRateStore: InputRateStore,
    ) : ViewModel() {
        // Absence means "user has not toggled"; use isMotionEnabled() for default rather than reading directly.
        val motionEnabled: StateFlow<Map<String, Boolean>> = motionEnabledStore.state

        private val _uiState = MutableStateFlow(MainUiState())
        val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

        private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<MainEvent> = _events.asSharedFlow()

        init {
            val slotsBase =
                combine(
                    hub.connections,
                    hub.bindings,
                    gamepadRegistry.devices,
                    batteryStatusStore.samples,
                    capabilityComposer.state,
                ) { conns, bindings, devices, batteries, motionCaps ->
                    val virtual =
                        ControllerSlot(
                            id = VIRTUAL_SLOT_ID,
                            inputType = SlotInputType.VIRTUAL,
                            name = context.getString(R.string.default_virtual_controller_name),
                        )
                    val hiddenRoutedIds = routedTwinIdsHiddenBySynthetics(devices.values)
                    val physical =
                        devices.values
                            .filter { dev -> dev.isUsbSynthetic || dev.id !in hiddenRoutedIds }
                            .map { dev ->
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
                    SlotsBase(slots, conns, motionCaps, devices)
                }

            // Second stage keyed off path prefs so a choice re-evaluates promptly; the cards themselves are
            // derived from the live device state, so the badge and toggle always show the actual mode.
            combine(
                slotsBase,
                pathPrefs.state,
                inputRateStore.state,
            ) { base, _, rates ->
                val pathCards =
                    base.slots
                        .mapNotNull { slot ->
                            pathCardFor(slot, base.devices)?.let { slot.id to it }
                        }.toMap()
                val inputRates =
                    base.slots
                        .mapNotNull { slot ->
                            rates.slots[slot.id]?.let { slot.id to it }
                        }.toMap()
                SlotsRender(base.slots, base.connections, base.motionCapabilities, pathCards, inputRates, rates.screenPeakHz)
            }.onEach { render ->
                _uiState.update { prev ->
                    prev.copy(
                        slots = render.slots,
                        connections = render.connections,
                        motionCapabilities = render.motionCapabilities,
                        pathCards = render.pathCards,
                        inputRates = render.inputRates,
                        screenPeakHz = render.screenPeakHz,
                    )
                }
            }.launchIn(viewModelScope)

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

            // The pill and the launcher read the SAME projection the descriptor declares
            // (CapabilityComposer.touchpadWireMode), so the dashboard can never claim a routing
            // the satellite doesn't have. Store/bindings/type changes all funnel through the
            // composer's emissions; the store is combined in for the pre-first-emission window.
            combine(capabilityComposer.state, touchpadModeStore.state) { caps, _ ->
                caps.keys.associateWith { slotId ->
                    TouchpadSlotUi(
                        mode = capabilityComposer.touchpadWireMode(slotId),
                        phoneSourced = capabilityComposer.touchpadSource(slotId) == TouchpadSource.PHONE,
                    )
                }
            }.onEach { map ->
                _uiState.update { it.copy(touchpadBySlot = map) }
            }.launchIn(viewModelScope)
        }

        fun bindSlot(
            slotId: String,
            connectionId: String,
        ) {
            // Quick-bind keeps the slot's remembered type; Xbox only when the
            // user never made a choice for this pairing.
            val type = hub.satTypes.value[connectionId to slotId] ?: CONTROLLER_TYPE_XBOX
            hub.bind(slotId, connectionId, type)
        }

        fun unbindSlot(slotId: String) {
            hub.unbind(slotId)
        }

        fun reconnectHosts() {
            hub.autoReconnectAll()
        }

        // No-op for Bluetooth: BT type fixed by remembered host's HID profile.
        fun setSatelliteControllerType(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            hub.setSatelliteControllerType(connectionId, slotId, type)
        }

        fun setMotionEnabled(
            slotId: String,
            enabled: Boolean,
        ) {
            motionEnabledStore.setEnabled(slotId, enabled)
        }

        // Use this in render code: absence and `false` differ in the store but mean the same to the user.
        fun isMotionEnabled(slotId: String): Boolean = motionEnabledStore.isEnabled(slotId)

        private fun pathCardFor(
            slot: ControllerSlot,
            devices: Map<Int, PhysicalGamepadRegistry.Device>,
        ): PathCard? {
            if (slot.inputType != SlotInputType.PHYSICAL) return null
            val device = devices[slot.physicalDeviceId] ?: return null
            val vid = device.vendorId
            val pid = device.productId
            // While claimed the framework InputDevice is gone, so Standard caps come from the last time
            // it was seen routed; if it was never seen routed they're unknown (shown as absent).
            val standard =
                if (device.isUsbSynthetic) {
                    gamepadRegistry
                        .frameworkCapsFor(vid, pid)
                        ?.let { PathCapabilities(rumble = it.hasRumble, motion = it.hasGyro) }
                        ?: PathCapabilities(rumble = false, motion = false)
                } else {
                    PathCapabilities(rumble = device.hasRumble, motion = device.hasGyro)
                }
            return PathCardMapper.map(
                isClaimedDirect = device.isUsbSynthetic,
                transport = device.transport,
                recognized = native.isKnownFastLaneModel(vid, pid),
                restoring = device.transitioning,
                standard = standard,
                direct =
                    PathCapabilities(
                        rumble = native.modelHasRumble(vid, pid),
                        motion = native.modelHasImu(vid, pid),
                    ),
                // Only a live synthetic (not mid-release, not stuck) is actually streaming Direct.
                directPollHz = if (device.isUsbSynthetic && !device.transitioning && !device.restoreStuck) device.pollRateHz else 0,
                needsReplug = device.needsReplug,
                restoreStuck = device.restoreStuck,
                directFailure = device.directFailure,
            )
        }

        fun setInputPath(
            slotId: String,
            choice: PathChoice,
        ) {
            val deviceId = slotId.toIntOrNull() ?: return
            val device = gamepadRegistry.devices.value[deviceId] ?: return
            usbGamepadManager.setPathChoice(device.vendorId, device.productId, choice)
        }

        private data class SlotsBase(
            val slots: List<ControllerSlot>,
            val connections: List<ConnectionSummary>,
            val motionCapabilities: Map<String, SlotCapabilities>,
            val devices: Map<Int, PhysicalGamepadRegistry.Device>,
        )

        private data class SlotsRender(
            val slots: List<ControllerSlot>,
            val connections: List<ConnectionSummary>,
            val motionCapabilities: Map<String, SlotCapabilities>,
            val pathCards: Map<String, PathCard>,
            val inputRates: Map<String, SlotInputRates>,
            val screenPeakHz: Int,
        )
    }
