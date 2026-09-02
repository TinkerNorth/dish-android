// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The RECORD_AUDIO grant behind the microphone toggle, published as state so a screen can
 * show the toggle as needing permission instead of as a switch that silently does
 * nothing. Sibling of [BluetoothPermissionStateObserver], and it re-polls for the same
 * reason: the OS broadcasts nothing when a grant is revoked in system settings.
 *
 * The runtime request itself is the caller's (an Activity owns the launcher); this only
 * reports the grant and takes the result. Until the manifest declares the permission,
 * [granted] is simply false, which is the honest state to render.
 */
@Singleton
class MicPermissionGate
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AbstractStateSource<Boolean>(false) {
        init {
            setState(currentlyGranted())
        }

        val granted: Boolean get() = state.value

        override fun onStart(owner: LifecycleOwner) {
            refresh()
        }

        /** Re-read the grant: on returning to the screen, and after a request resolves. */
        fun refresh() {
            setState(currentlyGranted())
        }

        private fun currentlyGranted(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
