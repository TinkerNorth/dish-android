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

enum class BluetoothPermissionState {
    NOT_REQUIRED,
    GRANTED,
    DENIED,
}

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
            // OS doesn't broadcast revocation; re-poll on every foreground.
            setState(currentState())
        }

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
