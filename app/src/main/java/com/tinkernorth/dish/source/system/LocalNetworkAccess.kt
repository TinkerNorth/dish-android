// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

// Android 17 (API 37) enforces Local Network Protections: mDNS/UDP/raw-socket
// access to the LAN is blocked until the user grants ACCESS_LOCAL_NETWORK. That
// gate covers everything the satellite transport does — NsdManager discovery, UDP
// discovery/streaming, and the direct socket connect — so without the grant the OS
// interrupts scanning with its own "choose a device" picker. Earlier releases keep
// implicit LAN access via INTERNET, so on them this is a no-op and callers scan as before.
object LocalNetworkAccess {
    // Referenced as the platform string rather than a Manifest.permission constant:
    // the constant isn't stable across the preview SDKs the app compiles against, and
    // this is also exactly what AndroidManifest.xml declares.
    const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    // First release to enforce LNP by default. A literal, not VERSION_CODES, because
    // the matching codename constant isn't final across preview SDK revisions.
    private const val ENFORCED_SDK = 37

    fun isEnforced(): Boolean = Build.VERSION.SDK_INT >= ENFORCED_SDK

    // True when the grant isn't needed (older OS) or has already been given.
    fun isGranted(context: Context): Boolean =
        !isEnforced() ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
