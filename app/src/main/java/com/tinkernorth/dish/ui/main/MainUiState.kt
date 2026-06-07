// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.source.sensor.BatteryValidator

enum class SlotInputType { VIRTUAL, PHYSICAL }

data class BatteryUi(
    val level: Int?,
    val charging: Boolean,
) {
    val isLow: Boolean get() = level != null && !charging && level <= LOW_THRESHOLD

    companion object {
        const val LOW_THRESHOLD = 15

        // Returns null only when both level and status are UNKNOWN — nothing to render.
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

data class ControllerSlot(
    val id: String,
    val inputType: SlotInputType,
    val name: String,
    val physicalDeviceId: Int = -1,
    val boundConnectionId: String? = null,
    val boundStatus: ConnectionSummary? = null,
    val battery: BatteryUi? = null,
    val isDisconnecting: Boolean = false,
    val disconnectTimeLeft: Int = 0,
)

data class MainUiState(
    val slots: List<ControllerSlot> =
        listOf(
            // Localised label set by ViewModel; blank here so no English literal survives in data layer.
            ControllerSlot(id = VIRTUAL_SLOT_ID, inputType = SlotInputType.VIRTUAL, name = ""),
        ),
    val connections: List<ConnectionSummary> = emptyList(),
    val motionCapabilities: Map<String, MotionCapability> = emptyMap(),
    val touchpadModesBySatellite: Map<String, String> = emptyMap(),
    val pathBadges: Map<String, PathBadge> = emptyMap(),
) {
    val virtualSlot get() = slots.first { it.id == VIRTUAL_SLOT_ID }
    val physicalSlots get() = slots.filter { it.inputType == SlotInputType.PHYSICAL }
    val anyConnected get() = connections.any { it.live == com.tinkernorth.dish.composer.LinkState.Connected }
    val anyConnecting get() = connections.any { it.live == com.tinkernorth.dish.composer.LinkState.Connecting }

    // Distinct from connections.count { CONNECTED }: excludes physical slots with no device attached.
    val streamingSlotCount: Int get() =
        slots.count {
            !it.isDisconnecting &&
                it.boundConnectionId != null &&
                it.boundStatus?.live == com.tinkernorth.dish.composer.LinkState.Connected &&
                (it.inputType == SlotInputType.VIRTUAL || it.physicalDeviceId >= 0)
        }
}

const val VIRTUAL_SLOT_ID = "virtual"

sealed class MainEvent {
    data class ShowToast(
        val message: String,
    ) : MainEvent()

    data class ShowPairingDialog(
        val connectionId: String,
    ) : MainEvent()
}
