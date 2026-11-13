// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.SteamBleManager
import com.tinkernorth.dish.source.store.UsbPathPreferenceStore
import javax.inject.Inject

/**
 * The physical-pad side a screen reads as one: the registry of pads the framework or USB
 * Direct sees, the native model tables that say what each model can do, the USB manager
 * that claims and releases them, the remembered USB path choice, and the manager of the Steam
 * Controllers paired over Bluetooth LE.
 */
class PhysicalPadSources
    @Inject
    constructor(
        val registry: PhysicalGamepadRegistry,
        val native: PhysicalInputNative,
        val usb: UsbGamepadManager,
        val pathPrefs: UsbPathPreferenceStore,
        val steamBle: SteamBleManager,
    )
