// SPDX-License-Identifier: LGPL-3.0-or-later

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

data class BluetoothPermissionState(
    val required: Boolean,
    val connectGranted: Boolean,
    val scanGranted: Boolean,
) {
    val connectMissing: Boolean get() = required && !connectGranted

    val scanMissing: Boolean get() = required && !scanGranted

    val anyMissing: Boolean get() = connectMissing || scanMissing

    companion object {
        // Pre-S relies on the install-time BLUETOOTH/BLUETOOTH_ADMIN grants.
        val SATISFIED = BluetoothPermissionState(required = false, connectGranted = true, scanGranted = true)
    }
}

@Singleton
class BluetoothPermissionStateObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AbstractStateSource<BluetoothPermissionState>(BluetoothPermissionState.SATISFIED) {
        init {
            setState(currentState())
        }

        override fun onStart(owner: LifecycleOwner) {
            // OS doesn't broadcast revocation; re-poll on every foreground.
            setState(currentState())
        }

        fun refresh() {
            setState(currentState())
        }

        private fun currentState(): BluetoothPermissionState {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return BluetoothPermissionState.SATISFIED
            return BluetoothPermissionState(
                required = true,
                connectGranted = granted(Manifest.permission.BLUETOOTH_CONNECT),
                scanGranted = granted(Manifest.permission.BLUETOOTH_SCAN),
            )
        }

        private fun granted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
