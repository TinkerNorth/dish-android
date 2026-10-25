// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.view.InputDevice

internal fun ignoredInputDiags(world: DiagnosticsWorld): List<IgnoredInputDiag> =
    world.ignoredInputs
        .sortedWith(compareBy({ it.name }, { it.id }))
        .map { device ->
            IgnoredInputDiag(
                id = device.id,
                name = device.name,
                vendorId = device.vendorId,
                productId = device.productId,
                sources = device.sources,
                inputs = inputSourceKinds(device.sources),
                keyboard = keyboardKind(device.keyboardType),
            )
        }

internal fun inputSourceKinds(sources: Int): List<InputSourceKind> = InputSourceKind.entries.filter { (sources and it.mask) == it.mask }

internal fun keyboardKind(keyboardType: Int): KeyboardKind =
    when (keyboardType) {
        InputDevice.KEYBOARD_TYPE_ALPHABETIC -> KeyboardKind.FULL
        InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC -> KeyboardKind.BUTTONS_ONLY
        else -> KeyboardKind.NONE
    }
