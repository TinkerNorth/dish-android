// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the user has granted BLUETOOTH_CONNECT on API 31+. */
enum class BluetoothPermissionState {
    /** Not required on this API (pre-31). */
    NOT_REQUIRED,

    /** Required and granted. */
    GRANTED,

    /** Required and at least one permission denied / never asked. */
    DENIED,
}

/**
 * Re-checks the BT runtime permission state on every process foreground. Permissions
 * can be revoked while the app is backgrounded (App Info → Force stop → Permissions,
 * or the OS auto-revoke after long inactivity); without a re-check on resume the
 * activity would silently fail on the next auto-reconnect.
 *
 * **Pattern:** [AbstractStateSource]`<BluetoothPermissionState>` —
 * polls on `onStart` plus an imperative [refresh] called from the permission
 * launcher callback.
 */
@Singleton
class BluetoothPermissionStateObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AbstractStateSource<BluetoothPermissionState>(BluetoothPermissionState.NOT_REQUIRED) {
        init {
            setState(currentState())
        }

        override fun onStart(owner: LifecycleOwner) {
            // Re-poll on every foreground — the OS doesn't broadcast revocation.
            setState(currentState())
        }

        /** Force a re-poll (called after the Connections activity's permission launcher). */
        fun refresh() {
            setState(currentState())
        }

        private fun currentState(): BluetoothPermissionState {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return BluetoothPermissionState.NOT_REQUIRED
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            return if (granted) BluetoothPermissionState.GRANTED else BluetoothPermissionState.DENIED
        }
    }
