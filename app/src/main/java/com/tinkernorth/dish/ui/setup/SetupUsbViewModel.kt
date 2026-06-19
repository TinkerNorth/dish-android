// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.source.usb.UsbController
import com.tinkernorth.dish.source.usb.UsbGamepadManager
import com.tinkernorth.dish.source.usb.UsbPhase
import dagger.hilt.android.lifecycle.HiltViewModel
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
// step. The wizard records the mode pick and waits out the Direct claim / system
// permission; the manager owns the actual claim. The slot id handed forward is
// the live device id (synthetic on Direct, framework on Standard), resolved
// after the switch settles so the framework -> synthetic id swap can't strand it.
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
        }

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
        val events: SharedFlow<Event> = _events.asSharedFlow()

        private var activeKey: Int? = null

        init {
            usb.reconcileForeground()
            usb.controllers.onEach { onControllers(it) }.launchIn(viewModelScope)
        }

        private fun onControllers(map: Map<Int, UsbController>) {
            val rows = map.values.map { Controller(vpk(it), it.name, "%04X:%04X".format(it.vendorId, it.productId)) }
            // The controller we were configuring was unplugged: drop back to the list.
            if (activeKey != null && map[activeKey] == null) {
                activeKey = null
                _state.value = State(controllers = rows)
                return
            }
            _state.update { it.copy(controllers = rows) }
        }

        // Tapping a controller is the commit: it selects that pad and advances to
        // the mode step (there is no separate Continue).
        fun selectController(key: Int) {
            val c = usb.controllers.value[key] ?: return
            activeKey = key
            _state.update {
                it.copy(stage = Stage.MODE, verified = native.isKnownFastLaneModel(c.vendorId, c.productId))
            }
        }

        fun chooseDirect() = _state.update { it.copy(stage = Stage.GRANTING) }

        fun chooseStandard() {
            val c = current() ?: return
            usb.setPathChoice(c.vendorId, c.productId, PathChoice.Standard)
            val slot = c.frameworkId?.toString() ?: c.syntheticId?.toString() ?: return
            emitProceed(slot)
        }

        // Direct shows the Android permission prompt and then claims; wait the FSM
        // out (same settle predicate the configure screen uses) and proceed on
        // whatever it lands on, falling back to Standard if Direct is refused.
        fun showPrompt() {
            val c = current() ?: return
            val key = vpk(c)
            _state.update { it.copy(working = true) }
            viewModelScope.launch {
                usb.setPathChoice(c.vendorId, c.productId, PathChoice.Direct)
                withTimeoutOrNull(DIRECT_TIMEOUT_MS) {
                    usb.controllers.first { settled(it[key]) }
                }
                _state.update { it.copy(working = false) }
                val landed = usb.controllers.value[key]
                val slot =
                    when (landed?.phase) {
                        UsbPhase.Direct -> landed.syntheticId?.toString()
                        else -> landed?.frameworkId?.toString()
                    } ?: landed?.frameworkId?.toString() ?: landed?.syntheticId?.toString()
                if (slot != null) emitProceed(slot)
            }
        }

        // True for "back was handled in-flow"; the Activity finishes only when false.
        fun back(): Boolean =
            when (_state.value.stage) {
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

        private fun current(): UsbController? = activeKey?.let { usb.controllers.value[it] }

        private fun emitProceed(slotId: String) {
            viewModelScope.launch { _events.emit(Event.Proceed(slotId)) }
        }

        private fun vpk(c: UsbController): Int = (c.vendorId shl 16) or (c.productId and 0xFFFF)

        private companion object {
            const val DIRECT_TIMEOUT_MS = 20_000L

            fun settled(c: UsbController?): Boolean =
                when (c?.phase) {
                    UsbPhase.Direct, UsbPhase.NeedsReplug, UsbPhase.RestoreStuck -> true
                    UsbPhase.Routed -> c.desired != PathChoice.Direct
                    UsbPhase.Claiming, UsbPhase.AwaitingFramework, null -> false
                }
        }
    }
