// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

enum class BluetoothPermissionBannerVariant {
    CONNECT,
    SCAN,
}

object BluetoothPermissionBannerDecision {
    fun evaluate(
        state: BluetoothPermissionState,
        dismissed: Boolean,
    ): BluetoothPermissionBannerVariant? {
        if (dismissed) return null
        return when {
            state.connectMissing -> BluetoothPermissionBannerVariant.CONNECT
            state.scanMissing -> BluetoothPermissionBannerVariant.SCAN
            else -> null
        }
    }
}
