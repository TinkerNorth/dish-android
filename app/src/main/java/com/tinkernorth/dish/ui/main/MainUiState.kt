// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.store.BatteryStatusStore

// ── Per-slot types ───────────────────────────────────────────────────────

enum class SlotInputType { VIRTUAL, PHYSICAL }

/**
 * Battery state of a controller slot, rendered by [ControllerAdapter] as a
 * percentage + icon on the slot row. Built from the latest wire
 * `(level, status)` reported for the slot (see
 * [com.tinkernorth.dish.source.store.BatteryStatusStore]).
 *
 * [level] is 0..100, or null when the source reported the
 * [BatteryValidator.LEVEL_UNKNOWN] sentinel (a pad that exposes a charging
 * status but no percentage). [charging] is true while on mains or full.
 */
data class BatteryUi(
    val level: Int?,
    val charging: Boolean,
) {
    /** True for a level the user should be warned about (low battery). */
    val isLow: Boolean get() = level != null && !charging && level <= LOW_THRESHOLD

    companion object {
        /** Below this percent (and not charging) the indicator turns red. */
        const val LOW_THRESHOLD = 15

        /**
         * Build a [BatteryUi] from a wire `(level, status)` pair, or null when
         * there is nothing meaningful to show. A [BatteryValidator.LEVEL_UNKNOWN]
         * level with an unknown status carries no information, so it collapses
         * to null; a known status with an unknown level still renders (as a
         * charging/discharging icon with no percentage).
         */
        fun fromWire(
            level: Int,
            status: Int,
        ): BatteryUi? {
            val charging =
                status == BatteryValidator.STATUS_CHARGING ||
                    status == BatteryValidator.STATUS_FULL ||
                    status == BatteryValidator.STATUS_WIRED
            val pct = if (level == BatteryValidator.LEVEL_UNKNOWN) null else level
            if (pct == null && status == BatteryValidator.STATUS_UNKNOWN) return null
            return BatteryUi(level = pct, charging = charging)
        }
    }
}

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
    /** Latest reported battery for this slot, or null if none reported yet. */
    val battery: BatteryUi? = null,
    // Disconnecting state for physical controllers that were unplugged.
    val isDisconnecting: Boolean = false,
    val disconnectTimeLeft: Int = 0,
)

// ── Top-level UI state ───────────────────────────────────────────────────

data class MainUiState(
    val slots: List<ControllerSlot> =
        listOf(
            // Placeholder name; the real localised label is set by the
            // ViewModel from R.string.default_virtual_controller_name on the
            // first state emission. Kept blank here so no English literal
            // survives in the data layer.
            ControllerSlot(id = VIRTUAL_SLOT_ID, inputType = SlotInputType.VIRTUAL, name = ""),
        ),
    val connections: List<ConnectionSummary> = emptyList(),
) {
    val virtualSlot get() = slots.first { it.id == VIRTUAL_SLOT_ID }
    val physicalSlots get() = slots.filter { it.inputType == SlotInputType.PHYSICAL }
    val anyConnected get() = connections.any { it.live == com.tinkernorth.dish.composer.LinkState.Connected }

    /**
     * Slots that can route input to a live connection right now: bound to a
     * CONNECTED connection, not currently tearing down, and either virtual
     * (always has an input source — the on-screen overlay) or physical with
     * a real device attached. This is the "Streaming · N controllers" count
     * — distinct from `connections.count { CONNECTED }` which would inflate
     * when a remembered connection is live but nothing is plugged in to feed
     * it.
     */
    val streamingSlotCount: Int get() =
        slots.count {
            !it.isDisconnecting &&
                it.boundConnectionId != null &&
                it.boundStatus?.live == com.tinkernorth.dish.composer.LinkState.Connected &&
                (it.inputType == SlotInputType.VIRTUAL || it.physicalDeviceId >= 0)
        }
}

const val VIRTUAL_SLOT_ID = "virtual"

// ── Events ───────────────────────────────────────────────────────────────

sealed class MainEvent {
    data class ShowToast(
        val message: String,
    ) : MainEvent()

    data class ShowPairingDialog(
        val connectionId: String,
    ) : MainEvent()
}
