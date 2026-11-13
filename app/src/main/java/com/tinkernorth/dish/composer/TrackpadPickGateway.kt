// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.repository.TrackpadPick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Gateway: tells each Direct Steam Controller's native router the trackpad pick in force for its
// slot, whenever the composer's answer moves. Started with the application.
@Singleton
class TrackpadPickGateway
    @Inject
    constructor(
        composer: CapabilityComposer,
        private val native: PhysicalInputNative,
        scope: CoroutineScope,
    ) {
        init {
            scope.launch { composer.trackpadPicks.collect { picks -> push(picks) } }
        }

        private fun push(picks: Map<Int, TrackpadPick>) {
            for ((deviceId, pick) in picks) native.setTrackpadPick(deviceId, pick)
        }
    }
