// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState

internal data class CardActionSpec(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val kind: CardActionKind,
)

// The action row's shape IS the RecyclerView view type: each (filled count, outlined) pair
// maps to a layout holding exactly those buttons, so binding never shows or hides one.
internal data class CardActions(
    val filled: List<CardActionSpec>,
    val outlined: CardActionSpec?,
) {
    val viewType: Int get() = filled.size * 2 + if (outlined != null) 1 else 0
}

@LayoutRes
internal fun cardActionsLayoutFor(viewType: Int): Int =
    when (viewType) {
        VIEW_TYPE_O1 -> R.layout.binding_card_actions_o1
        VIEW_TYPE_F1 -> R.layout.binding_card_actions_f1
        VIEW_TYPE_F1_O1 -> R.layout.binding_card_actions_f1_o1
        VIEW_TYPE_F2 -> R.layout.binding_card_actions_f2
        VIEW_TYPE_F2_O1 -> R.layout.binding_card_actions_f2_o1
        VIEW_TYPE_F3_O1 -> R.layout.binding_card_actions_f3_o1
        VIEW_TYPE_F4_O1 -> R.layout.binding_card_actions_f4_o1
        else -> error("No card actions layout for view type $viewType")
    }

private const val VIEW_TYPE_O1 = 1
private const val VIEW_TYPE_F1 = 2
private const val VIEW_TYPE_F1_O1 = 3
private const val VIEW_TYPE_F2 = 4
private const val VIEW_TYPE_F2_O1 = 5
private const val VIEW_TYPE_F3_O1 = 7
private const val VIEW_TYPE_F4_O1 = 9

private val CONFIGURE_SPEC =
    CardActionSpec(R.drawable.ic_tune, R.string.binding_action_configure, CardActionKind.CONFIGURE)
private val SETUP_WIRED_SPEC =
    CardActionSpec(R.drawable.ic_usb, R.string.binding_action_use_wired, CardActionKind.SETUP_WIRED)

internal fun computeCardActions(row: ControllerAdapter.Row): CardActions {
    val bound = row.slot.boundStatus ?: return unboundCardActions(row)
    val hasConnection = row.slot.boundConnectionId != null
    if (!hasConnection) return unboundCardActions(row)
    return boundCardActions(row, bound)
}

private fun unboundCardActions(row: ControllerAdapter.Row): CardActions {
    val filled = mutableListOf<CardActionSpec>()
    if (row.pathCard?.wiredSwitchAvailable == true) filled += SETUP_WIRED_SPEC

    val hasNowhereToBind = row.connections.isEmpty()
    if (!hasNowhereToBind) return CardActions(filled, CONFIGURE_SPEC)

    filled += CardActionSpec(R.drawable.ic_satellite, R.string.binding_action_find_hosts, CardActionKind.FIND_HOSTS)
    return CardActions(filled, outlined = null)
}

private fun boundCardActions(
    row: ControllerAdapter.Row,
    bound: ConnectionSummary,
): CardActions {
    val filled = mutableListOf<CardActionSpec>()
    val connected = bound.live == LinkState.Connected
    val satellite = bound.kind == ConnectionKind.SATELLITE
    val pointerHost = satellite || bound.kind == ConnectionKind.MOONLIGHT
    val isVirtual = row.slot.inputType == SlotInputType.VIRTUAL

    val opensTheOnScreenPad = isVirtual && connected
    if (opensTheOnScreenPad) {
        filled += CardActionSpec(R.drawable.ic_open_gamepad, R.string.action_open_gamepad, CardActionKind.GAMEPAD)
    }
    // A phone pointer surface only exists where the phone is the slot's touch source: a USB-direct
    // pad streaming its own trackpad gets neither button, because two producers would fight over
    // the slot's single MSG_TOUCHPAD stream. The virtual slot never offers the touchpad surface:
    // its trackpad lives inside the pad itself.
    val opensTheTouchpad = satellite && connected && !isVirtual && row.pointer?.touchpadOpenable == true
    if (opensTheTouchpad) {
        filled += CardActionSpec(R.drawable.ic_open_touchpad, R.string.action_open_touchpad, CardActionKind.TOUCHPAD)
    }
    val opensTheMouse = pointerHost && connected && row.pointer?.mouseOpenable == true
    if (opensTheMouse) {
        filled += CardActionSpec(R.drawable.ic_mouse, R.string.action_open_mouse, CardActionKind.MOUSE)
    }
    val offersDirect = satellite && connected && row.pathCard?.suggestDirectForTouch == true
    if (offersDirect) {
        filled += CardActionSpec(R.drawable.ic_bolt, R.string.card_switch_to_direct, CardActionKind.SWITCH_DIRECT)
    }
    if (row.pathCard?.wiredSwitchAvailable == true) filled += SETUP_WIRED_SPEC
    return CardActions(filled, CONFIGURE_SPEC)
}
