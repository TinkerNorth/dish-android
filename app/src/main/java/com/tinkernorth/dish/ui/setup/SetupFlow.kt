// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

const val EXTRA_INPUT_TYPE = "extra_setup_input_type"
const val EXTRA_SLOT_ID = "extra_setup_slot_id"
const val EXTRA_CONNECTION_ID = "extra_setup_connection_id"

const val INPUT_USB = "usb"
const val INPUT_BLUETOOTH = "bt_controller"
const val INPUT_ONSCREEN = "onscreen"

fun inputTypeOf(raw: String?): SetupInputType =
    when (raw) {
        INPUT_USB -> SetupInputType.USB
        INPUT_BLUETOOTH -> SetupInputType.BLUETOOTH_CONTROLLER
        else -> SetupInputType.ONSCREEN
    }

fun rawOf(type: SetupInputType): String =
    when (type) {
        SetupInputType.USB -> INPUT_USB
        SetupInputType.BLUETOOTH_CONTROLLER -> INPUT_BLUETOOTH
        SetupInputType.ONSCREEN -> INPUT_ONSCREEN
    }
