// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.usb.DirectClaimFailure
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.UsbController
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.UsbPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

// Stage 2 USB. Lists the connected USB gamepads (UsbGamepadManager only ever
// tracks plugged-in, gamepad-shaped devices, so the list is "connected
// controllers" by construction); tapping one commits it and moves to the mode
// step. Either mode pick records the choice and waits the manager's path FSM
// out; the slot handed forward is the live device id it lands on (synthetic on
// Direct, framework on Standard). If it settles with no live id, the wizard
// surfaces a recovery dialog instead of silently stranding the user.
@HiltViewModel
class SetupUsbViewModel
    @Inject
    constructor(
        private val usb: UsbGamepadManager,
        private val native: PhysicalInputNative,
    ) : ViewModel() {
        enum class Stage { DETECTING, MODE, GRANTING }

        data class Controller(
            val key: Int,
            val name: String,
            val code: String,
        )

        data class State(
            val stage: Stage = Stage.DETECTING,
            val controllers: List<Controller> = emptyList(),
            val verified: Boolean = false,
            val working: Boolean = false,
        )

        sealed interface Event {
            data class Proceed(
                val slotId: String,
            ) : Event

            // The path settled with no usable slot; the Activity offers retry / start over / exit.
            data class Recover(
                val reason: DirectClaimFailure?,
            ) : Event
        }

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
        val events: SharedFlow<Event> = _events.asSharedFlow()

        private var activeKey: Int? = null
        private var pathJob: Job? = null

        init {
            usb.reconcileForeground()
            usb.controllers.onEach { onControllers(it) }.launchIn(viewModelScope)
        }

        private fun onControllers(map: Map<Int, UsbController>) {
            val rows = map.values.map { Controller(vpk(it), it.name, "%04X:%04X".format(it.vendorId, it.productId)) }
            // The controller we were configuring was unplugged: abandon any in-flight switch and drop to the list.
            if (activeKey != null && map[activeKey] == null) {
                pathJob?.cancel()
                activeKey = null
                _state.value = State(controllers = rows)
                return
            }
            _state.update { it.copy(controllers = rows) }
        }

        fun selectController(key: Int) {
            val c = usb.controllers.value[key] ?: return
            activeKey = key
            _state.update {
                it.copy(stage = Stage.MODE, verified = native.isKnownFastLaneModel(c.vendorId, c.productId))
            }
        }

        fun chooseDirect() = _state.update { it.copy(stage = Stage.GRANTING) }

        fun chooseStandard() = runPath(PathChoice.Standard)

        // Direct shows the Android permission prompt and then claims; Standard just
        // asks the framework layer to (re)bind. Both drive the manager's FSM and then
        // wait it out to whatever slot it lands on.
        fun showPrompt() = runPath(PathChoice.Direct)

        private fun runPath(choice: PathChoice) {
            if (pathJob?.isActive == true) return
            val key = activeKey ?: return
            val c = usb.controllers.value[key] ?: return
            _state.update { it.copy(working = true) }
            pathJob =
                viewModelScope.launch {
                    try {
                        usb.setPathChoice(c.vendorId, c.productId, choice)
                        withTimeoutOrNull(timeoutFor(choice)) {
                            usb.controllers.first { resolved(it[key]) }
                        }
                        // The user backed out or unplugged while we waited: don't navigate behind them.
                        if (activeKey != key) return@launch
                        val landed = usb.controllers.value[key]
                        val slot = proceedSlot(landed)
                        _events.emit(if (slot != null) Event.Proceed(slot) else Event.Recover(landed?.failure))
                    } finally {
                        _state.update { it.copy(working = false) }
                    }
                }
        }

        // True for "back was handled in-flow"; the Activity finishes only when false.
        fun back(): Boolean {
            pathJob?.cancel()
            return when (_state.value.stage) {
                Stage.MODE -> {
                    activeKey = null
                    _state.update { it.copy(stage = Stage.DETECTING) }
                    true
                }
                Stage.GRANTING -> {
                    _state.update { it.copy(stage = Stage.MODE) }
                    true
                }
                Stage.DETECTING -> false
            }
        }

        private fun vpk(c: UsbController): Int = (c.vendorId shl 16) or (c.productId and 0xFFFF)

        private companion object {
            const val DIRECT_TIMEOUT_MS = 20_000L
            const val STANDARD_TIMEOUT_MS = 8_000L

            fun timeoutFor(choice: PathChoice): Long = if (choice == PathChoice.Direct) DIRECT_TIMEOUT_MS else STANDARD_TIMEOUT_MS

            // Stop waiting once the path has a live id, or has reached a dead end that never will. A Routed
            // controller still pursuing Direct is mid-permission/claim, not settled, so keep waiting.
            fun resolved(c: UsbController?): Boolean =
                when (c?.phase) {
                    UsbPhase.Direct -> c.syntheticId != null
                    UsbPhase.Routed -> c.desired != PathChoice.Direct && c.frameworkId != null
                    UsbPhase.RestoreStuck, UsbPhase.NeedsReplug -> true
                    UsbPhase.Claiming, UsbPhase.AwaitingFramework, null -> false
                }

            // The live slot to hand forward. RestoreStuck's synthetic is a detached placeholder and
            // NeedsReplug has none, so both return null and the caller recovers instead of stranding the user.
            fun proceedSlot(c: UsbController?): String? =
                when (c?.phase) {
                    UsbPhase.Direct -> c.syntheticId?.toString()
                    UsbPhase.Routed -> if (c.desired != PathChoice.Direct) c.frameworkId?.toString() else null
                    else -> null
                }
        }
    }
