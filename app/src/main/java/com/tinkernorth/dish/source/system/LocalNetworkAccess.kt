// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

// Android 17 (API 37) blocks LAN access — mDNS, UDP, direct sockets — until the user
// grants ACCESS_LOCAL_NETWORK. Older OSes keep implicit access via INTERNET, a no-op here.
object LocalNetworkAccess {
    // Platform string, not a Manifest.permission constant: the constant isn't stable
    // across the preview SDKs we compile against.
    const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val ENFORCED_SDK = 37

    // sdkInt injectable for tests.
    fun isEnforced(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= ENFORCED_SDK

    fun isGranted(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean =
        !isEnforced(sdkInt) ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
