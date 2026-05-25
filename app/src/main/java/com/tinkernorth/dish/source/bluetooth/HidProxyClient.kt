// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.core.input.BluetoothGamepad

interface HidProxyClient {
    interface Events {
        fun onAcquired()

        fun onReleased()

        fun onAppRegistered()

        fun onAppUnregistered()

        fun onHostConnected(
            mac: String,
            name: String?,
        )

        fun onHostDisconnected(mac: String)

        fun onError(message: String)
    }

    fun isAdapterEnabled(): Boolean

    fun acquire(events: Events)

    fun registerApp(profile: BluetoothGamepad.GamepadProfile)

    fun connectToHost(mac: String)

    fun disconnectCurrentHost()

    fun findOsConnectedHost(mac: String): String?

    fun sendReport(report: ByteArray): Boolean

    // Safe to call when nothing is bound; required on every teardown path.
    fun unregisterAndRelease()
}
