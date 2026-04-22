package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.data.network.ConnectionSummary

// ── Per-slot types ───────────────────────────────────────────────────────

enum class SlotInputType { VIRTUAL, PHYSICAL }

/**
 * One controller slot. The virtual controller is always present (id = [VIRTUAL_SLOT_ID]).
 * Physical controllers appear when Android detects a gamepad InputDevice. A
 * slot sends its input to at most one connection from the [ConnectionHub];
 * the binding lives in the hub, this struct just caches the current bound id
 * for rendering.
 */
data class ControllerSlot(
    val id: String,
    val inputType: SlotInputType,
    val name: String,
    val physicalDeviceId: Int = -1,
    val boundConnectionId: String? = null,
    /** Cached live status of the bound connection (for rendering status dot). */
    val boundStatus: ConnectionSummary? = null,
    // Disconnecting state for physical controllers that were unplugged.
    val isDisconnecting: Boolean = false,
    val disconnectTimeLeft: Int = 0,
)

// ── Top-level UI state ───────────────────────────────────────────────────

data class MainUiState(
    val slots: List<ControllerSlot> = listOf(
        ControllerSlot(id = VIRTUAL_SLOT_ID, inputType = SlotInputType.VIRTUAL, name = "Virtual Controller")
    ),
    val connections: List<ConnectionSummary> = emptyList(),
) {
    val virtualSlot get() = slots.first { it.id == VIRTUAL_SLOT_ID }
    val physicalSlots get() = slots.filter { it.inputType == SlotInputType.PHYSICAL }
    val anyConnected get() = connections.any { it.live == com.tinkernorth.dish.data.network.ConnectionLive.CONNECTED }
}

const val VIRTUAL_SLOT_ID = "virtual"

// ── Events ───────────────────────────────────────────────────────────────

sealed class MainEvent {
    data class ShowToast(val message: String) : MainEvent()
    data class ShowPairingDialog(val connectionId: String) : MainEvent()
}
