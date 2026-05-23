// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.composer.TouchpadModeComposer
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.connection.ConnectionEvent
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.source.store.MotionEnabledStore
import com.tinkernorth.dish.source.store.TouchpadModeStore
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
import kotlinx.coroutines.launch
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
        private val touchpadModeStore: TouchpadModeStore,
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
                Triple(slots, conns, motionCaps)
            }.onEach { (slots, conns, motionCaps) ->
                // `.update` (not `.value =`) so the parallel touchpad-mode
                // collector below can patch its single field without losing
                // it whenever this combine re-fires (a fresh MainUiState
                // would default touchpadModesBySatellite to emptyMap and
                // wipe a value the touchpad collector just wrote).
                _uiState.update { prev ->
                    prev.copy(
                        slots = slots,
                        connections = conns,
                        motionCapabilities = motionCaps,
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

            // Per-satellite touchpad mode: resolved from the user's saved
            // pick (if any) collapsed onto the server-advertised supported
            // modes. The capabilities probe isn't wired yet so the assumed
            // support set is the full one — the server-side validation in
            // `setTouchpadMode` rejects an unsupported pick and we surface
            // that as a toast. A separate collector (rather than folding
            // into the main combine above) keeps the typed-combine arity
            // at 5 and mutates only this single field via `_uiState.update`.
            //
            // The store's `state` flow is the reactive source of truth for
            // saved picks; reading it inside the combine (rather than
            // calling `store.modeFor(c.id)` on every emission) makes the
            // dependency explicit so the combine re-fires the instant a
            // pick is written — that reactive path is what makes the chip
            // selection update without an Activity reload.
            combine(hub.connections, touchpadModeStore.state) { conns, savedModes ->
                conns
                    .filter { it.kind == ConnectionKind.SATELLITE }
                    .associate { c ->
                        c.id to
                            TouchpadModeComposer.resolve(
                                savedMode = savedModes[c.id],
                                serverSupports = ASSUMED_SUPPORTED_TOUCHPAD_MODES,
                                hasLocalTouchpadCapture = true,
                            )
                    }
            }.onEach { map ->
                _uiState.update { it.copy(touchpadModesBySatellite = map) }
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

        // ── Touchpad mode ─────────────────────────────────────────────────────

        /**
         * Persist [mode] for [connectionId] locally and push to the satellite.
         * The local write happens first and unconditionally — even if the
         * server rejects, the user's pick survives so a re-connect against a
         * recovered server picks it up. A `{"error":…}` reply surfaces as a
         * toast so the user understands an unsupported pick (e.g. choosing
         * `ds4` against a macOS receiver whose only mode is `off`).
         */
        fun setSatelliteTouchpadMode(
            connectionId: String,
            mode: String,
        ) {
            if (!TouchpadModeValue.isValid(mode)) return
            // Write through the store (durable + reactive in one call). The
            // combine collector above subscribes to `touchpadModeStore.state`
            // so this single line both persists the pick AND drives the chip
            // selection update in the dashboard — no Activity reload, no
            // separate "refresh" call.
            touchpadModeStore.setMode(connectionId, mode)
            viewModelScope.launch {
                val reply = satellite.setTouchpadMode(connectionId, mode)
                // `{"ok":true,...}` → success, no toast. Any other shape is an
                // error: dispatch to a specific message so the user can act on
                // the actual cause (re-pair, check satellite is running, etc.)
                // rather than the previous catch-all "unsupported" toast that
                // masked 404 / transport failures behind a misleading label.
                if (reply.contains("\"ok\":true")) return@launch
                _events.emit(MainEvent.ShowToast(toastForTouchpadModeError(reply)))
            }
        }

        /**
         * Map a non-success reply from `POST /api/devices/touchpad-mode` to a
         * localised toast string. Recognised server shapes (see
         * `satellite/src/net/webserver.cpp::handleTouchpadModeSet`):
         *
         *   - `{"error":"mode not supported on this host","supported":false}` (409)
         *   - `{"error":"device not paired"}` (404)
         *   - `{"error":"missing id or mode"}` / `{"error":"mode must be ds4..."}` (400)
         *   - `{"error":"request failed: …"}` — synthesised by
         *     [SatelliteHttpClient.request] when the HTTPS round-trip itself
         *     throws (connection refused, TLS handshake error, timeout).
         *
         * Falls back to the server's raw `error` field (or the whole reply if
         * we can't even find one) so a yet-unknown error shape is still
         * actionable rather than swallowed.
         */
        private fun toastForTouchpadModeError(reply: String): String {
            val err = extractJsonErrorField(reply)
            return when {
                // 409 — backend genuinely doesn't support this mode (e.g. macOS
                // receiver answering "off"-only, or a future stripped backend).
                reply.contains("\"supported\":false") ->
                    context.getString(R.string.touchpad_mode_unsupported_here)
                // 404 — the satellite's pairedDevices list doesn't include
                // this dish. Actionable remedy: re-pair from Connections.
                err == "device not paired" ->
                    context.getString(R.string.touchpad_mode_error_not_paired)
                // 401 — auth middleware rejected the request. Typically means
                // the dish forgot the `X-Device-Id` header for a new route, or
                // the dish's deviceId isn't in the satellite's pairedDevices
                // list. Same remedy as 404 from the user's perspective.
                err == "unauthorized" ->
                    context.getString(R.string.touchpad_mode_error_unauthorized)
                // Transport failure synthesised by SatelliteHttpClient.request
                // when the HTTPS call itself threw (no HTTP status reached us).
                err?.startsWith("request failed:") == true ->
                    context.getString(R.string.touchpad_mode_error_transport)
                // Any other server-side rejection — pass the raw error through
                // so the user sees what the satellite actually said.
                else ->
                    context.getString(
                        R.string.touchpad_mode_error_unknown,
                        err ?: reply,
                    )
            }
        }

        /**
         * Pull the value of `"error"` out of a JSON body without taking on a
         * full JSON dep. The server always emits `{"error":"…"}` as the first
         * key on the error path, with no nesting and no embedded backslash
         * sequences in the values it uses — see the literals in
         * `handleTouchpadModeSet`. Returns `null` if the key isn't present or
         * the value isn't a simple quoted string.
         */
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

        private companion object {
            /**
             * Touchpad modes we assume every satellite supports until the
             * `/api/server/capabilities` probe lands (follow-up). Windows +
             * Linux receivers honour all three; macOS only honours `off`, but
             * a macOS satellite bails at `MSG_CONTROLLER_ADD` with
             * `ACK_ERR_BACKEND_UNAVAIL` so the touchpad UI is never reachable
             * against one. Picking an unsupported mode falls back to the
             * server's HTTP error response, surfaced as a toast.
             */
            val ASSUMED_SUPPORTED_TOUCHPAD_MODES: Set<String> = TouchpadModeValue.ALL.toSet()
        }
    }
