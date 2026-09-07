// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.ui.common.statusChipTextRes
import com.tinkernorth.dish.ui.main.BatteryUi

internal fun Context.diagKv(
    @StringRes label: Int,
    value: String,
): String = getString(R.string.diagnostics_kv, getString(label), value)

internal fun Context.diagYesNo(value: Boolean): String = getString(if (value) R.string.diagnostics_yes else R.string.diagnostics_no)

internal fun Context.hostValue(host: BoundHostDiag?): String =
    host?.let { getString(R.string.diagnostics_joined, it.label, getString(statusChipTextRes(it.live))) }
        ?: getString(R.string.binding_card_not_bound)

internal fun Context.boundSlotLines(host: BoundHostDiag): List<String> {
    val type = emulatedTypeLabel(host.kind, host.typeId, host.btProfile)
    val index = host.slotIndex
    if (index == null) {
        return listOfNotNull(type?.let { diagKv(R.string.binding_label_emulate, it) })
    }
    return listOf(
        getString(R.string.diagnostics_wire_declared, index, type.orEmpty(), host.touchpadMode.orEmpty()),
        getString(R.string.diagnostics_wire_applied, diagYesNo(host.registered == true), diagYesNo(host.streaming == true)),
    )
}

internal fun Context.hostSlotLines(
    kind: ConnectionKind,
    slot: HostSlotDiag,
    btProfile: String?,
): List<String> {
    val type = emulatedTypeLabel(kind, slot.typeId, btProfile)
    val index = slot.slotIndex
    if (index == null) {
        return listOf(type?.let { getString(R.string.diagnostics_host_bound, slot.controllerName, it) } ?: slot.controllerName)
    }
    return listOf(
        getString(R.string.diagnostics_host_slot, index, slot.controllerName, type.orEmpty(), slot.touchpadMode.orEmpty()),
        getString(R.string.diagnostics_wire_applied, diagYesNo(slot.registered == true), diagYesNo(slot.streaming == true)),
    )
}

internal fun Context.batteryValue(battery: BatteryUi): String {
    val level = battery.level?.let { getString(R.string.battery_percent, it) } ?: getString(R.string.battery_unknown_level)
    return if (battery.charging) getString(R.string.diagnostics_joined, level, getString(R.string.battery_state_charging)) else level
}
