// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Log

private const val TAG = "BluetoothSettings"

/** The system Bluetooth settings, where a new device pairs without any permission of Dish's. */
fun Activity.openBluetoothSettings() {
    try {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no Bluetooth settings screen on this device: ${e.message}")
    }
}
