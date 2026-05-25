// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VirtualBatterySource
    @Inject
    constructor(
        @ApplicationContext context: Context,
        statusStore: BatteryStatusStore,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private val phoneBattery =
            PhoneBatterySource(
                context = context,
                slotId = VIRTUAL_SLOT_ID,
                statusStore = statusStore,
            )

        override fun onStart(owner: LifecycleOwner) {
            // start() re-arms (stops first), so a redundant onStart is safe.
            phoneBattery.start(scope) { _, _ -> }
        }

        override fun onStop(owner: LifecycleOwner) {
            phoneBattery.stop()
        }
    }
