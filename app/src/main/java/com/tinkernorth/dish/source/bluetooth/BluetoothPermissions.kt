// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The grant behind every BluetoothAdapter/BluetoothDevice call that reads or connects:
 * BLUETOOTH_CONNECT, a runtime permission from API 31, and below that the install-time
 * BLUETOOTH permission the manifest carries, which cannot be denied. Returns
 * [PackageManager.PERMISSION_GRANTED] or [PackageManager.PERMISSION_DENIED] like the
 * platform check it wraps, so a caller reads it the same way.
 */
internal fun Context.checkBluetoothConnectPermission(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        PackageManager.PERMISSION_GRANTED
    }
