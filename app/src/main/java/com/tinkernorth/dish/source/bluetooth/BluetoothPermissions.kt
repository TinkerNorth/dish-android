// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat

/**
 * The grant behind every BluetoothAdapter/BluetoothDevice call that reads or connects:
 * BLUETOOTH_CONNECT, a runtime permission from API 31, and below that the install-time
 * BLUETOOTH permission the manifest carries, which cannot be denied. Returns
 * [PackageManager.PERMISSION_GRANTED] or [PackageManager.PERMISSION_DENIED] like the
 * platform check it wraps, so a caller reads it the same way. [sdkInt] is the device's release;
 * a test passes one to reach both sides of the API 31 gate.
 */
internal fun Context.checkBluetoothConnectPermission(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    if (atLeast(Build.VERSION_CODES.S, sdkInt)) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        PackageManager.PERMISSION_GRANTED
    }

// The gate lint reads: with the level passed in, lint cannot see through a bare
// `sdkInt >= S` to the BLUETOOTH_CONNECT constant it guards.
@ChecksSdkIntAtLeast(parameter = 0)
private fun atLeast(
    api: Int,
    sdkInt: Int,
): Boolean = sdkInt >= api
