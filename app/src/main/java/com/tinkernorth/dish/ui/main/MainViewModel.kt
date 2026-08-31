// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.CapabilityComposer
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.TouchpadSource
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.inputrate.InputRateStore
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.UsbPathPreferenceStore
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.UsbController
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.ui.common.GamepadSkin
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
        private val native: PhysicalInputNative,
        private val pathPrefs: UsbPathPreferenceStore,
        private val usbGamepadManager: UsbGamepadManager,
        private val inputRateStore: InputRateStore,
        private val hostFeaturesStore: SatelliteHostFeaturesStore,
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
                usbGamepadManager.controllers,
            ) { base, _, rates, usbControllers ->
                val pathCards =
                    base.slots
                        .mapNotNull { slot ->
                            pathCardFor(slot, base.devices, usbControllers)?.let { slot.id to it }
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

            // The buttons and the wire read the SAME capability projection, so a card can
            // never offer a surface the satellite would dead-letter: touch needs the type
            // to carry a trackpad, mouse needs the host grant, and both need the phone to
            // be the slot's touch source.
            capabilityComposer.state
                .onEach { caps ->
                    val map =
                        caps.mapValues { (slotId, cap) ->
                            val phoneSourced = capabilityComposer.touchpadSource(slotId) == TouchpadSource.PHONE
                            PointerSlotUi(
                                mode = capabilityComposer.touchpadWireMode(slotId),
                                touchpadOpenable =
                                    phoneSourced && slotId != VIRTUAL_SLOT_ID && cap.isAvailable(Feature.TOUCHPAD),
                                mouseOpenable = phoneSourced && cap.isAvailable(Feature.MOUSE),
                            )
                        }
                    _uiState.update { it.copy(pointerBySlot = map) }
                }.launchIn(viewModelScope)

            hostFeaturesStore.state
                .onEach { features ->
                    _uiState.update { it.copy(hostCompat = features.mapValues { (_, f) -> f.compat }) }
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

        // The skin the on-screen pad opens with, matching the pad the host really builds:
        // a Bluetooth host carries it in the profile name, a Moonlight host in its own type
        // table (Auto resolved the same way the session resolves it), a satellite in the
        // per-slot catalog type.
        fun gamepadSkinFor(slotId: String): GamepadSkin {
            val summary =
                uiState.value.slots
                    .firstOrNull { it.id == slotId }
                    ?.boundStatus
            return when (summary?.kind) {
                ConnectionKind.BLUETOOTH -> GamepadSkin.forBtProfile(summary.btProfile)
                ConnectionKind.MOONLIGHT -> GamepadSkin.forMoonlightType(resolvedMoonlightType(slotId, summary))
                else -> GamepadSkin.forControllerType(summary?.satelliteControllerTypes?.get(slotId) ?: CONTROLLER_TYPE_XBOX)
            }
        }

        private fun resolvedMoonlightType(
            slotId: String,
            summary: ConnectionSummary,
        ): Int {
            val picked = MoonlightEmulatedType.fromStored(summary.satelliteControllerTypes[slotId] ?: MoonlightEmulatedType.AUTO)
            if (picked != MoonlightEmulatedType.AUTO) return picked
            val source =
                capabilityComposer.capabilityForCandidate(
                    slotId = slotId,
                    candidateType = MoonlightEmulatedType.XBOX,
                    candidateHostKind = ConnectionKind.MOONLIGHT,
                    candidateHostId = summary.id,
                )
            return MoonlightEmulatedType.resolve(picked, source.inputOk(Feature.MOTION))
        }

        private fun pathCardFor(
            slot: ControllerSlot,
            devices: Map<Int, PhysicalGamepadRegistry.Device>,
            usbControllers: Map<Int, UsbController>,
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
                padHasTouchpad = native.modelHasTouchpad(vid, pid),
                wiredUsbPresent = wiredUsbPresentFor(device, usbControllers.values),
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
