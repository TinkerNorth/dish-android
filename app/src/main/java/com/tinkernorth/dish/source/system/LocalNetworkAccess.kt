// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

// Android 17 (API 37) gates LAN access; older OSes imply it via INTERNET, so this no-ops there.
object LocalNetworkAccess {
    const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val ENFORCED_SDK = 37

    fun isEnforced(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= ENFORCED_SDK

    fun isGranted(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean =
        !isEnforced(sdkInt) ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
